package com.smeshariki.behavior.dialog;

import com.smeshariki.behavior.BehaviorMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Сервер просит клиента открыть список реплик (игрок начал разговор: Нюша позвала смешарика поговорить). Если реплик нет, ничего не происходит. */
public record OpenReplies() implements CustomPacketPayload {
	public static final Type<OpenReplies> TYPE = new Type<>(BehaviorMod.id("open_replies"));
	public static final StreamCodec<RegistryFriendlyByteBuf, OpenReplies> CODEC = StreamCodec.unit(new OpenReplies());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
