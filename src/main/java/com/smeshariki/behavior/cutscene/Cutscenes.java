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

	private static void viewCenter(ServerPlayer player, ViewCenter msg) {
		MinecraftServer server = player.level().getServer();
		if (!msg.on()) {
			if (VIEW.remove(player.getUUID()) != null) {
				VIEW_SENT.remove(player.getUUID());
				player.level().getChunkSource().move(player);
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
				p.level().getChunkSource().move(p);
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
		RUNS.add(run);
		List<CutsceneBlob> parts = CutsceneBlob.split(id, renderFps > 0 ? CutsceneBlob.RENDER : CutsceneBlob.WATCH, where, 0, renderFps > 0 ? String.valueOf(renderFps) : "", scene);
		for (ServerPlayer p : viewers) {
			for (CutsceneBlob b : parts) ServerPlayNetworking.send(p, b);
		}
		runActions(run, 0);
		return true;
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
		for (CutsceneBlob b : CutsceneBlob.split(id, CutsceneBlob.EDIT, anchor, at, "", scene)) ServerPlayNetworking.send(player, b);
	}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(CutsceneBlob.TYPE, CutsceneBlob.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CutsceneBlob.TYPE, CutsceneBlob.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CutsceneEnd.TYPE, CutsceneEnd.CODEC);
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
		ServerPlayNetworking.registerGlobalReceiver(ViewCenter.TYPE, (msg, ctx) -> viewCenter(ctx.player(), msg));
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !(entity instanceof ServerPlayer p && busy(p.getUUID())));
		ServerTickEvents.END_SERVER_TICK.register(Cutscenes::tick);
		ServerTickEvents.END_SERVER_TICK.register(Cutscenes::tickViews);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			leave(handler.player.getUUID(), server);
			VIEW.remove(handler.player.getUUID());
			VIEW_SENT.remove(handler.player.getUUID());
			Recorder.abort(handler.player.getUUID(), server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Run run : List.copyOf(RUNS)) finish(run, server, false);
			Recorder.abortAll(server);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			RUNS.clear();
			VIEW.clear();
			VIEW_SENT.clear();
			world = new HashMap<>();
		});
		Recorder.init();
	}

	/** Идентификатор из команды: без пространства — наше. */
	static Identifier parse(String text) {
		Identifier id = Identifier.tryParse(text.contains(":") ? text : BehaviorMod.NAMESPACE + ":" + text);
		return id == null ? BehaviorMod.id("scene") : id;
	}

	private static void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) -> dispatcher.register(Commands.literal("cutscene").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("play").then(Commands.argument("id", StringArgumentType.string()).executes(ctx -> {
					CommandSourceStack source = ctx.getSource();
					Identifier id = parse(StringArgumentType.getString(ctx, "id"));
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
					if (busy(player.getUUID())) {
						ctx.getSource().sendFailure(Component.literal("Сначала закончи сцену или запись."));
						return 0;
					}
					openEditor(player, id, 0);
					return 1;
				})))
				.then(Commands.literal("record").then(Commands.argument("id", StringArgumentType.string()).executes(ctx -> record(ctx.getSource(), StringArgumentType.getString(ctx, "id"), 0, false))
						.then(Commands.argument("tick", IntegerArgumentType.integer(0, Cutscene.MAX_LENGTH)).executes(ctx -> record(ctx.getSource(), StringArgumentType.getString(ctx, "id"), IntegerArgumentType.getInteger(ctx, "tick"), false))
								.then(Commands.argument("edit", BoolArgumentType.bool()).executes(ctx -> record(ctx.getSource(), StringArgumentType.getString(ctx, "id"), IntegerArgumentType.getInteger(ctx, "tick"), BoolArgumentType.getBool(ctx, "edit")))))))
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
		if (get(id) == null || !play(id, List.of(player), null, null, fps)) {
			source.sendFailure(Component.literal("Не удалось снять: " + problems(id)));
			return 0;
		}
		return 1;
	}

	private static int record(CommandSourceStack source, String text, int tick, boolean edit) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		String error = Recorder.begin(player, parse(text), tick, edit);
		if (error != null) {
			source.sendFailure(Component.literal(error));
			return 0;
		}
		return 1;
	}

	private static void tick(MinecraftServer server) {
		for (Run run : List.copyOf(RUNS)) {
			int t = ++run.tick;
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
		for (UUID id : run.viewers) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) ServerPlayNetworking.send(p, new CutsceneEnd());
		}
		run.onFinish.accept(completed);
	}
}
