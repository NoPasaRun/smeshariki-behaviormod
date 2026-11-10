package com.smeshariki.behavior.client;

import java.util.List;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Реплики внизу экрана над панелью, без окна: игра идёт дальше, мышь и движение свободны. У каждой — номер, нажатие цифры произносит её
 * ({@link BehaviorModClient}). Первая реплика — верхняя.
 */
final class RepliesHud {
	private static final int ROW = 14, PAD = 4, MAX_W = 260, BOTTOM_GAP = 52;
	private static final int BOX = 0xB0101820, EDGE = 0xFF5FA8E8, NUMBER = 0xFFFFD040, TEXT = 0xFFFFFFFF;

	private RepliesHud() {
	}

	static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		List<Component> replies = BehaviorModClient.replies;
		if (!BehaviorModClient.open || mc.player == null || replies.isEmpty()) return;
		int count = Math.min(replies.size(), mc.options.keyHotbarSlots.length);
		int w = 0;
		for (int i = 0; i < count; i++) w = Math.max(w, mc.font.width(label(i)) + mc.font.width(replies.get(i)) + 2 * PAD);
		w = Math.min(w, Math.min(MAX_W, g.guiWidth() - 16));
		int x = (g.guiWidth() - w) / 2;
		int top = g.guiHeight() - BOTTOM_GAP - count * ROW;
		for (int i = 0; i < count; i++) {
			int y = top + i * ROW;
			g.fill(x, y, x + w, y + ROW - 2, BOX);
			g.fill(x, y, x + 1, y + ROW - 2, EDGE);
			String number = label(i);
			g.text(mc.font, number, x + PAD, y + 2, NUMBER);
			int textX = x + PAD + mc.font.width(number);
			g.text(mc.font, mc.font.substrByWidth(replies.get(i), x + w - PAD - textX).getString(), textX, y + 2, TEXT);
		}
	}

	/** Номер реплики — клавиша, которой её сказать (цифры панели). */
	private static String label(int i) {
		return (i + 1) + ". ";
	}
}
