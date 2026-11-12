package com.smeshariki.behavior.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.smeshariki.behavior.client.CutsceneClient;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;

/** Пока идёт сцена (показ, запись, пуск в редакторе), фоновая музыка и эмбиент ванильной игры не запускаются: сцена звучит сама. Свой саундтрек сцены пропускается. */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
	@Inject(method = "play", at = @At("HEAD"), cancellable = true)
	private void behaviormod$silence(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
		if (CutsceneClient.blocksVanilla(instance)) cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
	}

	@Inject(method = "playDelayed", at = @At("HEAD"), cancellable = true)
	private void behaviormod$silenceDelayed(SoundInstance instance, int delay, CallbackInfo ci) {
		if (CutsceneClient.blocksVanilla(instance)) ci.cancel();
	}
}
