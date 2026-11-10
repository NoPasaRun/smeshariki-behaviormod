package com.smeshariki.behavior.order;

import java.util.Comparator;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.smeshariki.behavior.BehaviorMod;
import com.smeshariki.behavior.companion.CompanionData;
import com.smeshariki.behavior.companion.Companions;
import com.smeshariki.gameplay.api.Mechanics;
import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.gameplay.raft.BalloonEntity;
import com.smeshariki.gameplay.raft.BalloonRopeAction;
import com.smeshariki.gameplay.raft.BalloonRopeEntity;
import com.smeshariki.gameplay.raft.BalloonRopeItem;
import com.smeshariki.gameplay.raft.Balloons;
import com.smeshariki.gameplay.rope.RopeEntity;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Приказы этого мода:
 * <ul>
 * <li>{@code behaviormod:follow} — идти за ведущим (отпускает стропу);</li>
 * <li>{@code behaviormod:wait} — стоять на месте;</li>
 * <li>{@code behaviormod:take_rope} ({@code kind}: lift / helm, {@code group} — группа шара, необязательно) — взять ближайшую стропу шара,
 * если дотягивается (у игрока её не отнимает);</li>
 * <li>{@code behaviormod:release_rope} — отпустить стропу;</li>
 * <li>{@code behaviormod:balloon_lift} ({@code steps}, по умолчанию 1, меньше нуля — вниз) — щелчки стропой высоты, как у игрока, с тратой топлива;
 * с {@code keep: true} — без остановки в сторону знака {@code steps}, пока не скажут «стой»;</li>
 * <li>{@code behaviormod:balloon_helm} — толчки рулём: {@code dir} (forward / back / left / right — от взгляда ведущего, к ближайшей оси шара),
 * или {@code look: true} (= forward), или {@code forward}, {@code left} от носа шара; {@code steps}; с {@code keep: true} — без остановки;</li>
 * <li>{@code behaviormod:stop} — перестать работать стропой (стропу не отпускает).</li>
 * </ul>
 */
public final class BuiltinOrders {
	/** Сколько щелчков за приказ можно попросить. */
	private static final int MAX_STEPS = 8;

	private BuiltinOrders() {
	}

