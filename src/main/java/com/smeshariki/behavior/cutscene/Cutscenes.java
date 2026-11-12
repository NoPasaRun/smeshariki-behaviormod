package com.smeshariki.behavior.cutscene;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.smeshariki.behavior.BehaviorMod;
import com.smeshariki.gameplay.api.Mechanics;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.phys.Vec3;

/**
 * Кат-сцены: загрузка (датапаки {@code data/<мод>/cutscene/<имя>.json} и файлы мира, записанные в игре, {@link CutsceneStore}) и показ. Показ ведёт сервер:
 * он считает тики, шлёт зрителям сцену и конец, делает их неуязвимыми, пока идёт сцена, выполняет дорожку действий и пропускает по просьбе хоста. Камеру,
 * кукол, реплики, звук и свет делает клиент. Дубли записывает {@link Recorder}. Состояние показа в памяти, в мир не пишется: после выхода сцена не продолжается.
 */
public final class Cutscenes {
	private static Map<Identifier, Cutscene> pack = Map.of();
	private static Map<Identifier, Cutscene> world = new HashMap<>();
	private static final List<Run> RUNS = new ArrayList<>();
	/** Где камера сцены у игрока (редактор, показ): вокруг неё сервер грузит и шлёт чанки. */
	private static final Map<UUID, Vec3> VIEW = new HashMap<>();
	private static final Map<UUID, net.minecraft.world.level.ChunkPos> VIEW_SENT = new HashMap<>();
	/** Дальше этого от тела камера не берётся (защита от просьбы грузить любое место мира). */
	private static final double VIEW_MAX = 4000;

	private static final class Run {
		final Identifier id;
		final Cutscene scene;
		final Set<UUID> viewers;
		final Consumer<Boolean> onFinish;
		final MinecraftServer server;
		/** Съёмка в видео: клиент идёт по кадрам, а не по часам, и сам просит конец. */
		int renderFps;
		int tick;
		/** Показ ещё не начался: ждём, пока вокруг места сцены загрузятся чанки (до 5 с), потом шлём сцену зрителям. */
		boolean waiting = true;
		int waited;
		Vec3 where = Vec3.ZERO;
		List<CutsceneBlob> parts = List.of();
		/** Чанк первого кадра камеры (его надо загрузить до показа); {@code null} — камера у игрока, чанки уже есть. */
		@Nullable
		net.minecraft.world.level.ChunkPos start;
		/** Дальность показа, чанков, если сцена просит больше, чем у зрителей настроено; 0 — как у них. */
		int distance;
		/** Зрителям уже расширили дальность; пока тикает, сцену не шлём (даём чанкам доехать). */
		boolean streaming;
		int settle;

		Run(MinecraftServer server, Identifier id, Cutscene scene, Set<UUID> viewers, Consumer<Boolean> onFinish) {
			this.server = server;
			this.id = id;
			this.scene = scene;
			this.viewers = viewers;
			this.onFinish = onFinish;
		}
	}

	private Cutscenes() {
	}

	/** Сцена по id: сначала мира, потом датапака; {@code null} — нет. */
	@Nullable
	public static Cutscene get(Identifier id) {
		Cutscene w = world.get(id);
		return w != null ? w : pack.get(id);
	}

	/** Известные сцены (ошибки в описании не проверяются: смотрите {@link #problems}). */
	public static Set<Identifier> ids() {
		Set<Identifier> out = new TreeSet<>(pack.keySet());
		out.addAll(world.keySet());
		return out;
	}

	/** Реестр сцен игроку для окна управления (только тем, кому доступны команды сцен). */
	static void sendList(ServerPlayer player, String message) {
		if (!canManage(player)) return;
		List<CutsceneList.Entry> out = new ArrayList<>();
		for (Identifier id : ids()) {
			List<String> problems = problems(id);
			out.add(new CutsceneList.Entry(id, world.containsKey(id), length(id), problems.isEmpty() ? "" : problems.getFirst()));
		}
		ServerPlayNetworking.send(player, new CutsceneList(out, message));
	}

	/** Длина сцены в тиках; -1, если такой нет. */
	public static int length(Identifier id) {
		Cutscene scene = get(id);
		return scene == null ? -1 : scene.length();
	}

	/** Что не так со сценой; пусто — всё хорошо. */
	public static List<String> problems(Identifier id) {
		Cutscene scene = get(id);
		return scene == null ? List.of("нет такой сцены: " + id) : scene.problems();
	}

