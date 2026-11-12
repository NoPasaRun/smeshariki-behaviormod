package com.smeshariki.behavior.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.smeshariki.behavior.client.CutsceneClient;

import net.minecraft.client.Options;

/**
 * Показ сцены со своей дальностью: пока идёт показ и сервер подтвердил радиус чанков, отрисовка берёт дальность сцены, если она больше настройки игрока.
 * Настройка в файле не меняется. Сервер расширяет круг отправки чанков и присылает радиус сам (см. {@code Cutscenes.beginStream}).
 */
@Mixin(Options.class)
public abstract class OptionsMixin {
	@Shadow
	private int serverRenderDistance;

	@Inject(method = "getEffectiveRenderDistance", at = @At("RETURN"), cancellable = true)
	private void behaviormod$cutsceneDistance(CallbackInfoReturnable<Integer> cir) {
		int want = CutsceneClient.streamDistance();
		if (want > cir.getReturnValue() && serverRenderDistance >= want) cir.setReturnValue(want);
	}
}
