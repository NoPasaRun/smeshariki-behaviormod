package com.smeshariki.behavior.cutscene;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.smeshariki.behavior.BehaviorMod;

import net.minecraft.resources.Identifier;

/**
 * Саундтрек: звук {@code sound} (любой id игры или пакета ресурсов, например {@code minecraft:music.creative} или свой из пакета) начинает играть на тике {@code tick}
 * (или файл {@code file} — .mp3 из папки музыки зрителя) и звучит до тика {@code end} ({@code 0} — до конца сцены; раньше кончится сам звук — тогда тише тишина). Громкость {@code volume}; {@code fade} тиков — плавно
 * нарастает с начала, {@code fadeOut} тиков — затихает к концу ({@code -1} — как {@code fade}: так читаются старые сцены, где была одна плавность на оба конца). Играет у зрителя как музыка (не привязан к месту). Начать звук с середины нельзя: в редакторе трек стартует с начала, когда время дошло до его тика.
 */
public record MusicKey(int tick, int end, Identifier sound, float volume, int fade, int fadeOut, String file) {
	/** Заглушка в {@code sound}, когда играет файл. */
	public static final Identifier NONE = BehaviorMod.id("none");

	public static final Codec<MusicKey> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.intRange(0, Integer.MAX_VALUE).fieldOf("tick").forGetter(MusicKey::tick),
			Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("end", 0).forGetter(MusicKey::end),
			Identifier.CODEC.optionalFieldOf("sound", NONE).forGetter(MusicKey::sound),
			Codec.FLOAT.optionalFieldOf("volume", 1f).forGetter(MusicKey::volume),
			Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("fade", 0).forGetter(MusicKey::fade),
			Codec.intRange(-1, Integer.MAX_VALUE).optionalFieldOf("fade_out", -1).forGetter(MusicKey::fadeOut),
			Codec.STRING.optionalFieldOf("file", "").forGetter(MusicKey::file)
	).apply(i, MusicKey::new));

	/** Играет файл .mp3 из {@code config/behaviormod/music/} у зрителя, а не звук игры. */
	public boolean isFile() {
		return !file.isEmpty();
	}

	/** Что показывать человеку: имя файла или id звука. */
	public String title() {
		return isFile() ? file : sound.toString();
	}

	/** Тик, на котором трек кончается в сцене длины {@code length}. */
	public int endIn(int length) {
		return end <= tick ? Math.max(length, tick + 1) : end;
	}

	public MusicKey withTick(int value) {
		return new MusicKey(value, end > 0 ? end + (value - tick) : 0, sound, volume, fade, fadeOut, file);
	}

	/** Тот же трек, но кончается на тике {@code newEnd} ({@code 0} — до конца сцены). */
	public MusicKey withEnd(int newEnd) {
		return new MusicKey(tick, newEnd, sound, volume, fade, fadeOut, file);
	}

	/** Сколько тиков затихает к концу. */
	public int fadeOutTicks() {
		return fadeOut < 0 ? fade : fadeOut;
	}

	/** Громкость в момент {@code time} (0 вне трека) с учётом нарастания и затухания. */
	public float gain(float time, int length) {
		float e = endIn(length);
		if (time < tick || time >= e) return 0f;
		float g = volume;
		float k = 1f;
		if (fade > 0) k = Math.min(k, (time - tick) / fade);
		int out = fadeOutTicks();
		if (out > 0) k = Math.min(k, (e - time) / out);
		return g * k;
	}
}
