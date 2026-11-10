package com.smeshariki.behavior.dialog;

import com.smeshariki.behavior.BehaviorMod;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Игрок выбрал реплику: её место и номер списка, из которого выбирал (сервер сверяет, что список не сменился, пока окно было открыто). */
public record DialogChoice(int index, int version) implements CustomPacketPayload {
	public static final Type<DialogChoice> TYPE = new Type<>(BehaviorMod.id("dialog_choice"));
	public static final StreamCodec<ByteBuf, DialogChoice> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, DialogChoice::index, ByteBufCodecs.VAR_INT, DialogChoice::version, DialogChoice::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
