package com.smeshariki.behavior.dialog;

import java.util.List;

import com.smeshariki.behavior.BehaviorMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Номер списка (растёт с каждой отправкой) и реплики, которые игрок может сказать сейчас (пусто — нечего, иконка не горит). Шлётся при изменении. */
public record DialogReplies(int version, List<Component> replies) implements CustomPacketPayload {
	public static final Type<DialogReplies> TYPE = new Type<>(BehaviorMod.id("dialog_replies"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DialogReplies> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, DialogReplies::version, ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()), DialogReplies::replies, DialogReplies::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
