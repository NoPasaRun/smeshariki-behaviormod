package com.smeshariki.behavior.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.smeshariki.behavior.client.CutsceneClient;

import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/** Во время кат-сцены игрок не двигается: нажатия клавиш движения обнуляются. */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
	@Inject(method = "tick", at = @At("TAIL"))
	private void behaviormod$freeze(CallbackInfo ci) {
		if (!CutsceneClient.active()) return;
		keyPresses = new Input(false, false, false, false, false, false, false);
		moveVector = Vec2.ZERO;
	}
}
