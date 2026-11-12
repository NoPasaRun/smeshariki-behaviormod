package com.smeshariki.behavior.cutscene;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Свет сцены: на тике {@code tick} экран зрителя затянут {@code level}: −1 — абсолютно чёрный экран, 0 — ничего (ни темноты, ни пересвета), 1 — максимально белый
 * экран; между кадрами плавно. В файле {@code "level"}; старое поле {@code "darkness"} (0..1, затемнение) читается как {@code -darkness}.
 */
public record LightKey(int tick, float level) {
	public static final Codec<LightKey> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.intRange(0, Integer.MAX_VALUE).fieldOf("tick").forGetter(LightKey::tick),
			Codec.floatRange(-1f, 1f).optionalFieldOf("level").forGetter(k -> Optional.of(k.level)),
			Codec.floatRange(0f, 1f).optionalFieldOf("darkness").forGetter(k -> Optional.empty())
	).apply(i, (tick, level, darkness) -> new LightKey(tick, level.orElseGet(() -> -darkness.orElse(0f)))));
}
