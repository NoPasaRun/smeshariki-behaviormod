package com.smeshariki.behavior.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Иконка «говорить» справа от панели: горит, пока есть реплики; под ней клавиша окна. */
final class TalkIcon {
	private static final int W = 18, H = 12;
	private static final int FILL = 0xFFF4F0E0, EDGE = 0xFF303030, DOT = 0xFF303030, KEY = 0xFFFFFFFF;

	private TalkIcon() {
	}

	static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || BehaviorModClient.replies.isEmpty()) return;
		int screenW = mc.getWindow().getGuiScaledWidth(), screenH = mc.getWindow().getGuiScaledHeight();
		int x = screenW / 2 + 91 + 10, y = screenH - 22;
		// облачко с хвостиком снизу слева и тремя точками
		g.fill(x + 1, y - 1, x + W - 1, y + H + 1, EDGE);
		g.fill(x - 1, y + 1, x + W + 1, y + H - 1, EDGE);
		g.fill(x + 1, y, x + W - 1, y + H, FILL);
		g.fill(x, y + 1, x + W, y + H - 1, FILL);
		g.fill(x + 3, y + H, x + 7, y + H + 2, EDGE);
		g.fill(x + 3, y + H + 2, x + 5, y + H + 4, EDGE);
		g.fill(x + 4, y + H - 1, x + 6, y + H + 2, FILL);
		for (int i = 0; i < 3; i++) g.fill(x + 4 + i * 4, y + 5, x + 6 + i * 4, y + 7, DOT);
		g.text(mc.font, keyLabel(), x + W + 4, y + 2, KEY);
	}

	/**
	 * Подпись клавиши. Буквы — латиницей по самой клавише: игра берёт имя из раскладки, и на русской V подписывается «М», хотя нажимать
	 * надо клавишу V.
	 */
	static net.minecraft.network.chat.Component keyLabel() {
		var key = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.getBoundKeyOf(BehaviorModClient.TALK);
		int code = key.getValue();
		if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM && (code >= org.lwjgl.glfw.GLFW.GLFW_KEY_A && code <= org.lwjgl.glfw.GLFW.GLFW_KEY_Z || code >= org.lwjgl.glfw.GLFW.GLFW_KEY_0 && code <= org.lwjgl.glfw.GLFW.GLFW_KEY_9))
			return net.minecraft.network.chat.Component.literal(String.valueOf((char) code));
		return BehaviorModClient.TALK.getTranslatedKeyMessage();
	}
}