	public static void init() {
		Orders.register(BehaviorMod.id("stop"), (c, leader, params) -> {
			Companions.setRopeAction(c, CompanionData.NONE);
			return null;
		});
		Orders.register(BehaviorMod.id("follow"), (c, leader, params) -> {
			Companions.setRopeAction(c, CompanionData.NONE);
			RopeEntity rope = RopeEntity.heldBy(c);
			if (rope != null) rope.release();
			Companions.setWaiting(c, false);
			return null;
		});
		Orders.register(BehaviorMod.id("wait"), (c, leader, params) -> {
			Companions.setWaiting(c, true);
			return null;
		});
		Orders.register(BehaviorMod.id("release_rope"), (c, leader, params) -> {
			RopeEntity rope = RopeEntity.heldBy(c);
			if (rope == null) return Component.translatable("message.behaviormod.order.no_rope_held", c.getName());
			Companions.setRopeAction(c, CompanionData.NONE);
			rope.release();
			return null;
		});
		Orders.register(BehaviorMod.id("take_rope"), new Orders.Order() {
			@Override
			public @Nullable Component run(CharacterEntity c, ServerPlayer leader, Mechanics.Params params) {
				BalloonRopeItem.Kind kind = kind(params);
				String group = params.string("group", "");
				if (RopeEntity.heldBy(c) instanceof BalloonRopeEntity held && held.kind() == kind) return null;
				List<BalloonRopeEntity> ropes = c.level().getEntitiesOfClass(BalloonRopeEntity.class, c.getBoundingBox().inflate(RopeEntity.LONGEST + 8), r -> r.kind() == kind
						&& r.balloon() != null && (group.isEmpty() || group.equals(r.balloon().group()))
						&& !(r.mode() == RopeEntity.HELD && c.level().getEntity(r.holderId()) instanceof Player));
				Vec3 hand = RopeEntity.holdPoint(c, 1f);
				BalloonRopeEntity rope = ropes.stream().min(Comparator.comparingDouble(r -> r.anchor().distanceTo(hand))).orElse(null);
				if (rope == null) return Component.translatable("message.behaviormod.order.no_rope", c.getName());
				if (rope.anchor().distanceTo(hand) > rope.length()) return Component.translatable("message.behaviormod.order.rope_far", c.getName());
				RopeEntity old = RopeEntity.heldBy(c);
				if (old != null) old.release();
				Companions.setRopeAction(c, CompanionData.NONE);
				rope.grabBy(c);
				return null;
			}

			@Override
			public List<String> problems(Mechanics.Params params) {
				String kind = params.string("kind", "");
				return kind.equals("lift") || kind.equals("helm") ? List.of() : List.of("у take_rope kind должен быть lift или helm");
			}
		});
		Orders.register(BehaviorMod.id("balloon_lift"), new Orders.Order() {
			@Override
			public @Nullable Component run(CharacterEntity c, ServerPlayer leader, Mechanics.Params params) {
				if (!(RopeEntity.heldBy(c) instanceof BalloonRopeEntity rope) || rope.kind() != BalloonRopeItem.Kind.LIFT)
					return Component.translatable("message.behaviormod.order.no_lift", c.getName());
				int steps = steps(params);
				int action = steps > 0 ? BalloonRopeAction.UP : BalloonRopeAction.DOWN;
				if (params.bool("keep", false)) {
					if (rope.fuel() <= 0) return Component.translatable("message.behaviormod.order.no_fuel", c.getName());
					Companions.setRopeAction(c, action);
					return null;
				}
				Balloons.Result last = Balloons.Result.WRONG;
				for (int i = 0; i < Math.abs(steps); i++) {
					last = Balloons.operate(rope, action, false);
					if (last != Balloons.Result.CLIMBED) break;
				}
				if (last == Balloons.Result.NO_FUEL) return Component.translatable("message.behaviormod.order.no_fuel", c.getName());
				BalloonEntity balloon = rope.balloon();
				if (balloon != null) leader.sendOverlayMessage(Component.translatable("message.behaviormod.order.lift", c.getName(), Math.round(balloon.targetY()), rope.fuel()));
				return null;
			}

			@Override
			public List<String> problems(Mechanics.Params params) {
				return steps(params) == 0 ? List.of("у balloon_lift steps не должен быть 0") : List.of();
			}
		});
		Orders.register(BehaviorMod.id("balloon_helm"), new Orders.Order() {
			@Override
			public @Nullable Component run(CharacterEntity c, ServerPlayer leader, Mechanics.Params params) {
				if (!(RopeEntity.heldBy(c) instanceof BalloonRopeEntity rope) || rope.kind() != BalloonRopeItem.Kind.HELM || rope.balloon() == null)
					return Component.translatable("message.behaviormod.order.no_helm", c.getName());
				BalloonEntity balloon = rope.balloon();
				int forward = (int) Math.signum(params.number("forward", 0)), left = (int) Math.signum(params.number("left", 0));
				Vec3 wanted = viewDirection(leader, params.bool("look", false) ? "forward" : params.string("dir", ""));
				if (wanted != null) {
					double f = wanted.dot(balloon.front()), l = wanted.dot(balloon.left());
					forward = Math.abs(f) >= Math.abs(l) ? (int) Math.signum(f) : 0;
					left = Math.abs(l) > Math.abs(f) ? (int) Math.signum(l) : 0;
				}
				if (params.bool("keep", false)) {
					if (rope.fuel() <= 0) return Component.translatable("message.behaviormod.order.no_fuel", c.getName());
					// без остановки — по одной оси: та, что ближе к просьбе
					int action = forward != 0 ? (forward > 0 ? BalloonRopeAction.FORWARD : BalloonRopeAction.BACK) : (left > 0 ? BalloonRopeAction.LEFT : BalloonRopeAction.RIGHT);
					Companions.setRopeAction(c, action);
					return null;
				}
				int steps = Math.abs(steps(params));
				Balloons.Result last = Balloons.Result.WRONG;
				for (int i = 0; i < steps; i++) {
					if (forward != 0) last = Balloons.operate(rope, forward > 0 ? BalloonRopeAction.FORWARD : BalloonRopeAction.BACK, false);
					if (left != 0 && last != Balloons.Result.NO_FUEL) last = Balloons.operate(rope, left > 0 ? BalloonRopeAction.LEFT : BalloonRopeAction.RIGHT, false);
					if (last != Balloons.Result.NUDGED) break;
				}
				return switch (last) {
					case NO_FUEL -> Component.translatable("message.behaviormod.order.no_fuel", c.getName());
					case BLOCKED -> Component.translatable("message.behaviormod.order.landed", c.getName());
					default -> null;
				};
			}

			@Override
			public List<String> problems(Mechanics.Params params) {
				String dir = params.string("dir", "");
				if (!dir.isEmpty() && !List.of("forward", "back", "left", "right").contains(dir)) return List.of("у balloon_helm dir должен быть forward, back, left или right");
				boolean any = !dir.isEmpty() || params.bool("look", false) || params.number("forward", 0) != 0 || params.number("left", 0) != 0;
				return any ? List.of() : List.of("у balloon_helm нужен dir, look: true или forward / left");
			}
		});
	}

	/** Направление от взгляда ведущего по горизонтали: вперёд, назад, влево, вправо; {@code null} — не задано. */
	private static @Nullable Vec3 viewDirection(ServerPlayer leader, String dir) {
		Vec3 look = leader.getLookAngle();
		Vec3 front = new Vec3(look.x, 0, look.z);
		if (front.lengthSqr() < 1.0e-6) front = Vec3.directionFromRotation(0, leader.getYRot());
		front = front.normalize();
		// влево от взгляда: поворот на 90° (в Minecraft z на юг, x на восток)
		Vec3 left = new Vec3(front.z, 0, -front.x);
		return switch (dir) {
			case "forward" -> front;
			case "back" -> front.scale(-1);
			case "left" -> left;
			case "right" -> left.scale(-1);
			default -> null;
		};
	}

	private static BalloonRopeItem.Kind kind(Mechanics.Params params) {
		return params.string("kind", "lift").equals("helm") ? BalloonRopeItem.Kind.HELM : BalloonRopeItem.Kind.LIFT;
	}

	private static int steps(Mechanics.Params params) {
		return (int) Math.clamp(Math.round(params.number("steps", 1)), -MAX_STEPS, MAX_STEPS);
	}
}
