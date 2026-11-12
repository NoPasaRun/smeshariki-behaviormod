package com.smeshariki.behavior.cutscene;

import com.smeshariki.behavior.BehaviorMod;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Клиент просит реестр сцен (окно управления кат-сценами). */
public record CutsceneListRequest() implements CustomPacketPayload {
	public static final Type<CutsceneListRequest> TYPE = new Type<>(BehaviorMod.id("cutscene_list_request"));
	public static final StreamCodec<ByteBuf, CutsceneListRequest> CODEC = StreamCodec.unit(new CutsceneListRequest());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
