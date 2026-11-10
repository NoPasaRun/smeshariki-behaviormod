package com.smeshariki.behavior.companion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import org.jetbrains.annotations.Nullable;

import com.smeshariki.behavior.BehaviorMod;
import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.gameplay.raft.BalloonEntity;
import com.smeshariki.gameplay.raft.BalloonRopeEntity;
import com.smeshariki.gameplay.raft.BalloonRopeItem;
import com.smeshariki.gameplay.raft.Balloons;
import com.smeshariki.gameplay.raft.RaftEntity;
import com.smeshariki.gameplay.rope.RopeEntity;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Напарник — смешарик без игрока, который идёт за своим ведущим (игроком) и слушается приказов: сам смешарик (в мире он один) с вложением
 * {@link CompanionData}, им управляет скрипт ({@code setScripted}: цели прогулки и паники сняты), урона нет. Отпущенный ({@link #release})
 * остаётся, где стоит, и снова гуляет.
 * <p>
 * Каждый тик: идёт к телу ведущего навигацией; отстал дальше {@link #TELEPORT_DISTANCE}, застрял на {@link #STUCK_TICKS}, попал в лаву или
 * пустоту — переносится к ведущему, на место, где тот недавно стоял на земле (след). Ведущий стоит на плоту или шаре — напарник переносится
 * к нему на палубу и стоит, плот его везёт. Держит стропу или ждёт по приказу — стоит.
 */
public final class Companions {
	public static final AttachmentType<CompanionData> DATA = AttachmentRegistry.create(BehaviorMod.id("companion"), b -> b.persistent(CompanionData.CODEC));

	/** Дальше этого от ведущего — перенос. */
	public static final double TELEPORT_DISTANCE = 24;
	/** Ближе этого стоит. */
	private static final double NEAR = 3;
	/** Сколько тиков без продвижения к ведущему считается «застрял». */
	private static final int STUCK_TICKS = 60;
	/** Сколько тиков ведущий должен простоять на плоту, чтобы напарник перенёсся к нему (не прыгать за каждым касанием). */
	private static final int RAFT_TICKS = 10;
	/** Сколько тиков напарник может простоять внутри ведущего, прежде чем отойдёт. */
	private static final int INSIDE_TICKS = 10;
	/** Длина следа ведущего и шаг между точками. */
	private static final int TRAIL_SIZE = 40;
	private static final double TRAIL_STEP = 1;

	private static final Set<CharacterEntity> LOADED = new HashSet<>();
	private static final Map<UUID, Deque<Vec3>> TRAILS = new HashMap<>();
	private static final Map<CharacterEntity, Memory> MEMORY = new WeakHashMap<>();

	/** Только в памяти: секунды, которые не нужно переживать перезапуск. */
	private static final class Memory {
		Vec3 lastPos = Vec3.ZERO;
		int stuck;
		int leaderOnRaft;
		int operateTicks;
		int inside;
	}

	private Companions() {
	}

	public static void init() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof CharacterEntity c && c.hasAttached(DATA)) adopt(c);
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof CharacterEntity c) LOADED.remove(c);
		});
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !isCompanion(entity));
		ServerTickEvents.END_SERVER_TICK.register(Companions::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			LOADED.clear();
			TRAILS.clear();
			MEMORY.clear();
		});
	}

	/**
	 * Взять смешарика в напарники игроку {@code leader}: им управляет скрипт (своих целей нет, занят для выбора и яйца), он идёт за игроком и
	 * переносится к нему. Смешарик в мире один (GameplayMod {@code Singletons}), поэтому напарником становится он сам, а не копия.
	 */
	public static void recruit(CharacterEntity c, ServerPlayer leader) {
		c.setAttached(DATA, new CompanionData(leader.getUUID(), false, CompanionData.NONE));
		adopt(c);
		bringToLeader(c, leader);
	}

	/** Отпустить: смешарик остаётся, где стоит, и снова гуляет сам. */
	public static void release(CharacterEntity c) {
		RopeEntity rope = RopeEntity.heldBy(c);
		if (rope != null) rope.release();
		c.removeAttached(DATA);
		LOADED.remove(c);
		c.setInvulnerable(false);
		c.setScripted(false);
		c.getNavigation().stop();
	}

	private static void adopt(CharacterEntity c) {
		c.setScripted(true);
		c.setInvulnerable(true);
		c.setNoAi(false);
		LOADED.add(c);
	}

	public static boolean isCompanion(Entity entity) {
		return entity instanceof CharacterEntity c && c.hasAttached(DATA);
	}

	public static @Nullable UUID leader(Entity entity) {
		CompanionData data = entity.getAttached(DATA);
		return data == null ? null : data.leader();
	}

	/** Загруженные напарники игрока. */
	public static List<CharacterEntity> of(ServerPlayer leader) {
		List<CharacterEntity> out = new ArrayList<>();
		for (CharacterEntity c : LOADED) {
			CompanionData data = c.getAttached(DATA);
			if (!c.isRemoved() && data != null && data.leader().equals(leader.getUUID())) out.add(c);
		}
		return out;
	}

	public static void setLeader(CharacterEntity c, ServerPlayer leader) {
		CompanionData data = c.getAttached(DATA);
		c.setAttached(DATA, data == null ? new CompanionData(leader.getUUID(), false, CompanionData.NONE) : new CompanionData(leader.getUUID(), data.waiting(), data.ropeAction()));
	}

	/** Стоять на месте (true) или идти за ведущим. */
	public static void setWaiting(CharacterEntity c, boolean waiting) {
		CompanionData data = c.getAttached(DATA);
		if (data == null) return;
		c.setAttached(DATA, data.withWaiting(waiting));
		c.getNavigation().stop();
	}

	/** Щёлкать стропой без остановки ({@link BalloonRopeAction}) или перестать ({@link CompanionData#NONE}). */
	public static void setRopeAction(CharacterEntity c, int action) {
		CompanionData data = c.getAttached(DATA);
		if (data != null && data.ropeAction() != action) c.setAttached(DATA, data.withRopeAction(action));
	}

	public static int ropeAction(CharacterEntity c) {
		CompanionData data = c.getAttached(DATA);
		return data == null ? CompanionData.NONE : data.ropeAction();
	}

	public static boolean waiting(CharacterEntity c) {
		CompanionData data = c.getAttached(DATA);
		return data != null && data.waiting();
	}

	/** Перенести к ведущему сейчас: на его плот, на место недавнего следа на земле или к нему самому. */
	public static void bringToLeader(CharacterEntity c, ServerPlayer leader) {
		Entity body = CharacterEntity.body(leader);
		RopeEntity rope = RopeEntity.heldBy(c);
		if (rope != null) rope.release();
		setRopeAction(c, CompanionData.NONE);
		RaftEntity raft = RaftEntity.under(body);
		Vec3 target = raft != null ? beside(c, body, false) : trailSpot(c, leader, body);
		if (c.level() != body.level() && body.level() instanceof ServerLevel to) {
			c.teleport(new net.minecraft.world.level.portal.TeleportTransition(to, target, Vec3.ZERO, body.getYRot(), 0f, net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
		} else {
			c.teleportTo(target.x, target.y, target.z);
		}
		c.setDeltaMovement(Vec3.ZERO);
		c.resetFallDistance();
		c.getNavigation().stop();
		Memory m = MEMORY.get(c);
		if (m != null) {
			m.stuck = 0;
			m.lastPos = target;
		}
	}

	/** Насколько далеко от ведущего встаёт перенесённый напарник: не в него (два тела в одной точке сбивают сервер с толку, игрока дёргает). */
	private static final double BESIDE_NEAR = 1.4, BESIDE_FAR = 2.4;

	/**
	 * Свободное место рядом с телом ведущего на его высоте: сначала за спиной, потом по кругу. Подходит, если коробка напарника там ни во что не
	 * упирается, не задевает ведущего и под ногами опора ({@code needFloor}; на палубе плота её ищет сам плот, поэтому без проверки). Не нашлось —
	 * место ведущего.
	 */
	private static Vec3 beside(CharacterEntity c, Entity body, boolean needFloor) {
		Vec3 at = body.position();
		double back = Math.toRadians(body.getYRot() + 180);
		for (double r : new double[] {BESIDE_NEAR, BESIDE_FAR}) {
			for (int i = 0; i < 8; i++) {
				// 0, +45°, −45°, +90°, ... от направления за спиной
				double turn = Math.toRadians(45 * ((i + 1) / 2) * (i % 2 == 0 ? 1 : -1));
				double a = back + turn;
				Vec3 p = at.add(-Math.sin(a) * r, 0.05, Math.cos(a) * r);
				net.minecraft.world.phys.AABB box = c.getDimensions(c.getPose()).makeBoundingBox(p);
				if (box.intersects(body.getBoundingBox()) || !body.level().noCollision(c, box)) continue;
				if (needFloor && body.level().noCollision(c, box.move(0, -1.0, 0))) continue;
				return p;
			}
		}
		return at.add(0, 0.05, 0);
	}

	/** Последняя точка следа в 2–12 блоках от ведущего (там он недавно стоял на земле); нет такой — рядом с ведущим. */
	private static Vec3 trailSpot(CharacterEntity c, ServerPlayer leader, Entity body) {
		Deque<Vec3> trail = TRAILS.get(leader.getUUID());
		if (trail != null) {
			for (var it = trail.descendingIterator(); it.hasNext(); ) {
				Vec3 p = it.next();
				if (p.distanceTo(body.position()) >= 2 && p.distanceTo(body.position()) <= TELEPORT_DISTANCE / 2) return p;
			}
		}
		return beside(c, body, true);
	}

	private static void tick(MinecraftServer server) {
		Set<UUID> leaders = new HashSet<>();
		for (CharacterEntity c : List.copyOf(LOADED)) {
			if (c.isRemoved()) {
				LOADED.remove(c);
				continue;
			}
			CompanionData data = c.getAttached(DATA);
			if (data == null) {
				LOADED.remove(c);
				continue;
			}
			leaders.add(data.leader());
			if (com.smeshariki.behavior.cutscene.Cutscenes.busy(data.leader())) {
				// ведущий смотрит сцену или пишет дубль: напарник стоит и не мешает
				c.getNavigation().stop();
				continue;
			}
			control(server, c, data);
		}
		for (UUID id : leaders) {
			ServerPlayer leader = server.getPlayerList().getPlayer(id);
			if (leader != null) record(leader);
		}
		TRAILS.keySet().retainAll(leaders);
	}

	private static void record(ServerPlayer leader) {
		Entity body = CharacterEntity.body(leader);
		if (!body.onGround() || body.isInLava() || body.isInWater() || RaftEntity.under(body) != null) return;
		Deque<Vec3> trail = TRAILS.computeIfAbsent(leader.getUUID(), k -> new ArrayDeque<>());
		Vec3 last = trail.peekLast();
		if (last != null && last.distanceTo(body.position()) < TRAIL_STEP) return;
		trail.addLast(body.position());
		while (trail.size() > TRAIL_SIZE) trail.removeFirst();
	}

	/** Как часто напарник щёлкает стропой без остановки, тиков: высотой — как быстро крутят колесо, рулём — реже, толчок долгий. */
	private static final int LIFT_EVERY = 5, HELM_EVERY = 10;
	/** Как часто показывать ведущему высоту и топливо, пока напарник работает стропой высоты. */
	private static final int REPORT_EVERY = 20;

	/** «Выше», «левее» и т. п.: щелчок раз в несколько тиков, пока не скажут «стой» или не кончится топливо. */
	private static void keepOperating(CharacterEntity c, ServerPlayer leader, RopeEntity held, int action, Memory m) {
		if (!(held instanceof BalloonRopeEntity rope)) {
			setRopeAction(c, CompanionData.NONE);
			return;
		}
		boolean lift = rope.kind() == BalloonRopeItem.Kind.LIFT;
		m.operateTicks++;
		if (m.operateTicks % (lift ? LIFT_EVERY : HELM_EVERY) != 0) return;
		Balloons.Result result = Balloons.operate(rope, action, false);
		switch (result) {
			case WRONG -> setRopeAction(c, CompanionData.NONE);
			case NO_FUEL -> {
				setRopeAction(c, CompanionData.NONE);
				leader.sendOverlayMessage(Component.translatable("message.behaviormod.order.out_of_fuel", c.getName()));
			}
			default -> {
				BalloonEntity balloon = rope.balloon();
				if (lift && balloon != null && m.operateTicks % REPORT_EVERY == 0)
					leader.sendOverlayMessage(Component.translatable("message.behaviormod.order.lift", c.getName(), Math.round(balloon.targetY()), rope.fuel()));
			}
		}
	}

	private static void control(MinecraftServer server, CharacterEntity c, CompanionData data) {
		if (c.isVehicle()) return;
		c.clearFire();
		Memory m = MEMORY.computeIfAbsent(c, k -> new Memory());
		ServerPlayer leader = server.getPlayerList().getPlayer(data.leader());
		if (leader == null || !leader.isAlive() || leader.isSpectator()) {
			c.getNavigation().stop();
			return;
		}
		Entity body = CharacterEntity.body(leader);
		if (c.level() != body.level() || c.isInLava() || c.getY() < c.level().getMinY() + 2) {
			bringToLeader(c, leader);
			return;
		}
		c.getLookControl().setLookAt(body, 10f, c.getMaxHeadXRot());
		RopeEntity held = RopeEntity.heldBy(c);
		if (held != null) {
			c.getNavigation().stop();
			if (data.ropeAction() != CompanionData.NONE) keepOperating(c, leader, held, data.ropeAction(), m);
			return;
		}
		if (data.ropeAction() != CompanionData.NONE) setRopeAction(c, CompanionData.NONE);
		RaftEntity raft = RaftEntity.under(body);
		m.leaderOnRaft = raft != null ? m.leaderOnRaft + 1 : 0;
		if (raft != null && !data.waiting()) {
			if (RaftEntity.under(c) == raft) {
				c.getNavigation().stop();
				return;
			}
			if (m.leaderOnRaft >= RAFT_TICKS) {
				bringToLeader(c, leader);
				return;
			}
		}
		if (data.waiting()) {
			c.getNavigation().stop();
			return;
		}
		double d = c.distanceTo(body);
		if (d > TELEPORT_DISTANCE || m.stuck >= STUCK_TICKS) {
			bringToLeader(c, leader);
			return;
		}
		if (d <= NEAR) {
			c.getNavigation().stop();
			m.stuck = 0;
			// стоит внутри ведущего (тот пришёл в него или их свело переносом): отойти в сторону
			m.inside = c.getBoundingBox().intersects(body.getBoundingBox()) ? m.inside + 1 : 0;
			if (m.inside >= INSIDE_TICKS) {
				Vec3 p = beside(c, body, true);
				c.teleportTo(p.x, p.y, p.z);
				c.setDeltaMovement(Vec3.ZERO);
				m.inside = 0;
			}
			return;
		}
		if (c.tickCount % 10 == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(body, d > 8 ? 1.5 : 1.1);
		if (c.tickCount % 20 == 0) {
			m.stuck = c.position().distanceTo(m.lastPos) < 0.5 ? m.stuck + 20 : 0;
			m.lastPos = c.position();
		}
	}
}
