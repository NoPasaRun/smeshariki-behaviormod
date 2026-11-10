package com.smeshariki.behavior.cutscene;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

/** Реплика-субтитр: с тика {@code tick} на {@code duration} тиков внизу экрана. */
public record LineKey(int tick, int duration, Component text) {
	public static final Codec<LineKey> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.intRange(0, Integer.MAX_VALUE).fieldOf("tick").forGetter(LineKey::tick),
			Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("duration", 60).forGetter(LineKey::duration),
			ComponentSerialization.CODEC.fieldOf("text").forGetter(LineKey::text)
	).apply(i, LineKey::new));
}
