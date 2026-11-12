package com.smeshariki.behavior.cutscene;

import java.util.ArrayList;
import java.util.List;

import com.smeshariki.behavior.BehaviorMod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Реестр сцен для окна управления: id, записана ли в мире (только такую можно удалить), длина в тиках, первая ошибка описания; {@code message} — итог последнего действия («создана», «не удалось…»), пусто — нечего сказать. */
public record CutsceneList(List<Entry> entries, String message) implements CustomPacketPayload {
	public static final Type<CutsceneList> TYPE = new Type<>(BehaviorMod.id("cutscene_list"));
	public static final StreamCodec<FriendlyByteBuf, CutsceneList> CODEC = StreamCodec.of((buf, list) -> {
		buf.writeUtf(list.message, 256);
		buf.writeVarInt(list.entries.size());
		for (Entry e : list.entries) {
			buf.writeIdentifier(e.id);
			buf.writeBoolean(e.world);
			buf.writeVarInt(e.length);
			buf.writeUtf(e.problem, 256);
		}
	}, buf -> {
		String message = buf.readUtf(256);
		int n = buf.readVarInt();
		List<Entry> out = new ArrayList<>();
		for (int i = 0; i < n; i++) out.add(new Entry(buf.readIdentifier(), buf.readBoolean(), buf.readVarInt(), buf.readUtf(256)));
		return new CutsceneList(out, message);
	});

	/** {@code problem} — первая ошибка описания сцены (пусто — всё хорошо). */
	public record Entry(Identifier id, boolean world, int length, String problem) {
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
