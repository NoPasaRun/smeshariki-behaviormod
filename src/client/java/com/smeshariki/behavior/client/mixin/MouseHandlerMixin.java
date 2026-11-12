package com.smeshariki.behavior.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.smeshariki.behavior.client.CutsceneClient;

import net.minecraft.client.MouseHandler;

/** Во время кат-сцены и в редакторе мышь не поворачивает игрока; камеру редактора в режиме камеры поворачивает {@link CutsceneClient#editorLook}. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow
	private double accumulatedDX;

	@Shadow
	private double accumulatedDY;

	@Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	private void behaviormod$freeze(double dt, CallbackInfo ci) {
		if (!CutsceneClient.active()) return;
		// редактор в режиме камеры: мышь поворачивает камеру редактора, как игрока в обычной игре
		CutsceneClient.editorLook(accumulatedDX, accumulatedDY);
		accumulatedDX = 0;
		accumulatedDY = 0;
		ci.cancel();
	}
}
