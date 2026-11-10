package com.smeshariki.behavior.order;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.smeshariki.gameplay.api.Mechanics;
import com.smeshariki.gameplay.character.CharacterEntity;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Реестр приказов напарнику. Приказ берётся по идентификатору ({@code "behaviormod:take_rope"}), параметры — те же {@link Mechanics.Params},
 * что у механик моста (уровни дают их из своего JSON). Название для окон — {@code order.<пр>.<имя>} в языковых файлах.
 */
public final class Orders {
	/** Приказ: выполняется сразу на сервере. */
	public interface Order {
		/** {@code null} — выполнен; иначе почему нет (показывается ведущему строкой над панелью). */
		@Nullable Component run(CharacterEntity companion, ServerPlayer leader, Mechanics.Params params);

		/** Ошибки параметров (уровень с ними не загрузится). */
		default List<String> problems(Mechanics.Params params) {
			return List.of();
		}
	}

	private static final Map<Identifier, Order> ORDERS = new LinkedHashMap<>();

	private Orders() {
	}

	public static void register(Identifier id, Order order) {
		ORDERS.put(id, order);
	}

	public static @Nullable Order get(Identifier id) {
		return ORDERS.get(id);
	}

	public static Map<Identifier, Order> all() {
		return Collections.unmodifiableMap(ORDERS);
	}

	/** Выполнить приказ; неизвестный — отказ. */
	public static @Nullable Component run(Identifier id, CharacterEntity companion, ServerPlayer leader, Mechanics.Params params) {
		Order order = ORDERS.get(id);
		if (order == null) return Component.translatable("message.behaviormod.order.unknown", id.toString());
		return order.run(companion, leader, params);
	}
}
