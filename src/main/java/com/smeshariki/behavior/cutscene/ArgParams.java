package com.smeshariki.behavior.cutscene;

import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.smeshariki.gameplay.api.Mechanics;

/** Параметры действия дорожки действий сцены: строки из файла; область не поддерживается (сцена привязана к точке, а не к области уровня). */
record ArgParams(Map<String, String> args) implements Mechanics.Params {
	@Override
	public String string(String name, String fallback) {
		return args.getOrDefault(name, fallback);
	}

	@Override
	public boolean bool(String name, boolean fallback) {
		String v = args.get(name);
		if (v == null) return fallback;
		return v.equalsIgnoreCase("true") || v.equals("1");
	}

	@Override
	public double number(String name, double fallback) {
		String v = args.get(name);
		if (v == null) return fallback;
		try {
			return Double.parseDouble(v);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	@Override
	public @Nullable Mechanics.Area area(String name) {
		return null;
	}
}