	/** Где сцену записывали в мире; {@code null} — сцена не привязана (её показывает тот, кто запускает, и указывает точку). */
	@Nullable
	public static Vec3 anchor(Identifier id) {
		Cutscene scene = get(id);
		return scene == null ? null : scene.anchor().orElse(null);
	}

	/** Чанк камеры игрока, если она сейчас улетела от тела; {@code null} — чанки вокруг тела. */
	@Nullable
	public static net.minecraft.world.level.ChunkPos viewChunk(ServerPlayer player) {
		Vec3 v = VIEW.get(player.getUUID());
		return v == null ? null : new net.minecraft.world.level.ChunkPos(net.minecraft.util.Mth.floor(v.x) >> 4, net.minecraft.util.Mth.floor(v.z) >> 4);
	}

	/** Пересчитать, какие чанки слать игроку (вокруг камеры или, если камеры нет, вокруг тела); игра сама делает это, только когда тело сменило секцию. */
	private static void refreshTracking(ServerPlayer player) {
		((com.smeshariki.behavior.mixin.ChunkMapAccessor) player.level().getChunkSource().chunkMap).behaviormod$updateChunkTracking(player);
	}

	private static void viewCenter(ServerPlayer player, ViewCenter msg) {
		MinecraftServer server = player.level().getServer();
		if (!msg.on()) {
			if (VIEW.remove(player.getUUID()) != null) {
				VIEW_SENT.remove(player.getUUID());
				refreshTracking(player);
			}
			return;
		}
		if (!isHost(player) && !watching(player.getUUID())) return;
		if (msg.pos().distanceTo(player.position()) > VIEW_MAX || !Double.isFinite(msg.pos().x + msg.pos().y + msg.pos().z)) return;
		VIEW.put(player.getUUID(), msg.pos());
	}

