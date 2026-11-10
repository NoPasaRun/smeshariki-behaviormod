package com.smeshariki.behavior.cutscene;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

/** Звук на тике сцены: любой id звука (игры или пакета ресурсов), играет у зрителя. */
public record SoundKey(int tick, Identifier sound, float volume, float pitch) {
	public static final Codec<SoundKey> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.intRange(0, Integer.MAX_VALUE).fieldOf("tick").forGetter(SoundKey::tick),
			Identifier.CODEC.fieldOf("sound").forGetter(SoundKey::sound),
			Codec.FLOAT.optionalFieldOf("volume", 1f).forGetter(SoundKey::volume),
			Codec.FLOAT.optionalFieldOf("pitch", 1f).forGetter(SoundKey::pitch)
	).apply(i, SoundKey::new));
}
