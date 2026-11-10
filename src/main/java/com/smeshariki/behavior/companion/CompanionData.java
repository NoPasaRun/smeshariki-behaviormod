package com.smeshariki.behavior.companion;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

/**
 * Что помнит напарник (вложение сущности, сохраняется с ней): чей он, стоит ли на месте по приказу и что делает стропой без остановки
 * ({@code ropeAction} — действие {@link com.smeshariki.gameplay.raft.BalloonRopeAction}, −1 — ничего), пока не скажут «стой».
 */
public record CompanionData(UUID leader, boolean waiting, int ropeAction) {
	public static final int NONE = -1;
	public static final Codec<CompanionData> CODEC = RecordCodecBuilder.create(i -> i.group(
			UUIDUtil.CODEC.fieldOf("leader").forGetter(CompanionData::leader),
			Codec.BOOL.optionalFieldOf("waiting", false).forGetter(CompanionData::waiting),
			Codec.INT.optionalFieldOf("rope_action", NONE).forGetter(CompanionData::ropeAction)
	).apply(i, CompanionData::new));

	public CompanionData withWaiting(boolean value) {
		return new CompanionData(leader, value, ropeAction);
	}

	public CompanionData withRopeAction(int value) {
		return new CompanionData(leader, waiting, value);
	}
}
