package com.smeshariki.behavior.cutscene;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.DataResult;
import com.smeshariki.behavior.BehaviorMod;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Сцена по сети: сжатый NBT, нарезанный кусками (пакет мода не больше 32 КБ в сторону сервера), в обе стороны. {@code purpose} — зачем:
 * {@link #WATCH} показать зрителю, {@link #RECORD} играть остальных актёров, пока записывается дубль, {@link #EDIT} открыть в редакторе,
 * {@link #SAVE} (на сервер) сохранить правку редактора, {@link #RENDER} показать и снять в видео (в {@code character} — кадров в секунду). {@code at} — тик сцены (с него играет запись, куда встаёт курсор редактора),
 * {@code character} — кого записывают (пусто — никого).
 */
public record CutsceneBlob(Identifier id, int purpose, Vec3 origin, int at, String character, int index, int total, byte[] data) implements CustomPacketPayload {
	public static final int WATCH = 0, RECORD = 1, EDIT = 2, SAVE = 3, RENDER = 4;
	public static final Type<CutsceneBlob> TYPE = new Type<>(BehaviorMod.id("cutscene_blob"));
	private static final int CHUNK = 20000;
	public static final StreamCodec<FriendlyByteBuf, CutsceneBlob> CODEC = StreamCodec.of(
			(buf, b) -> {
				buf.writeIdentifier(b.id);
				buf.writeVarInt(b.purpose);
				buf.writeDouble(b.origin.x);
				buf.writeDouble(b.origin.y);
				buf.writeDouble(b.origin.z);
				buf.writeVarInt(b.at);
				buf.writeUtf(b.character);
				buf.writeVarInt(b.index);
				buf.writeVarInt(b.total);
				buf.writeByteArray(b.data);
			},
			buf -> new CutsceneBlob(buf.readIdentifier(), buf.readVarInt(), new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()), buf.readVarInt(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(CHUNK + 16)));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	/** Сцена → куски. */
	public static List<CutsceneBlob> split(Identifier id, int purpose, Vec3 origin, int at, String character, Cutscene scene) {
		byte[] all = toBytes(scene);
		int total = Math.max(1, (all.length + CHUNK - 1) / CHUNK);
		List<CutsceneBlob> out = new ArrayList<>();
		for (int i = 0; i < total; i++) {
			int from = i * CHUNK, to = Math.min(all.length, from + CHUNK);
			out.add(new CutsceneBlob(id, purpose, origin, at, character, i, total, java.util.Arrays.copyOfRange(all, from, to)));
		}
		return out;
	}

	public static byte[] toBytes(Cutscene scene) {
		try {
			Tag tag = Cutscene.CODEC.encodeStart(NbtOps.INSTANCE, scene).getOrThrow();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			NbtIo.writeCompressed((CompoundTag) tag, out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public static DataResult<Cutscene> fromBytes(byte[] bytes) {
		try {
			CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
			return Cutscene.CODEC.parse(NbtOps.INSTANCE, tag);
		} catch (IOException e) {
			return DataResult.error(e::getMessage);
		}
	}

	/** Собирает куски одной передачи. */
	public static final class Assembler {
		private final List<byte[]> parts = new ArrayList<>();
		private Identifier id;
		private int purpose = -1;

		/** @return сцена, когда пришёл последний кусок; иначе {@code null} (ошибка разбора — тоже, её пишет {@code onError}) */
		public Cutscene add(CutsceneBlob b, java.util.function.Consumer<String> onError) {
			if (b.index == 0 || !b.id.equals(id) || b.purpose != purpose) {
				parts.clear();
				id = b.id;
				purpose = b.purpose;
			}
			if (b.index != parts.size()) {
				parts.clear();
				return null;
			}
			parts.add(b.data);
			if (parts.size() < b.total) return null;
			ByteArrayOutputStream all = new ByteArrayOutputStream();
			for (byte[] p : parts) all.writeBytes(p);
			parts.clear();
			DataResult<Cutscene> result = fromBytes(all.toByteArray());
			if (result.error().isPresent()) {
				onError.accept(result.error().get().message());
				return null;
			}
			return result.result().orElse(null);
		}
	}
}
