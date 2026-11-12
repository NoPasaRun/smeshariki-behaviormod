package com.smeshariki.behavior.cutscene;

import com.smeshariki.behavior.BehaviorMod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Действие из окна управления кат-сценами: создать (пустую, сразу сохранённую, и открыть в редакторе), удалить (из мира), править, играть. Имя сервер разбирает сам. */
public record CutsceneAction(int kind, String name) implements CustomPacketPayload {
	public static final int CREATE = 0, DELETE = 1, EDIT = 2, PLAY = 3;
	public static final Type<CutsceneAction> TYPE = new Type<>(BehaviorMod.id("cutscene_action"));
	public static final StreamCodec<FriendlyByteBuf, CutsceneAction> CODEC = StreamCodec.of((buf, a) -> {
		buf.writeVarInt(a.kind);
		buf.writeUtf(a.name, 256);
	}, buf -> new CutsceneAction(buf.readVarInt(), buf.readUtf(256)));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
