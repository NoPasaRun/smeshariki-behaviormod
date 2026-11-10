package com.smeshariki.behavior.cutscene;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Свет сцены: на тике {@code tick} зритель видит мир с затемнением {@code darkness} (0 — как есть, 1 — самый тёмный); между кадрами плавно. */
public record LightKey(int tick, float darkness) {
	public static final Codec<LightKey> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.intRange(0, Integer.MAX_VALUE).fieldOf("tick").forGetter(LightKey::tick),
			Codec.floatRange(0f, 1f).fieldOf("darkness").forGetter(LightKey::darkness)
	).apply(i, LightKey::new));
}
