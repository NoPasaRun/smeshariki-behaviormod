package com.smeshariki.behavior.cutscene;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.smeshariki.behavior.BehaviorMod;

import net.minecraft.resources.Identifier;

/**
 * Реестр функций сглаживания камеры: любая кубическая кривая Безье, как {@code cubic-bezier(x1, y1, x2, y2)} в CSS. Кадр камеры хранит id функции, по которой камера
 * идёт от него к следующему кадру. Встроенные — {@link #LINEAR}, {@link #SMOOTH} и другие; свои добавляет любой мод ({@link #register}) или игрок из редактора
 * (они сохраняются в {@code config/behaviormod/easings.json}, а сцена, которая их использует, носит их определения с собой — {@link Cutscene#easings}).
 * Значения {@code y} могут выходить за 0..1 (откат назад, перелёт); {@code x} — только 0..1.
 */
public final class Easings {
	public static final Identifier LINEAR = BehaviorMod.id("linear"), SMOOTH = BehaviorMod.id("smooth");

	/** Кривая Безье с точками (0,0), (x1,y1), (x2,y2), (1,1). */
	public record Bezier(float x1, float y1, float x2, float y2) {
		public static final Codec<Bezier> CODEC = Codec.FLOAT.listOf(4, 4).xmap(l -> new Bezier(l.get(0), l.get(1), l.get(2), l.get(3)), b -> List.of(b.x1, b.y1, b.x2, b.y2));

		public Bezier {
			x1 = Math.max(0f, Math.min(1f, x1));
			x2 = Math.max(0f, Math.min(1f, x2));
		}

		private static double at(double s, double p1, double p2) {
			double u = 1 - s;
			return 3 * u * u * s * p1 + 3 * u * s * s * p2 + s * s * s;
		}

		/** Значение функции при доле пути {@code t} (0..1): сначала по x находится параметр кривой, потом y. */
		public float apply(float t) {
			if (t <= 0f) return 0f;
			if (t >= 1f) return 1f;
			double lo = 0, hi = 1, s = t;
			for (int i = 0; i < 30; i++) {
				s = (lo + hi) / 2;
				if (at(s, x1, x2) < t) lo = s;
				else hi = s;
			}
			return (float) at(s, y1, y2);
		}

		public String text() {
			return String.format(java.util.Locale.ROOT, "%.2f %.2f %.2f %.2f", x1, y1, x2, y2);
		}
	}

	private static final Map<Identifier, Bezier> REGISTRY = new LinkedHashMap<>();
	private static final List<Identifier> BUILTIN = new ArrayList<>();

	static {
		builtin("linear", 0, 0, 1, 1);
		builtin("smooth", 0.42f, 0, 0.58f, 1);
		builtin("ease", 0.25f, 0.1f, 0.25f, 1);
		builtin("ease_in", 0.42f, 0, 1, 1);
		builtin("ease_out", 0, 0, 0.58f, 1);
		builtin("slow_start", 0.7f, 0, 1, 1);
		builtin("slow_end", 0, 0, 0.3f, 1);
		builtin("back_in", 0.6f, -0.28f, 0.735f, 0.045f);
		builtin("back_out", 0.175f, 0.885f, 0.32f, 1.275f);
		builtin("back_in_out", 0.68f, -0.55f, 0.265f, 1.55f);
		builtin("step", 1, 0, 1, 0);
	}

	private Easings() {
	}

	private static void builtin(String name, float x1, float y1, float x2, float y2) {
		Identifier id = BehaviorMod.id(name);
		REGISTRY.put(id, new Bezier(x1, y1, x2, y2));
		BUILTIN.add(id);
	}

	/** Добавить функцию в реестр. Встроенные заменить нельзя. @return {@code false} — id занят встроенной */
	public static synchronized boolean register(Identifier id, Bezier bezier) {
		if (BUILTIN.contains(id)) return false;
		REGISTRY.put(id, bezier);
		return true;
	}

	public static synchronized boolean remove(Identifier id) {
		return !BUILTIN.contains(id) && REGISTRY.remove(id) != null;
	}

	public static boolean isBuiltin(Identifier id) {
		return BUILTIN.contains(id);
	}

	@Nullable
	public static synchronized Bezier get(Identifier id) {
		return REGISTRY.get(id);
	}

	public static synchronized List<Identifier> ids() {
		return List.copyOf(REGISTRY.keySet());
	}

	/** Функция по id: сначала определения самой сцены, потом реестр; неизвестная — линейная. */
	public static Bezier resolve(Identifier id, Map<Identifier, Bezier> local) {
		Bezier b = local.get(id);
		if (b == null) b = get(id);
		return b != null ? b : get(LINEAR);
	}

	/** Id из строки файла: без пространства имён — наше ({@code "smooth"} → {@code behaviormod:smooth}). */
	public static Identifier parse(String text) {
		Identifier id = Identifier.tryParse(text.contains(":") ? text : BehaviorMod.NAMESPACE + ":" + text);
		return id == null ? LINEAR : id;
	}
}
