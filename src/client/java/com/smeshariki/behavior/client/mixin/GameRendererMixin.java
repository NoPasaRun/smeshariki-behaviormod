package com.smeshariki.behavior.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.smeshariki.behavior.client.PuppetAccess;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;

/** После каждого кадра: при съёмке видео кадр уходит в файл. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "render", at = @At("TAIL"))
	private void behaviormod$frame(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
		PuppetAccess.afterFrame(((GameRenderer) (Object) this).mainRenderTarget());
	}
}
