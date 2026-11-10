package com.smeshariki.behavior.cutscene;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Ключевой кадр камеры («стоп»): на тике {@code tick} камера стоит в {@code pos} (от точки привязки сцены), смотрит {@code yaw}/{@code pitch}, крен {@code roll},
 * угол {@code fov}. {@code ease} — функция из {@link Easings}, по которой камера идёт от этого кадра к следующему (в файле {@code "smooth"} значит {@code behaviormod:smooth}).
 */
public record CameraKey(int tick, Vec3 pos, float yaw, float pitch, float roll, float fov, Identifier ease) {
	private static final Codec<Identifier> EASE_CODEC = Codec.STRING.xmap(Easings::parse, id -> id.getNamespace().equals("behaviormod") ? id.getPath() : id.toString());

	public static final Codec<CameraKey> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.intRange(0, Integer.MAX_VALUE).fieldOf("tick").forGetter(CameraKey::tick),
			Vec3.CODEC.fieldOf("pos").forGetter(CameraKey::pos),
			Codec.FLOAT.optionalFieldOf("yaw", 0f).forGetter(CameraKey::yaw),
			Codec.FLOAT.optionalFieldOf("pitch", 0f).forGetter(CameraKey::pitch),
			Codec.FLOAT.optionalFieldOf("roll", 0f).forGetter(CameraKey::roll),
			Codec.FLOAT.optionalFieldOf("fov", 70f).forGetter(CameraKey::fov),
			EASE_CODEC.optionalFieldOf("ease", Easings.SMOOTH).forGetter(CameraKey::ease)
	).apply(i, CameraKey::new));

	public CameraKey withEase(Identifier value) {
		return new CameraKey(tick, pos, yaw, pitch, roll, fov, value);
	}

	/** Тот же кадр с новым видом (положение, повороты, крен, угол); тик и функция прежние. */
	public CameraKey withView(Vec3 newPos, float newYaw, float newPitch, float newRoll, float newFov) {
		return new CameraKey(tick, newPos, newYaw, newPitch, newRoll, newFov, ease);
	}
}
