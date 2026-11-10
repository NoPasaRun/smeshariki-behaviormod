package com.smeshariki.behavior.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.smeshariki.behavior.client.CutsceneClient;

import net.minecraft.client.Minecraft;

/** Пока смотрим сцену или правим её, игровые клавиши (инвентарь, выброс, чат, удар, цифры панели) не срабатывают; привязки редактора читает он сам. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "handleKeybinds", at = @At("HEAD"), cancellable = true)
	private void behaviormod$freeze(CallbackInfo ci) {
		if (CutsceneClient.active()) ci.cancel();
	}
}
