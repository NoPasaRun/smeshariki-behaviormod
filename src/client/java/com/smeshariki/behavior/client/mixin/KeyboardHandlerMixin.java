package com.smeshariki.behavior.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.smeshariki.behavior.client.CutsceneClient;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;

/** Редактор в режиме камеры (курсор захвачен, окна нет): клавиши редактора, в том числе Esc («выйти на панель»), берёт он, а не игра (иначе Esc открыл бы меню паузы). */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void behaviormod$editorKeys(long window, int action, KeyEvent event, CallbackInfo ci) {
		// отпускание клавиш не трогаем: состояние клавиш игры должно сниматься как обычно
		if (action != 0 && CutsceneClient.editorKey(event)) ci.cancel();
	}
}
