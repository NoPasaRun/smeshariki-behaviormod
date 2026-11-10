package com.smeshariki.behavior.cutscene;

import com.smeshariki.behavior.BehaviorMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * Клиент сообщает, где сейчас его камера сцены ({@code on}) или что камера снова у игрока. Пока камера улетела от тела, сервер грузит и присылает чанки вокруг неё
 * (иначе вдали от игрока был бы пустой мир): см. {@code ChunkMapMixin}.
 */
public record ViewCenter(boolean on, Vec3 pos) implements CustomPacketPayload {
	public static final Type<ViewCenter> TYPE = new Type<>(BehaviorMod.id("view_center"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ViewCenter> CODEC = StreamCodec.of(
			(buf, v) -> {
				buf.writeBoolean(v.on);
				buf.writeDouble(v.pos.x);
				buf.writeDouble(v.pos.y);
				buf.writeDouble(v.pos.z);
			},
			buf -> new ViewCenter(buf.readBoolean(), new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
