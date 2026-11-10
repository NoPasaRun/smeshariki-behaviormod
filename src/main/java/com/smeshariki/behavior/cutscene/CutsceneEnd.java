package com.smeshariki.behavior.cutscene;

import com.smeshariki.behavior.BehaviorMod;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Показ кончился (досчитан или пропущен): вернуть камеру и управление. */
public record CutsceneEnd() implements CustomPacketPayload {
	public static final Type<CutsceneEnd> TYPE = new Type<>(BehaviorMod.id("cutscene_end"));
	public static final StreamCodec<ByteBuf, CutsceneEnd> CODEC = StreamCodec.unit(new CutsceneEnd());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