	/** Раз в несколько тиков: держать чанки вокруг камер загруженными и переключать, куда игроку шлют чанки. */
	private static void tickViews(MinecraftServer server) {
		for (Map.Entry<UUID, Vec3> e : List.copyOf(VIEW.entrySet())) {
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null) {
				VIEW.remove(e.getKey());
				VIEW_SENT.remove(e.getKey());
				continue;
			}
			net.minecraft.world.level.ChunkPos c = viewChunk(p);
			boolean moved = !c.equals(VIEW_SENT.get(e.getKey()));
			if (!moved && server.getTickCount() % 10 != 0) continue;
			int radius = Math.min(32, server.getPlayerList().getViewDistance() + 1);
			p.level().getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PLAYER_SPAWN, c, radius);
			if (moved) {
				VIEW_SENT.put(e.getKey(), c);
				refreshTracking(p);
			}
		}
	}

	/** Идёт ли сейчас сцена с этим зрителем. */
	public static boolean watching(UUID player) {
		return RUNS.stream().anyMatch(r -> r.viewers.contains(player));
	}

	/** Занят ли игрок сценой: смотрит её или записывает дубль. Напарник такого ведущего стоит, сам он неуязвим. */
	public static boolean busy(UUID player) {
		return watching(player) || Recorder.recording(player);
	}

	/**
	 * Показать сцену зрителям. {@code origin} — точка привязки: координаты кадров отсчитываются от неё; {@code null} — где сцену записывали, а если не
	 * записывали — где стоит первый зритель. {@code onFinish} получает {@code true}, если сцена досчитана, и {@code false}, если пропущена или зрителей не осталось.
	 *
	 * @return {@code false}, если сцены нет, в ней ошибки, нет зрителей или кто-то из них уже занят сценой (ничего не началось, {@code onFinish} не вызывается)
	 */
	public static boolean play(Identifier id, Collection<ServerPlayer> viewers, @Nullable Vec3 origin, @Nullable Consumer<Boolean> onFinish) {
		return play(id, viewers, origin, onFinish, 0);
	}

	/** Как {@link #play}, но {@code renderFps > 0} — съёмка в видео с таким числом кадров в секунду (сцена не кончается по тикам сервера: клиент закончит сам). */
	public static boolean play(Identifier id, Collection<ServerPlayer> viewers, @Nullable Vec3 origin, @Nullable Consumer<Boolean> onFinish, int renderFps) {
		Cutscene scene = get(id);
		if (scene == null || !scene.problems().isEmpty() || viewers.isEmpty()) return false;
		for (ServerPlayer p : viewers) {
			if (busy(p.getUUID())) return false;
		}
		Vec3 where = origin != null ? origin : scene.anchor().orElseGet(() -> viewers.iterator().next().position());
		Set<UUID> ids = new HashSet<>();
		for (ServerPlayer p : viewers) ids.add(p.getUUID());
		MinecraftServer server = viewers.iterator().next().level().getServer();
		Run run = new Run(server, id, scene, ids, onFinish == null ? done -> { } : onFinish);
		run.renderFps = renderFps;
		run.where = where;
		run.parts = CutsceneBlob.split(id, renderFps > 0 ? CutsceneBlob.RENDER : CutsceneBlob.WATCH, where, 0, renderFps > 0 ? String.valueOf(renderFps) : "", scene);
		// как у игрока в майне: чанки вокруг камеры держит и шлёт tickViews (билет viewDistance+1 вокруг неё); здесь только первый кадр, чтобы не показать пустоту
		run.start = scene.hasCamera() ? chunkOf(scene.view(0).pos().add(where)) : null;
		// сцена просит дальше, чем настроено у игроков: на время показа круг отправки чанков вокруг камеры расширяется (сервером и пакетом радиуса для клиента),
		// а загружаются только чанки в конусе взгляда вдоль ближайшего участка пути, чтобы не держать весь круг
		run.distance = scene.hasCamera() && scene.distance() > server.getPlayerList().getViewDistance() ? scene.distance() : 0;
		RUNS.add(run);
		return true;
	}

	private static net.minecraft.world.level.ChunkPos chunkOf(Vec3 p) {
		return new net.minecraft.world.level.ChunkPos(net.minecraft.util.Mth.floor(p.x) >> 4, net.minecraft.util.Mth.floor(p.z) >> 4);
	}

	/** Держать чанки вокруг камеры в её положении через 2 с (билет, как у игрока), чтобы быстрый пролёт не обгонял загрузку. */
	private static void holdAhead(Run run, ServerPlayer viewer, int tick) {
		if (!run.scene.hasCamera()) return;
		int radius = Math.min(32, run.server.getPlayerList().getViewDistance() + 1);
		viewer.level().getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PLAYER_SPAWN, chunkOf(run.scene.view(Math.min(run.scene.length(), tick + 40)).pos().add(run.where)), radius);
	}

	// -- дальность показа выше настроенной --

	/** Зрители, которым на время показа расширена дальность, и до скольких чанков. */
	private static final Map<UUID, Integer> STREAM = new HashMap<>();

	/** Шаг выборки пути камеры, тиков, и на сколько вперёд держим чанки конуса. */
	private static final int SAMPLE_STEP = 10, WINDOW = 120;

	/** Дальность отправки чанков игроку на время показа (чанков), 0 — как обычно; её читает {@code ChunkMapMixin}. */
	public static int streamDistance(ServerPlayer player) {
		return STREAM.getOrDefault(player.getUUID(), 0);
	}

	/** Расширить зрителям круг отправки чанков до {@code run.distance} и сообщить клиенту радиус обычным пакетом. */
	private static void beginStream(Run run) {
		run.streaming = true;
		for (UUID id : run.viewers) {
			ServerPlayer p = run.server.getPlayerList().getPlayer(id);
			if (p == null) continue;
			STREAM.put(id, run.distance);
			p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket(run.distance));
			// центр отправки — на первый кадр камеры, чтобы чанки поехали ещё до начала сцены (потом клиент сам шлёт ViewCenter)
			if (run.start != null && !VIEW.containsKey(id)) VIEW.put(id, run.scene.view(0).pos().add(run.where));
			refreshTracking(p);
		}
	}

	/** Вернуть зрителям обычную дальность. */
	private static void endStream(Run run) {
		if (!run.streaming) return;
		run.streaming = false;
		for (UUID id : run.viewers) {
			if (STREAM.remove(id) == null) continue;
			ServerPlayer p = run.server.getPlayerList().getPlayer(id);
			if (p == null) continue;
			p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket(run.server.getPlayerList().getViewDistance()));
			refreshTracking(p);
		}
	}

	/** Держать загруженными чанки, которые камера увидит на ближайшие {@link #WINDOW} тиков: круг {@code run.distance} ∩ конус взгляда (по билету на чанк). */
	private static void holdCone(Run run, ServerPlayer viewer, int fromTick) {
		java.util.Set<Long> seen = new HashSet<>();
		for (int t = fromTick; t <= fromTick + WINDOW; t += SAMPLE_STEP) addCone(run, viewer, Math.min(t, run.scene.length()), seen);
	}

	private static void addCone(Run run, ServerPlayer viewer, int t, java.util.Set<Long> seen) {
		Cutscene.View v = run.scene.view(t);
		Vec3 p = v.pos().add(run.where);
		int cx = net.minecraft.util.Mth.floor(p.x) >> 4, cz = net.minecraft.util.Mth.floor(p.z) >> 4, radius = run.distance;
		// поле зрения по горизонтали ≈ 1,8 × угла кадра (экран шире, чем высокий) с запасом 25° на повороты между выборками
		double half = Math.min(Math.PI, Math.toRadians(Math.min(175.0, v.fov() * 1.8) / 2 + 25));
		double dirX = -Math.sin(Math.toRadians(v.yaw())), dirZ = Math.cos(Math.toRadians(v.yaw()));
		int near = Math.abs(v.pitch()) > 55 ? 4 : 2;
		double cosHalf = Math.cos(half);
		var cache = viewer.level().getChunkSource();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (dx * dx + dz * dz > radius * radius) continue;
				boolean include = Math.max(Math.abs(dx), Math.abs(dz)) <= near;
				if (!include) {
					double vx = (cx + dx) * 16 + 8 - p.x, vz = (cz + dz) * 16 + 8 - p.z, dist = Math.hypot(vx, vz);
					include = half >= Math.PI || (vx * dirX + vz * dirZ) / dist >= cosHalf;
				}
				if (include && seen.add(net.minecraft.world.level.ChunkPos.pack(cx + dx, cz + dz))) {
					cache.addTicketWithRadius(net.minecraft.server.level.TicketType.PLAYER_SPAWN, new net.minecraft.world.level.ChunkPos(cx + dx, cz + dz), 1);
				}
			}
		}
	}

	// -- место сцены: чанки вокруг точки привязки --

	/** Верхний предел радиуса держимой области, чанков (25×25 = 625 чанков — столько же, сколько вокруг игрока при дальности 12). */
	private static final int AREA_MAX = 12;

	private record Area(Vec3 center, long until) {
	}

	private static final Map<UUID, Area> AREA = new HashMap<>();

	private static int areaRadius(MinecraftServer server) {
		return Math.min(server.getPlayerList().getViewDistance(), AREA_MAX);
	}

	/**
	 * Держать загруженными чанки вокруг места сцены (радиус — дальность прорисовки сервера, не больше {@link #AREA_MAX}), пока игрок в редакторе, смотрит сцену или пишет дубль;
	 * после — ещё 10 с и отпускаем (билеты сами истекают).
	 */
	static void keepArea(ServerPlayer player, Vec3 center) {
		MinecraftServer server = player.level().getServer();
		AREA.put(player.getUUID(), new Area(center, server.getTickCount() + 200));
		holdTicket(player, center, server);
	}

	private static void holdTicket(ServerPlayer player, Vec3 center, MinecraftServer server) {
		net.minecraft.world.level.ChunkPos c = new net.minecraft.world.level.ChunkPos(net.minecraft.util.Mth.floor(center.x) >> 4, net.minecraft.util.Mth.floor(center.z) >> 4);
		player.level().getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PLAYER_SPAWN, c, areaRadius(server) + 1);
	}

	private static void tickAreas(MinecraftServer server) {
		long now = server.getTickCount();
		for (Map.Entry<UUID, Area> e : List.copyOf(AREA.entrySet())) {
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			Area a = e.getValue();
			if (p == null) {
				AREA.remove(e.getKey());
				continue;
			}
			if (VIEW.containsKey(e.getKey()) || busy(e.getKey())) {
				a = new Area(a.center(), now + 200);
				AREA.put(e.getKey(), a);
			} else if (now > a.until()) {
				AREA.remove(e.getKey());
				continue;
			}
			if (now % 10 == 0) holdTicket(p, a.center(), server);
		}
	}

	/** Загружены ли чанки вокруг первого кадра камеры (4 чанка в каждую сторону или меньше, если дальность прорисовки меньше). */
	private static boolean areaReady(Run run, ServerPlayer viewer) {
		if (run.start == null) return true;
		int r = Math.min(4, run.server.getPlayerList().getViewDistance());
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (viewer.level().getChunkSource().getChunkNow(run.start.x() + dx, run.start.z() + dz) == null) return false;
			}
		}
		return true;
	}

	private static void startRun(Run run) {
		run.waiting = false;
		for (UUID id : run.viewers) {
			ServerPlayer p = run.server.getPlayerList().getPlayer(id);
			if (p == null) continue;
			for (CutsceneBlob b : run.parts) ServerPlayNetworking.send(p, b);
		}
		runActions(run, 0);
	}

	/** Остановить сцену досрочно (например, уровень сброшен). */
	public static void stop(Identifier id, MinecraftServer server) {
		for (Run run : List.copyOf(RUNS)) {
			if (run.id.equals(id)) finish(run, server, false);
		}
	}

	/** Сохранить сцену в мир (и сразу сделать известной). */
	static boolean save(MinecraftServer server, Identifier id, Cutscene scene) {
		if (!CutsceneStore.save(server, id, scene)) return false;
		world.put(id, scene);
		return true;
	}

	/** Игрок, чьи правки принимаются: хост (на выделенном сервере — тот, кому доступны команды). */
	static boolean isHost(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		return server.isDedicatedServer() ? Commands.LEVEL_GAMEMASTERS.check(player.permissions()) : server.isSingleplayerOwner(player.nameAndId());
	}

	/** Открыть сцену в редакторе игрока (нет такой — пустая), курсор на тике {@code at}. */
	static void openEditor(ServerPlayer player, Identifier id, int at) {
		Cutscene scene = get(id);
		if (scene == null) scene = Cutscene.empty(200, null);
		Vec3 anchor = scene.anchor().orElse(player.position());
		keepArea(player, anchor);
		for (CutsceneBlob b : CutsceneBlob.split(id, CutsceneBlob.EDIT, anchor, at, "", scene)) ServerPlayNetworking.send(player, b);
	}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(CutsceneBlob.TYPE, CutsceneBlob.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CutsceneBlob.TYPE, CutsceneBlob.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CutsceneEnd.TYPE, CutsceneEnd.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CutsceneList.TYPE, CutsceneList.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CutsceneListRequest.TYPE, CutsceneListRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CutsceneAction.TYPE, CutsceneAction.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ViewCenter.TYPE, ViewCenter.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CutsceneSkip.TYPE, CutsceneSkip.CODEC);
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(BehaviorMod.id("cutscenes"), new SimpleJsonResourceReloadListener<>(Cutscene.CODEC, FileToIdConverter.json("cutscene")) {
			@Override
			protected void apply(Map<Identifier, Cutscene> loaded, ResourceManager manager, ProfilerFiller profiler) {
				pack = loaded;
			}
		});
		ServerLifecycleEvents.SERVER_STARTING.register(server -> world = CutsceneStore.loadAll(server));
		registerCommands();
		CutsceneBlob.Assembler uploads = new CutsceneBlob.Assembler();
		ServerPlayNetworking.registerGlobalReceiver(CutsceneBlob.TYPE, (blob, ctx) -> {
			ServerPlayer player = ctx.player();
			if (blob.purpose() != CutsceneBlob.SAVE || !isHost(player)) return;
			Cutscene scene = uploads.add(blob, e -> player.sendSystemMessage(Component.literal("Сцена не принята: " + e)));
			if (scene == null) return;
			MinecraftServer server = player.level().getServer();
			player.sendSystemMessage(Component.literal(save(server, blob.id(), scene) ? "Сцена " + blob.id() + " сохранена." : "Сцена " + blob.id() + " не сохранилась (см. журнал)."));
		});
		ServerPlayNetworking.registerGlobalReceiver(CutsceneSkip.TYPE, (msg, ctx) -> skip(ctx.player()));
		ServerPlayNetworking.registerGlobalReceiver(CutsceneListRequest.TYPE, (msg, ctx) -> sendList(ctx.player(), ""));
		ServerPlayNetworking.registerGlobalReceiver(CutsceneAction.TYPE, (msg, ctx) -> action(ctx.player(), msg));
		ServerPlayNetworking.registerGlobalReceiver(ViewCenter.TYPE, (msg, ctx) -> viewCenter(ctx.player(), msg));
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !(entity instanceof ServerPlayer p && busy(p.getUUID())));
		ServerTickEvents.END_SERVER_TICK.register(Cutscenes::tick);
		ServerTickEvents.END_SERVER_TICK.register(Cutscenes::tickViews);
		ServerTickEvents.END_SERVER_TICK.register(Cutscenes::tickAreas);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			leave(handler.player.getUUID(), server);
			VIEW.remove(handler.player.getUUID());
			VIEW_SENT.remove(handler.player.getUUID());
			AREA.remove(handler.player.getUUID());
			STREAM.remove(handler.player.getUUID());
			Recorder.abort(handler.player.getUUID(), server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Run run : List.copyOf(RUNS)) finish(run, server, false);
			Recorder.abortAll(server);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			RUNS.clear();
			STREAM.clear();
			VIEW.clear();
			VIEW_SENT.clear();
			AREA.clear();
			world = new HashMap<>();
		});
		Recorder.init();
	}

	static final String BAD_NAME = "Имя сцены: буквы, цифры, _ - . / (русские буквы заменяются латиницей), без пробелов; например intro или intro/part_1.";

	private static final String[] TRANSLIT = { "a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "h", "ts", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya" };

	/**
	 * Идентификатор сцены из того, что ввёл человек: без пространства — наше; заглавные становятся строчными, пробелы — «_», русские буквы — латиницей.
	 * {@code null} — имя не годится (раньше тихо подставлялась сцена {@code scene}, и чужая сцена перезаписывалась).
	 */
	@Nullable
	static Identifier parse(String text) {
		StringBuilder sb = new StringBuilder();
		for (char c : text.trim().toLowerCase(java.util.Locale.ROOT).toCharArray()) {
			if (c >= 'а' && c <= 'я') sb.append(TRANSLIT[c - 'а']);
			else if (c == 'ё') sb.append('e');
			else if (Character.isWhitespace(c)) sb.append('_');
			else sb.append(c);
		}
		String name = sb.toString();
		if (name.isEmpty() || name.endsWith(":") || name.startsWith(":")) return null;
		return Identifier.tryParse(name.contains(":") ? name : BehaviorMod.NAMESPACE + ":" + name);
	}

	/** Кому доступно окно управления: хосту (как сохранение из редактора) и тем, у кого есть команды. */
	static boolean canManage(ServerPlayer player) {
		return isHost(player) || Commands.LEVEL_GAMEMASTERS.check(player.permissions());
	}

	/** Действие из окна управления. Итог создания и удаления идёт в окно (строка внизу), ошибки правки и показа — в чат: окно к тому времени закрыто. */
	private static void action(ServerPlayer player, CutsceneAction a) {
		if (!canManage(player)) return;
		MinecraftServer server = player.level().getServer();
		Identifier id = parse(a.name());
		if (id == null) {
			sendList(player, BAD_NAME);
			if (a.kind() == CutsceneAction.EDIT || a.kind() == CutsceneAction.PLAY) player.sendSystemMessage(Component.literal(BAD_NAME));
			return;
		}
		switch (a.kind()) {
			case CutsceneAction.CREATE -> {
				if (busy(player.getUUID())) {
					sendList(player, "Сначала закончи сцену или запись.");
				} else if (get(id) != null) {
					sendList(player, "Сцена " + id + " уже есть: выбери другое имя или нажми «Править».");
				} else if (!save(server, id, Cutscene.empty(200, player.position()))) {
					sendList(player, "Сцена " + id + " не создалась: файл не записался (см. журнал).");
				} else {
					openEditor(player, id, 0);
				}
			}
			case CutsceneAction.DELETE -> {
				Cutscene had = world.get(id);
				if (had == null) {
					sendList(player, get(id) != null ? "Сцена " + id + " лежит в файле мода или датапака: из мира её не удалить." : "Сцены " + id + " нет.");
					return;
				}
				stop(id, server);
				world.remove(id);
				if (!CutsceneStore.delete(server, id)) {
					world.put(id, had);
					sendList(player, "Сцена " + id + " не удалилась: файл занят (см. журнал).");
					return;
				}
				sendList(player, "Сцена " + id + " удалена." + (get(id) != null ? " Осталась версия из файла мода." : ""));
			}
			case CutsceneAction.EDIT -> {
				if (busy(player.getUUID())) player.sendSystemMessage(Component.literal("Сначала закончи сцену или запись."));
				else if (get(id) == null) player.sendSystemMessage(Component.literal("Сцены " + id + " нет."));
				else openEditor(player, id, 0);
			}
			case CutsceneAction.PLAY -> {
				if (get(id) == null) player.sendSystemMessage(Component.literal("Сцены " + id + " нет."));
				else if (!problems(id).isEmpty()) player.sendSystemMessage(Component.literal("Сцена " + id + " не играет: " + problems(id).getFirst()));
				else if (!play(id, List.of(player), null, null)) player.sendSystemMessage(Component.literal("Сцена " + id + " не началась: сначала закончи другую сцену или запись."));
			}
			default -> { }
		}
	}

	private static void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) -> dispatcher.register(Commands.literal("cutscene").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("play").then(Commands.argument("id", StringArgumentType.string()).executes(ctx -> {
					CommandSourceStack source = ctx.getSource();
					Identifier id = parse(StringArgumentType.getString(ctx, "id"));
					if (id == null) {
						ctx.getSource().sendFailure(Component.literal(BAD_NAME));
						return 0;
					}
					ServerPlayer player = source.getPlayerOrException();
					if (get(id) == null) {
						source.sendFailure(Component.literal("Нет такой сцены. Есть: " + ids()));
						return 0;
					}
					if (!play(id, List.of(player), null, null)) {
						source.sendFailure(Component.literal("Не удалось показать: " + problems(id)));
						return 0;
					}
					return 1;
				})))
				.then(Commands.literal("edit").then(Commands.argument("id", StringArgumentType.string()).executes(ctx -> {
					ServerPlayer player = ctx.getSource().getPlayerOrException();
					Identifier id = parse(StringArgumentType.getString(ctx, "id"));
					if (id == null) {
						ctx.getSource().sendFailure(Component.literal(BAD_NAME));
						return 0;
					}
					if (busy(player.getUUID())) {
						ctx.getSource().sendFailure(Component.literal("Сначала закончи сцену или запись."));
						return 0;
					}
					openEditor(player, id, 0);
					return 1;
				})))
				.then(Commands.literal("record").then(Commands.argument("id", StringArgumentType.string()).executes(ctx -> record(ctx.getSource(), StringArgumentType.getString(ctx, "id"), 0, false, 0))
						.then(Commands.argument("tick", IntegerArgumentType.integer(0, Cutscene.MAX_LENGTH)).executes(ctx -> record(ctx.getSource(), StringArgumentType.getString(ctx, "id"), IntegerArgumentType.getInteger(ctx, "tick"), false, 0))
								.then(Commands.argument("edit", BoolArgumentType.bool()).executes(ctx -> record(ctx.getSource(), StringArgumentType.getString(ctx, "id"), IntegerArgumentType.getInteger(ctx, "tick"), BoolArgumentType.getBool(ctx, "edit"), 0))
										.then(Commands.argument("autostop", IntegerArgumentType.integer(0, 60)).executes(ctx -> record(ctx.getSource(), StringArgumentType.getString(ctx, "id"), IntegerArgumentType.getInteger(ctx, "tick"), BoolArgumentType.getBool(ctx, "edit"), IntegerArgumentType.getInteger(ctx, "autostop"))))))))
				.then(Commands.literal("render").then(Commands.argument("id", StringArgumentType.string()).executes(ctx -> render(ctx.getSource(), StringArgumentType.getString(ctx, "id"), 30))
						.then(Commands.argument("fps", IntegerArgumentType.integer(1, 120)).executes(ctx -> render(ctx.getSource(), StringArgumentType.getString(ctx, "id"), IntegerArgumentType.getInteger(ctx, "fps"))))))
				.then(Commands.literal("stop").executes(ctx -> {
					ServerPlayer player = ctx.getSource().getPlayerOrException();
					if (Recorder.finish(player.getUUID(), player.level().getServer(), true)) return 1;
					ctx.getSource().sendFailure(Component.literal("Запись не идёт."));
					return 0;
				}))
				.then(Commands.literal("delete").then(Commands.argument("id", StringArgumentType.string()).executes(ctx -> {
					Identifier id = parse(StringArgumentType.getString(ctx, "id"));
					if (id == null) {
						ctx.getSource().sendFailure(Component.literal(BAD_NAME));
						return 0;
					}
					MinecraftServer server = ctx.getSource().getServer();
					boolean had = world.remove(id) != null;
					CutsceneStore.delete(server, id);
					ctx.getSource().sendSuccess(() -> Component.literal(had ? "Сцена мира " + id + " удалена." : "В мире такой сцены нет."), false);
					return had ? 1 : 0;
				})))
				.then(Commands.literal("list").executes(ctx -> {
					ctx.getSource().sendSuccess(() -> Component.literal("Сцены: " + ids()), false);
					return 1;
				}))));
	}

	private static int render(CommandSourceStack source, String text, int fps) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Identifier id = parse(text);
		if (id == null) {
			source.sendFailure(Component.literal(BAD_NAME));
			return 0;
		}
		if (get(id) == null || !play(id, List.of(player), null, null, fps)) {
			source.sendFailure(Component.literal("Не удалось снять: " + problems(id)));
			return 0;
		}
		return 1;
	}

	private static int record(CommandSourceStack source, String text, int tick, boolean edit, int autoStopSeconds) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Identifier id = parse(text);
		if (id == null) {
			source.sendFailure(Component.literal(BAD_NAME));
			return 0;
		}
		String error = Recorder.begin(player, id, tick, edit, autoStopSeconds);
		if (error != null) {
			source.sendFailure(Component.literal(error));
			return 0;
		}
		return 1;
	}

	private static void tick(MinecraftServer server) {
		for (Run run : List.copyOf(RUNS)) {
			if (run.waiting) {
				ServerPlayer first = null;
				for (UUID id : run.viewers) {
					first = server.getPlayerList().getPlayer(id);
					if (first != null) break;
				}
				if (first == null) {
					finish(run, server, false);
					continue;
				}
				// до 5 с ждём, пока то, что камера увидит в начале, загрузится; сцену и её действия начинаем только потом
				if (run.start != null && run.waited % 10 == 0) {
					first.level().getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PLAYER_SPAWN, run.start, Math.min(32, server.getPlayerList().getViewDistance() + 1));
				}
				if (run.distance > 0 && run.waited % 10 == 0) holdCone(run, first, 0);
				if (run.streaming) {
					// дальность расширена, чанки едут к зрителю: секунду даём им доехать и только потом шлём сцену
					if (--run.settle <= 0) startRun(run);
				} else if (areaReady(run, first) || ++run.waited > 100) {
					if (run.distance > 0) {
						beginStream(run);
						run.settle = 20;
					} else {
						startRun(run);
					}
				} else if (run.waited % 20 == 1) {
					first.sendOverlayMessage(Component.literal("Загрузка места сцены…"));
				}
				continue;
			}
			int t = ++run.tick;
			if (t % 10 == 0) {
				for (UUID id : run.viewers) {
					ServerPlayer viewer = server.getPlayerList().getPlayer(id);
					if (viewer != null) {
						holdAhead(run, viewer, t);
						if (run.distance > 0) holdCone(run, viewer, t);
						break;
					}
				}
			}
			runActions(run, t);
			if (run.renderFps == 0 && t >= run.scene.length()) finish(run, server, true);
		}
	}

	private static void runActions(Run run, int tick) {
		List<ServerPlayer> players = new ArrayList<>();
		for (UUID id : run.viewers) {
			ServerPlayer p = run.server.getPlayerList().getPlayer(id);
			if (p != null) players.add(p);
		}
		for (ActionKey key : run.scene.actions()) {
			if (key.tick() != tick) continue;
			Mechanics.Action action = Mechanics.action(key.action());
			if (action == null) {
				BehaviorMod.LOGGER.warn("Сцена {}: нет действия {}", run.id, key.action());
				continue;
			}
			try {
				action.run(run.server, players, new ArgParams(key.args()));
			} catch (RuntimeException e) {
				BehaviorMod.LOGGER.warn("Сцена {}: действие {} не выполнилось", run.id, key.action(), e);
			}
		}
	}

	private static void leave(UUID player, MinecraftServer server) {
		for (Run run : List.copyOf(RUNS)) {
			if (!run.viewers.remove(player)) continue;
			if (run.viewers.isEmpty()) finish(run, server, false);
		}
	}

	/** Пропуск: только хост. Сцена кончается у всех зрителей; идущая запись дубля — тоже заканчивается. */
	private static void skip(ServerPlayer player) {
		if (!isHost(player)) return;
		MinecraftServer server = player.level().getServer();
		if (Recorder.finish(player.getUUID(), server, true)) return;
		for (Run run : List.copyOf(RUNS)) {
			if (run.viewers.contains(player.getUUID())) finish(run, server, false);
		}
	}

	private static void finish(Run run, MinecraftServer server, boolean completed) {
		if (!RUNS.remove(run)) return;
		endStream(run);
		for (UUID id : run.viewers) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) ServerPlayNetworking.send(p, new CutsceneEnd());
		}
		run.onFinish.accept(completed);
	}
}
