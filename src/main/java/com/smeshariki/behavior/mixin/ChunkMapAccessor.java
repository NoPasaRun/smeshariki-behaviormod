package com.smeshariki.behavior.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code ChunkMap.move} пересчитывает, какие чанки слать игроку, только когда сам игрок сменил секцию мира; камера сцены улетает при стоящем теле, поэтому
 * пересчёт зовём напрямую (центр подменяет {@link ChunkMapMixin}).
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
	@Invoker("updateChunkTracking")
	void behaviormod$updateChunkTracking(ServerPlayer player);
}
