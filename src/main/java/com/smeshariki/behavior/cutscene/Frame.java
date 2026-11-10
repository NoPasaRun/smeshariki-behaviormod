package com.smeshariki.behavior.cutscene;

import java.util.List;

import com.mojang.serialization.Codec;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Один тик записи актёра: положение от точки привязки, поворот тела, головы и наклон головы, флаги. В файле кадр — плоский список из семи чисел
 * ({@code x, y, z, тело, голова, наклон, флаги}): так запись в сотни тиков остаётся небольшой.
 */
public record Frame(float x, float y, float z, float yaw, float headYaw, float pitch, int flags) {
	/** Флаги кадра. */
	public static final int CROUCH = 1, SWING = 2;

	public static final Codec<Frame> CODEC = Codec.FLOAT.listOf(7, 7).xmap(
			l -> new Frame(l.get(0), l.get(1), l.get(2), l.get(3), l.get(4), l.get(5), Math.round(l.get(6))),
			f -> List.of(f.x, f.y, f.z, f.yaw, f.headYaw, f.pitch, (float) f.flags));

	public Vec3 pos() {
		return new Vec3(x, y, z);
	}

	public boolean has(int flag) {
		return (flags & flag) != 0;
	}

	/** Между кадрами: положение и углы плавно (повороты по короткой дуге), флаги — от первого. */
	public static Frame lerp(Frame a, Frame b, float f) {
		return new Frame(Mth.lerp(f, a.x, b.x), Mth.lerp(f, a.y, b.y), Mth.lerp(f, a.z, b.z), turn(a.yaw, b.yaw, f), turn(a.headYaw, b.headYaw, f), Mth.lerp(f, a.pitch, b.pitch), a.flags);
	}

	static float turn(float from, float to, float f) {
		return from + Mth.wrapDegrees(to - from) * f;
	}
}
