package com.smeshariki.behavior.client;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Реплики внизу экрана над панелью, без окна: игра идёт дальше, мышь и движение свободны. Оформление то же, что у окон «Подробнее» и «Описание механики»:
 * тёмная панель со срезанными углами, шапка с золотой полосой, у каждой реплики плашка с номером — цифрой её говорят ({@link BehaviorModClient}).
 */
final class RepliesHud {
	private static final int LINE = 10, PAD = 8, HEADER_H = 17, MAX_W = 320, BOTTOM_GAP = 52, BADGE = 14, GAP = 3;
	private static final int PANEL = 0xF2141922, PANEL_EDGE = 0xFF334155, HEADER = 0xFF1C2431, GOLD = 0xFFFFC857, MUTED = 0xFF8FA0B5, TEXT = 0xFFE3E8EF;
	private static final int ROW_BG = 0xFF1E2A3A, ROW_BAR = 0xFF4FA3FF, SLOT = 0xFF0E1218, SLOT_EDGE = 0xFF3C4A5E;

	private RepliesHud() {
	}

	private static void rounded(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
		g.fill(x0 + 1, y0, x1 - 1, y1, color);
		g.fill(x0, y0 + 1, x1, y1 - 1, color);
	}

	static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (CutsceneClient.hidesHud()) return;
		Minecraft mc = Minecraft.getInstance();
		List<Component> replies = BehaviorModClient.replies;
		if (!BehaviorModClient.open || mc.player == null || replies.isEmpty()) return;
		int count = Math.min(replies.size(), mc.options.keyHotbarSlots.length);
		int w = 0;
		for (int i = 0; i < count; i++) w = Math.max(w, mc.font.width(replies.get(i)) + BADGE + 3 * PAD);
		w = Math.min(Math.max(w, 160), Math.min(MAX_W, g.guiWidth() - 16));
		int textW = w - 2 * PAD - BADGE - 6;
		List<List<FormattedCharSequence>> lines = new java.util.ArrayList<>();
		int rowsH = 0;
		for (int i = 0; i < count; i++) {
			List<FormattedCharSequence> split = mc.font.split(replies.get(i), textW);
			lines.add(split);
			rowsH += Math.max(BADGE, split.size() * LINE + 4) + GAP;
		}
		int h = HEADER_H + PAD / 2 + rowsH + PAD / 2;
		int x = (g.guiWidth() - w) / 2, y = g.guiHeight() - BOTTOM_GAP - h;
		rounded(g, x - 1, y - 1, x + w + 1, y + h + 1, PANEL_EDGE);
		rounded(g, x, y, x + w, y + h, PANEL);
		g.fill(x + 1, y + 1, x + w - 1, y + HEADER_H, HEADER);
		g.fill(x + 1, y + 1, x + w - 1, y + 3, GOLD);
		g.fill(x + 1, y + HEADER_H, x + w - 1, y + HEADER_H + 1, PANEL_EDGE);
		g.text(mc.font, Component.translatable("gui.behaviormod.replies").withStyle(ChatFormatting.BOLD), x + PAD, y + 6, GOLD);
		Component key = TalkIcon.keyLabel();
		g.text(mc.font, key, x + w - PAD - mc.font.width(key), y + 6, MUTED);
		int ry = y + HEADER_H + PAD / 2 + 1;
		for (int i = 0; i < count; i++) {
			List<FormattedCharSequence> split = lines.get(i);
			int rh = Math.max(BADGE, split.size() * LINE + 4);
			g.fill(x + PAD, ry, x + w - PAD, ry + rh, ROW_BG);
			g.fill(x + PAD, ry, x + PAD + 2, ry + rh, ROW_BAR);
			int bx = x + PAD + 5, by = ry + (rh - BADGE + 4) / 2 - 1;
			rounded(g, bx - 1, by - 1, bx + BADGE - 4 + 1, by + BADGE - 4 + 1, SLOT_EDGE);
			rounded(g, bx, by, bx + BADGE - 4, by + BADGE - 4, SLOT);
			String number = String.valueOf(i + 1);
			g.text(mc.font, number, bx + (BADGE - 4 - mc.font.width(number)) / 2 + 1, by + 1, GOLD);
			for (int l = 0; l < split.size(); l++) g.text(mc.font, split.get(l), x + PAD + BADGE + 6, ry + (rh - split.size() * LINE) / 2 + l * LINE + 1, TEXT);
			ry += rh + GAP;
		}
	}
}
