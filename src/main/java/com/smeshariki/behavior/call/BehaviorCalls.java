package com.smeshariki.behavior.call;

import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.smeshariki.behavior.BehaviorMod;
import com.smeshariki.behavior.companion.Companions;
import com.smeshariki.behavior.dialog.Dialogs;
import com.smeshariki.behavior.dialog.OpenReplies;
import com.smeshariki.behavior.order.Orders;
import com.smeshariki.gameplay.api.Calls;
import com.smeshariki.gameplay.api.Mechanics;
import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.gameplay.character.Characters;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Что Нюша (клавиша G, GameplayMod) может сделать с позванным смешариком: поговорить (он поворачивается к ней и отвечает; если у миссии есть реплики —
 * открывается их список) и приказать (быстрые приказы {@link Orders}; не напарника делают напарником Нюшиного игрока).
 */
public final class BehaviorCalls {
	/** Число приветственных реплик {@code line.behaviormod.talk.N}. */
	private static final int LINES = 4;

	/** Быстрый приказ: идентификатор варианта, приказ и его параметры. */
	private record Quick(String id, String order, Map<String, String> args) {
	}

	private static final List<Quick> QUICK = List.of(
			new Quick("follow", "follow", Map.of()),
			new Quick("wait", "wait", Map.of()),
			new Quick("stop", "stop", Map.of()),
			new Quick("take_lift", "take_rope", Map.of("kind", "lift")),
			new Quick("take_helm", "take_rope", Map.of("kind", "helm")),
			new Quick("release_rope", "release_rope", Map.of()),
			new Quick("lift_up", "balloon_lift", Map.of("steps", "1", "keep", "true")),
			new Quick("lift_down", "balloon_lift", Map.of("steps", "-1", "keep", "true")),
			new Quick("helm_forward", "balloon_helm", Map.of("dir", "forward", "keep", "true")),
			new Quick("helm_back", "balloon_helm", Map.of("dir", "back", "keep", "true")),
			new Quick("helm_left", "balloon_helm", Map.of("dir", "left", "keep", "true")),
			new Quick("helm_right", "balloon_helm", Map.of("dir", "right", "keep", "true")),
			new Quick("dismiss", "", Map.of()));

	private BehaviorCalls() {
	}

	public static void init() {
		Calls.register("behaviormod:talk", new Calls.Action() {
			@Override
			public Component label() {
				return Component.translatable("gui.behaviormod.call.talk");
			}

			@Override
			public @Nullable Component run(ServerPlayer caller, CharacterEntity target, String option) {
				return talk(caller, target);
			}
		});
		Calls.register("behaviormod:order", new Calls.Action() {
			@Override
			public Component label() {
				return Component.translatable("gui.behaviormod.call.order");
			}

			@Override
			public List<Calls.Option> options() {
				return QUICK.stream().map(q -> new Calls.Option(q.id(), Component.translatable("call.behaviormod.order." + q.id()))).toList();
			}

			@Override
			public @Nullable Component run(ServerPlayer caller, CharacterEntity target, String option) {
				return order(caller, target, option);
			}
		});
	}

	private static @Nullable Component talk(ServerPlayer caller, CharacterEntity target) {
		var body = CharacterEntity.body(caller);
		target.getLookControl().setLookAt(body.getX(), body.getEyeY(), body.getZ());
		Component line = Component.translatable("line.behaviormod.talk." + (1 + target.getRandom().nextInt(LINES)));
		caller.sendSystemMessage(Component.empty().append(Characters.alias(target.getType())).append(": ").append(line));
		if (!Dialogs.replies(caller).isEmpty()) ServerPlayNetworking.send(caller, new OpenReplies());
		return null;
	}

	private static @Nullable Component order(ServerPlayer caller, CharacterEntity target, String option) {
		Quick quick = QUICK.stream().filter(q -> q.id().equals(option)).findFirst().orElse(null);
		if (quick == null) return null;
		var leader = Companions.leader(target);
		if (Companions.isCompanion(target) && leader != null && !leader.equals(caller.getUUID())) {
			ServerPlayer other = caller.level().getServer().getPlayerList().getPlayer(leader);
			if (other != null) return Component.translatable("message.behaviormod.call.not_yours", Characters.alias(target.getType()));
			Companions.setLeader(target, caller);
		}
		if (quick.order().isEmpty()) {
			// «свободен»: отпустить из напарников, дальше гуляет сам
			if (Companions.isCompanion(target)) Companions.release(target);
			return null;
		}
		// приказ получает напарник: не напарника он делает напарником (приходит к ведущему)
		if (!Companions.isCompanion(target)) Companions.recruit(target, caller);
		return Orders.run(BehaviorMod.id(quick.order()), target, caller, new MapParams(quick.args()));
	}

	/** Параметры приказа из таблицы строк. */
	private record MapParams(Map<String, String> args) implements Mechanics.Params {
		@Override
		public String string(String name, String fallback) {
			return args.getOrDefault(name, fallback);
		}

		@Override
		public boolean bool(String name, boolean fallback) {
			String v = args.get(name);
			return v == null ? fallback : v.equalsIgnoreCase("true") || v.equals("1");
		}

		@Override
		public double number(String name, double fallback) {
			String v = args.get(name);
			try {
				return v == null ? fallback : Double.parseDouble(v);
			} catch (NumberFormatException e) {
				return fallback;
			}
		}

		@Override
		public @Nullable Mechanics.Area area(String name) {
			return null;
		}
	}
}
