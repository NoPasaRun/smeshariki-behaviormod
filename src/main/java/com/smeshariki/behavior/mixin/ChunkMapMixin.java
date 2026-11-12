package com.smeshariki.behavior.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

	/** На время показа со своей дальностью игроку отправляются чанки дальше, чем настроено на сервере (но не дальше предела игры в 32). */
	@Inject(method = "getPlayerViewDistance", at = @At("RETURN"), cancellable = true)
	private void behaviormod$stream(ServerPlayer player, CallbackInfoReturnable<Integer> cir) {
		int stream = Cutscenes.streamDistance(player);
		if (stream > cir.getReturnValue()) cir.setReturnValue(Math.min(stream, 32));
	}
}
