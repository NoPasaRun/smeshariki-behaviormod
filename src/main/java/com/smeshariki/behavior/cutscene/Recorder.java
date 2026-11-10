package com.smeshariki.behavior.cutscene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.smeshariki.behavior.BehaviorMod;
import com.smeshariki.gameplay.api.Mechanics;
import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.gameplay.character.Singletons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Запись дублей. Игрок играет своим персонажем, сервер снимает его тело каждый тик, а остальные актёры сцены в это время играют куклами
 * (клиент получает сцену как {@link CutsceneBlob#RECORD}). Перед дублем после отсчёта мир возвращается к началу сеанса — то, что умеют запоминать механики
 * ({@code Mechanics.snapshots}: шары, лава); блоки мира не откатываются (это делает уровень). После дубля игрок встаёт на место, где начал, а запись становится
 * клипом дорожки своего персонажа; пересекающиеся прежние клипы выключаются (не удаляются: их можно вернуть в редакторе).
 */
final class Recorder {
	/** Тиков отсчёта перед дублем. */
	private static final int COUNTDOWN = 60;
	/** Как далеко от места записи откатываются постройки механик. */
	private static final int RADIUS = 96;

	private static final Map<UUID, Session> SESSIONS = new HashMap<>();

	private static final class Session {
		final Identifier id;
		final Cutscene scene;
		final int startTick;
		final boolean edit;
		final Vec3 startPos;
		final float startYaw;
		final ResourceKey<Level> dimension;
		final Identifier character;
		final Map<Identifier, CompoundTag> snapshots = new HashMap<>();
		final List<Frame> frames = new ArrayList<>();
		int countdown = COUNTDOWN;

		Session(Identifier id, Cutscene scene, int startTick, boolean edit, Vec3 startPos, float startYaw, ResourceKey<Level> dimension, Identifier character) {
			this.id = id;
			this.scene = scene;
			this.startTick = startTick;
			this.edit = edit;
			this.startPos = startPos;
			this.startYaw = startYaw;
			this.dimension = dimension;
			this.character = character;
		}
	}

	/** Квадрат вокруг места записи: механики берут снимок по контурам в плоскости XZ. */
	private record Square(ResourceKey<Level> dimension, Vec3 center, int radius) implements Mechanics.Area {
		@Override
		public boolean contains(Vec3 pos) {
			return Math.abs(pos.x - center.x) <= radius && Math.abs(pos.z - center.z) <= radius;
		}

		@Override
		public double[] boundsXZ() {
			return new double[] { center.x - radius, center.z - radius, center.x + radius, center.z + radius };
		}
	}

	private Recorder() {
	}

	static void init() {
		ServerTickEvents.END_SERVER_TICK.register(Recorder::tick);
	}

	static boolean recording(UUID player) {
		return SESSIONS.containsKey(player);
	}

	/** @return причина отказа или {@code null} — отсчёт пошёл */
	@Nullable
	static String begin(ServerPlayer player, Identifier id, int startTick, boolean edit) {
		if (!Cutscenes.isHost(player)) return "Записывать может только хост.";
		if (Cutscenes.busy(player.getUUID())) return "Сначала закончи сцену или запись.";
		if (!(CharacterEntity.body(player) instanceof CharacterEntity body)) return "Записывать можно только персонажем: сначала выбери смешарика.";
		Cutscene scene = Cutscenes.get(id);
		Vec3 pos = body.position();
		if (scene == null) scene = Cutscene.empty(Math.max(100, startTick + 40), new Vec3(Math.floor(pos.x) + 0.5, Math.floor(pos.y), Math.floor(pos.z) + 0.5));
		else if (scene.anchor().isEmpty()) scene = scene.withAnchor(new Vec3(Math.floor(pos.x) + 0.5, Math.floor(pos.y), Math.floor(pos.z) + 0.5));
		Session s = new Session(id, scene, startTick, edit, pos, body.getYRot(), body.level().dimension(), EntityType.getKey(body.getType()));
		ServerLevel level = (ServerLevel) body.level();
		Mechanics.Area area = new Square(level.dimension(), pos, RADIUS);
		for (Map.Entry<Identifier, Mechanics.Snapshot> e : Mechanics.snapshots().entrySet()) {
			try {
				s.snapshots.put(e.getKey(), e.getValue().take(level, area));
			} catch (RuntimeException ex) {
				BehaviorMod.LOGGER.warn("Снимок {} не снялся", e.getKey(), ex);
			}
		}
		SESSIONS.put(player.getUUID(), s);
		player.sendSystemMessage(Component.literal("Запись " + id + " с тика " + startTick + " (" + s.character + "). Остановить — Backspace или /cutscene stop."));
		return null;
	}

	private static void tick(MinecraftServer server) {
		for (UUID uuid : List.copyOf(SESSIONS.keySet())) {
			Session s = SESSIONS.get(uuid);
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			if (player == null || !player.isAlive() || !(CharacterEntity.body(player) instanceof CharacterEntity body) || !EntityType.getKey(body.getType()).equals(s.character)) {
				abort(uuid, server);
				continue;
			}
			if (s.countdown > 0) {
				if (s.countdown % 20 == 0) player.sendOverlayMessage(Component.literal(String.valueOf(s.countdown / 20)));
				if (--s.countdown == 0) go(player, body, s);
				continue;
			}
			Vec3 anchor = s.scene.anchor().orElse(Vec3.ZERO);
			Vec3 p = body.position().subtract(anchor);
			int flags = 0;
			if (player.isShiftKeyDown() || body.isCrouching()) flags |= Frame.CROUCH;
			if (player.swinging && player.swingTime == 0) flags |= Frame.SWING;
			s.frames.add(new Frame((float) p.x, (float) p.y, (float) p.z, body.getYRot(), player.getYRot(), player.getXRot(), flags));
			if (s.startTick + s.frames.size() >= Cutscene.MAX_LENGTH) finish(uuid, server, true);
		}
	}

	/** Отсчёт кончился: мир к началу, игрок на месте, остальные актёры пошли. */
	private static void go(ServerPlayer player, CharacterEntity body, Session s) {
		restore(player.level().getServer(), s);
		Singletons.move(body, (ServerLevel) body.level(), s.startPos, s.startYaw);
		player.sendOverlayMessage(Component.literal("Запись!"));
		for (CutsceneBlob b : CutsceneBlob.split(s.id, CutsceneBlob.RECORD, s.scene.anchor().orElse(Vec3.ZERO), s.startTick, s.character.toString(), s.scene)) ServerPlayNetworking.send(player, b);
	}

	private static void restore(MinecraftServer server, Session s) {
		ServerLevel level = server.getLevel(s.dimension);
		if (level == null) return;
		for (Map.Entry<Identifier, CompoundTag> e : s.snapshots.entrySet()) {
			Mechanics.Snapshot snapshot = Mechanics.snapshots().get(e.getKey());
			if (snapshot == null) continue;
			try {
				snapshot.restore(level, e.getValue());
			} catch (RuntimeException ex) {
				BehaviorMod.LOGGER.warn("Снимок {} не вернулся", e.getKey(), ex);
			}
		}
	}

	/** Дубль не удался (игрок вышел, умер, сменил персонажа): ничего не сохраняется, мир возвращается к началу. */
	static void abort(UUID player, MinecraftServer server) {
		Session s = SESSIONS.remove(player);
		if (s == null) return;
		restore(server, s);
		ServerPlayer p = server.getPlayerList().getPlayer(player);
		if (p == null) return;
		ServerPlayNetworking.send(p, new CutsceneEnd());
		p.sendSystemMessage(Component.literal("Запись прервана, дубль не сохранён."));
	}

	static void abortAll(MinecraftServer server) {
		for (UUID id : List.copyOf(SESSIONS.keySet())) abort(id, server);
	}

	/**
	 * Закончить дубль и сохранить клип. @return {@code false} — записи не шло
	 */
	static boolean finish(UUID player, MinecraftServer server, boolean save) {
		Session s = SESSIONS.remove(player);
		if (s == null) return false;
		ServerPlayer p = server.getPlayerList().getPlayer(player);
		restore(server, s);
		if (p != null) {
			ServerPlayNetworking.send(p, new CutsceneEnd());
			if (CharacterEntity.body(p) instanceof CharacterEntity body) Singletons.move(body, (ServerLevel) body.level(), s.startPos, s.startYaw);
		}
		if (!save || s.frames.isEmpty()) {
			if (p != null) p.sendSystemMessage(Component.literal(s.countdown > 0 ? "Запись отменена." : "Пустой дубль не сохранён."));
			return true;
		}
		Clip clip = new Clip(s.startTick, List.copyOf(s.frames), false, "дубль");
		Cutscene scene = s.scene;
		List<ActorTrack> tracks = new ArrayList<>(scene.actors());
		int index = -1;
		for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).character().equals(s.character)) index = i;
		ActorTrack track = index >= 0 ? tracks.get(index) : new ActorTrack(s.character, List.of());
		List<Clip> clips = new ArrayList<>();
		for (Clip old : track.clips()) {
			boolean overlap = old.start() < clip.end() && clip.start() < old.end();
			clips.add(overlap ? old.withMuted(true) : old);
		}
		clips.add(clip);
		track = track.withClips(clips);
		if (index >= 0) tracks.set(index, track);
		else tracks.add(track);
		scene = scene.withActors(tracks);
		if (clip.end() > scene.length()) scene = scene.withLength(Math.min(Cutscene.MAX_LENGTH, clip.end()));
		boolean saved = Cutscenes.save(server, s.id, scene);
		if (p != null) {
			p.sendSystemMessage(Component.literal(saved ? "Дубль сохранён: " + clip.frames().size() + " тиков, сцена " + s.id + "." : "Дубль не сохранился (см. журнал)."));
			if (s.edit) Cutscenes.openEditor(p, s.id, s.startTick);
		}
		return true;
	}
}
