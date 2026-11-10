package com.smeshariki.behavior.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.smeshariki.behavior.cutscene.Cutscenes;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/** Чанки игроку присылаются вокруг его камеры сцены, а не вокруг тела, пока камера улетела от него. */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
	@Redirect(method = "updateChunkTracking", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;chunkPosition()Lnet/minecraft/world/level/ChunkPos;"))
	private ChunkPos behaviormod$viewCenter(ServerPlayer player) {
		ChunkPos view = Cutscenes.viewChunk(player);
		return view != null ? view : player.chunkPosition();
	}
}
