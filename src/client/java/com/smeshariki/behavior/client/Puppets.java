package com.smeshariki.behavior.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.smeshariki.behavior.cutscene.ActorTrack;
import com.smeshariki.behavior.cutscene.Cutscene;
import com.smeshariki.behavior.cutscene.Frame;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;

/**
 * Куклы кат-сцены: актёры сцены как настоящие клиентские сущности (добавлены в клиентский мир, никому на сервере не известны), поэтому рисуются обычным
 * путём — со всеми шейдерами. Их положение и позу каждый кадр задаёт запись ({@link #frame}); собственные тики кукол отключены (миксин {@code ClientLevelMixin}),
 * иначе они стояли бы по своим законам. Настоящий персонаж того же типа на время сцены не рисуется ({@link #hidesReal}), кроме записываемого.
 */
final class Puppets {
	private static final class Puppet {
		final ActorTrack track;
		final Entity entity;
		Vec3 lastTickPos;
		boolean swingFlag;

		Puppet(ActorTrack track, Entity entity) {
			this.track = track;
			this.entity = entity;
		}
	}

	private static final List<Puppet> LIST = new ArrayList<>();
	private static List<Identifier> hidden = List.of();

	private Puppets() {
	}

	static boolean is(Entity entity) {
		for (Puppet p : LIST) if (p.entity == entity) return true;
		return false;
	}

	/** Не рисовать ли настоящего персонажа: у него есть кукла в этой сцене. */
	static boolean hidesReal(Entity entity) {
		if (hidden.isEmpty() || is(entity)) return false;
		return hidden.contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
	}

	/** Поставить кукол по дорожкам сцены; {@code skip} — персонаж, которого играет сам игрок (его куклы нет, а настоящий виден). */
	static void show(Cutscene scene, @Nullable Identifier skip) {
		clear();
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) return;
		List<Identifier> hide = new ArrayList<>();
		for (ActorTrack track : scene.actors()) {
			if (track.character().equals(skip)) continue;
			EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(track.character());
			if (type == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(track.character())) continue;
			Entity e = type.create(level, EntitySpawnReason.LOAD);
			if (e == null) continue;
			e.noPhysics = true;
			e.setNoGravity(true);
			e.setSilent(true);
			e.setInvulnerable(true);
			e.setInvisible(true);
			level.addEntity(e);
			LIST.add(new Puppet(track, e));
			hide.add(track.character());
		}
		hidden = List.copyOf(hide);
	}

	/** Заменить дорожки на правленные (редактор): кукол пересоздаёт, только если изменился набор персонажей. */
	static void retrack(Cutscene scene, @Nullable Identifier skip) {
		List<Identifier> want = new ArrayList<>();
		for (ActorTrack t : scene.actors()) if (!t.character().equals(skip)) want.add(t.character());
		List<Identifier> have = new ArrayList<>();
		for (Puppet p : LIST) have.add(p.track.character());
		if (want.equals(have)) {
			for (int i = 0; i < LIST.size(); i++) {
				ActorTrack track = null;
				for (ActorTrack t : scene.actors()) if (t.character().equals(have.get(i))) track = t;
				if (track != null) {
					Puppet old = LIST.get(i);
					Puppet fresh = new Puppet(track, old.entity);
					fresh.lastTickPos = old.lastTickPos;
					fresh.swingFlag = old.swingFlag;
					LIST.set(i, fresh);
				}
			}
			return;
		}
		show(scene, skip);
	}

	static void clear() {
		Minecraft mc = Minecraft.getInstance();
		for (Puppet p : LIST) {
			if (mc.level != null) mc.level.removeEntity(p.entity.getId(), Entity.RemovalReason.DISCARDED);
		}
		LIST.clear();
		hidden = List.of();
	}

	/** Каждый кадр: куклы встают в положение записи в момент {@code time}. */
	static void frame(Vec3 origin, float time) {
		for (Puppet p : LIST) {
			Frame f = p.track.pose(time);
			Entity e = p.entity;
			if (f == null) {
				e.setInvisible(true);
				continue;
			}
			e.setInvisible(false);
			Vec3 pos = origin.add(f.pos());
			e.setPos(pos);
			e.xo = e.xOld = pos.x;
			e.yo = e.yOld = pos.y;
			e.zo = e.zOld = pos.z;
			e.setYRot(f.yaw());
			e.yRotO = f.yaw();
			e.setXRot(f.pitch());
			e.xRotO = f.pitch();
			if (e instanceof LivingEntity l) {
				l.yBodyRot = l.yBodyRotO = f.yaw();
				l.yHeadRot = l.yHeadRotO = f.headYaw();
			}
			e.setPose(f.has(Frame.CROUCH) ? Pose.CROUCHING : Pose.STANDING);
		}
	}

	/** Каждый клиентский тик: ход ног по пройденному, взмах руки, счётчик возраста. */
	static void tick(Vec3 origin, int time) {
		for (Puppet p : LIST) {
			Entity e = p.entity;
			e.tickCount++;
			Frame f = p.track.pose(time);
			if (f == null) {
				p.lastTickPos = null;
				continue;
			}
			Vec3 pos = origin.add(f.pos());
			if (e instanceof LivingEntity l) {
				double moved = p.lastTickPos == null ? 0 : Math.hypot(pos.x - p.lastTickPos.x, pos.z - p.lastTickPos.z);
				l.walkAnimation.update((float) Math.min(moved * 4, 1), 0.4f, 1f);
				boolean swing = f.has(Frame.SWING);
				if (swing && !p.swingFlag) l.swing(InteractionHand.MAIN_HAND);
				p.swingFlag = swing;
				// своё время взмаха: тик куклы отключён, а без него рука застыла бы
				l.oAttackAnim = l.attackAnim;
				if (l.swinging) {
					l.swingTime++;
					if (l.swingTime >= 6) {
						l.swingTime = 0;
						l.swinging = false;
					}
				} else {
					l.swingTime = 0;
				}
				l.attackAnim = l.swinging ? l.swingTime / 6f : 0f;
			}
			p.lastTickPos = pos;
		}
	}
}
