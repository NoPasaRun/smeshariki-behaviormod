package com.smeshariki.behavior.cutscene;

import com.smeshariki.behavior.BehaviorMod;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Игрок просит пропустить сцену; сервер слушается только хоста. */
public record CutsceneSkip() implements CustomPacketPayload {
	public static final Type<CutsceneSkip> TYPE = new Type<>(BehaviorMod.id("cutscene_skip"));
	public static final StreamCodec<ByteBuf, CutsceneSkip> CODEC = StreamCodec.unit(new CutsceneSkip());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
