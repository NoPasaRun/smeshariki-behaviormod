package com.smeshariki.behavior.client;

import java.util.List;

import com.smeshariki.behavior.cutscene.ActionKey;
import com.smeshariki.behavior.cutscene.ActorTrack;
import com.smeshariki.behavior.cutscene.CameraKey;
import com.smeshariki.behavior.cutscene.Clip;
import com.smeshariki.behavior.cutscene.Cutscene;
import com.smeshariki.behavior.cutscene.LightKey;
import com.smeshariki.behavior.cutscene.LineKey;
import com.smeshariki.behavior.cutscene.MusicKey;
import com.smeshariki.behavior.cutscene.SoundKey;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Внизу экрана: реплика сцены и (в редакторе) полоса времени — дорожки камеры, актёров, реплик, звуков, действий и света, курсор, ромбики кадров, клипы.
 * Показывается видимая часть времени ({@code CutsceneEditor.viewStart/viewLen}); геометрия общая с {@link EditorScreen}, который по ней понимает клики.
 */
final class TimelineHud {
	static final int ROW = 11, LABEL = 84;
	private static final int BG = 0xC0101418, GRID = 0x30FFFFFF, CURSOR = 0xFFFF4040, CAMERA = 0xFFFFD040, CLIP = 0xFF4090E0, MUTED = 0xFF606870, END = 0xFF8060FF;

	private TimelineHud() {
	}

	static int rows(Cutscene s) {
		return 1 + s.actors().size() + 5;
	}

	static int stripH(Cutscene s) {
		return rows(s) * ROW + 26;
	}

	static int stripTop(int guiH, Cutscene s) {
		return guiH - stripH(s);
	}

	static int rowsTop(int guiH, Cutscene s) {
		return stripTop(guiH, s) + 14;
	}

	private static final int TOOL_W = 62, TOOL_H = 12;

	/** Плашка инструмента под курсором мыши; −1 — мимо. Плашки стоят над полосой слева. */
	static int toolAt(double mx, double my, int guiH, Cutscene s) {
		int y = stripTop(guiH, s) - TOOL_H - 1;
		if (my < y || my >= y + TOOL_H || mx < 6) return -1;
		int i = (int) ((mx - 6) / (TOOL_W + 2));
		return i < CutsceneEditor.Tool.values().length && (mx - 6) % (TOOL_W + 2) < TOOL_W ? i : -1;
	}

	static int x1(int guiW) {
		return guiW - 8;
	}

	static float xToTick(double x, int guiW) {
		return CutsceneEditor.viewStart + (float) ((x - LABEL) / (x1(guiW) - LABEL)) * CutsceneEditor.viewLen;
	}

	static int tickToX(float tick, int guiW) {
		return Math.round(LABEL + (tick - CutsceneEditor.viewStart) / CutsceneEditor.viewLen * (x1(guiW) - LABEL));
	}

	/** Строка полосы под высотой {@code y}: 0 — камера, потом актёры, реплики, звуки, действия, свет; −1 — не строка. */
	static int rowAt(double y, int guiH, Cutscene s) {
		int r = (int) Math.floor((y - rowsTop(guiH, s)) / ROW);
		return r >= 0 && r < rows(s) ? r : -1;
	}

	static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		Cutscene scene = CutsceneClient.scene;
		if (scene == null || CutsceneClient.mode() == CutsceneClient.Mode.NONE) return;
		boolean edit = CutsceneClient.mode() == CutsceneClient.Mode.EDIT;
		int stripH = edit ? stripH(scene) : 0;
		LineKey line = CutsceneClient.line();
		if (line != null) {
			String text = line.text().getString();
			int w = Math.min(g.guiWidth() - 40, mc.font.width(text) + 16);
			int x = (g.guiWidth() - w) / 2, y = g.guiHeight() - stripH - (edit ? 48 : 70);
			g.fill(x, y, x + w, y + 14, 0xB0000000);
			g.centeredText(mc.font, mc.font.substrByWidth(line.text(), w - 8).getString(), g.guiWidth() / 2, y + 3, 0xFFFFFFFF);
		}
		if (edit) strip(g, mc.font, scene, stripH);
	}

	private static boolean visible(int tick) {
		return tick >= CutsceneEditor.viewStart - 1 && tick <= CutsceneEditor.viewStart + CutsceneEditor.viewLen + 1;
	}

	private static int step(float pxPerTick) {
		int[] steps = { 1, 2, 5, 10, 20, 40, 100, 200, 400, 1000, 2000, 4000, 10000, 20000, 40000, 100000, 200000, 1000000 };
		for (int s : steps) if (s * pxPerTick >= 46) return s;
		return steps[steps.length - 1];
	}

	private static void strip(GuiGraphicsExtractor g, Font font, Cutscene scene, int stripH) {
		int w = g.guiWidth(), h = g.guiHeight();
		int rows = rows(scene), top = stripTop(h, scene), x0 = LABEL, x1 = x1(w);
		g.fill(0, top, w, h, BG);
		String state = String.format("%s  тик %d/%d (%.1f с)  %s%s%s%s%s%s%s", CutsceneEditor.id, CutsceneEditor.cursor, scene.length(), CutsceneEditor.cursor / 20f, CutsceneEditor.rollText(),
				CutsceneEditor.playing ? (CutsceneEditor.hasSelection() ? "  ▶ по кругу" : "  ▶") : "", CutsceneEditor.recordingCamera ? "  ● камера" : "", CutsceneEditor.followMove ? "  ↗ по движению (F)" : "", CutsceneEditor.lookThrough ? "  ◉ из сцены (G или WASD — назад)" : "", CutsceneEditor.autoStopSeconds > 0 ? "  ⏹ авто-стоп " + CutsceneEditor.autoStopSeconds + " с (K)" : "", CutsceneEditor.armed ? "  ◌ ждёт движения" : "");
		g.text(font, state + (CutsceneEditor.dirty ? "  *не сохранено" : ""), 6, top + 2, CutsceneEditor.dirty ? 0xFFFFC060 : 0xFFFFFFFF);
		String help = (CutsceneEditor.focused ? "КАМЕРА: мышь — взгляд, WASD — полёт · Esc — курсор и панель" : "ПАНЕЛЬ: клик по миру или Esc — камера") + " · F1 клавиши · ←/→ инструмент · Enter поставить · P пуск · B дубль · O сохранить";
		g.text(font, help, Math.max(6 + font.width(state) + 20, w - 8 - font.width(help)), top + 2, 0xFF808890);
		if (System.currentTimeMillis() < CutsceneEditor.messageUntil) g.text(font, font.substrByWidth(net.minecraft.network.chat.Component.literal(CutsceneEditor.message), w - 12).getString(), 6, top - 26, 0xFFB0E0FF);
		// панель инструментов: активный подсвечен, стрелки влево / вправо переключают
		CutsceneEditor.Tool[] tools = CutsceneEditor.Tool.values();
		for (int i = 0; i < tools.length; i++) {
			int tx = 6 + i * (TOOL_W + 2), ty = top - TOOL_H - 1;
			boolean active = tools[i] == CutsceneEditor.tool;
			g.fill(tx, ty, tx + TOOL_W, ty + TOOL_H, active ? 0xE02A6FB0 : 0xC0101418);
			g.outline(tx, ty, TOOL_W, TOOL_H, active ? 0xFFFFFFFF : 0xFF404850);
			g.centeredText(font, tools[i].title, tx + TOOL_W / 2, ty + 2, active ? 0xFFFFFFFF : 0xFFB0B0B0);
		}
		int y = rowsTop(h, scene);
		// за концом сцены — темнее
		int endX = tickToX(scene.length(), w);
		if (endX < x1) g.fill(Math.max(x0, endX), y, x1, y + rows * ROW, 0x50000000);
		// шкала: шаг подбирается под масштаб
		float px = (x1 - x0) / CutsceneEditor.viewLen;
		int step = step(px);
		int first = (int) (Math.floor(CutsceneEditor.viewStart / step) * step);
		for (int t = first; t <= CutsceneEditor.viewStart + CutsceneEditor.viewLen; t += step) {
			if (t < 0) continue;
			int gx = tickToX(t, w);
			if (gx < x0 || gx > x1) continue;
			g.fill(gx, y, gx + 1, y + rows * ROW, GRID);
			g.text(font, step % 20 == 0 || t % 20 == 0 && step >= 20 ? (t / 20) + "с" : String.valueOf(t), gx + 2, y - 10, 0xFFA0A0A0);
		}
		if (endX >= x0 && endX <= x1) {
			// конец сцены: линия с флажком над строками; флажок и линию можно тянуть мышью — менять длительность
			g.fill(endX - 1, y - 2, endX + 1, y + rows * ROW, END);
			g.fill(endX - 3, y - 12, endX + 4, y - 3, END);
			g.fill(endX - 1, y - 10, endX + 2, y - 5, 0xFFFFFFFF);
		}
		if (CutsceneEditor.hasSelection()) {
			int a = Math.max(x0, tickToX(CutsceneEditor.selFrom, w)), b = Math.min(x1, tickToX(CutsceneEditor.selTo, w) + 1);
			if (b > a) {
				g.fill(a, y, b, y + rows * ROW, 0x4060B0FF);
				g.fill(a, y, a + 1, y + rows * ROW, 0xFF60B0FF);
				g.fill(b - 1, y, b, y + rows * ROW, 0xFF60B0FF);
			}
			int n = CutsceneEditor.selTo - CutsceneEditor.selFrom + 1;
			String sel = "выделено " + CutsceneEditor.selFrom + "–" + CutsceneEditor.selTo + " (" + n + " т, " + String.format("%.1f", n / 20f) + " с) · Del — убрать, Esc — снять";
			g.text(font, sel, 6 + CutsceneEditor.Tool.values().length * (TOOL_W + 2) + 8, top - 11, 0xFF9CCBFF);
		}
		int row = 0;
		label(g, font, y, row, "Камера");
		for (CameraKey k : scene.camera()) {
			if (!visible(k.tick())) continue;
			int kx = tickToX(k.tick(), w);
			if (kx < x0 || kx > x1) continue;
			if (k.tick() == CutsceneEditor.selectedKey) g.fill(kx - 4, y + row * ROW + 1, kx + 4, y + row * ROW + ROW - 1, 0xFFFFFFFF);
			diamond(g, kx, y + row * ROW + ROW / 2, k.along() ? 0xFF40E0D0 : CAMERA);
		}
		row++;
		List<ActorTrack> actors = scene.actors();
		for (int i = 0; i < actors.size(); i++, row++) {
			ActorTrack a = actors.get(i);
			boolean sel = i == Math.min(CutsceneEditor.selectedActor, actors.size() - 1);
			label(g, font, y, row, (sel ? "> " : "") + a.character().getPath());
			for (Clip c : a.clips()) bar(g, w, c.start(), c.end(), y + row * ROW + 2, y + row * ROW + ROW - 2, c.muted() ? MUTED : CLIP);
		}
		label(g, font, y, row, "Реплики");
		for (LineKey k : scene.lines()) {
			bar(g, w, k.tick(), k.tick() + k.duration(), y + row * ROW + 3, y + row * ROW + ROW - 3, 0xFF60C060);
			handle(g, w, k.tick() + k.duration(), y + row * ROW + 3, y + row * ROW + ROW - 3);
		}
		row++;
		label(g, font, y, row, "Звуки");
		for (SoundKey k : scene.sounds()) mark(g, w, k.tick(), y + row * ROW, 0xFFE08040);
		row++;
		label(g, font, y, row, "Музыка");
		for (MusicKey k : scene.music()) {
			bar(g, w, k.tick(), k.endIn(scene.length()), y + row * ROW + 3, y + row * ROW + ROW - 3, 0xFFB070E0);
			handle(g, w, k.endIn(scene.length()), y + row * ROW + 3, y + row * ROW + ROW - 3);
		}
		row++;
		label(g, font, y, row, "Действия");
		for (ActionKey k : scene.actions()) mark(g, w, k.tick(), y + row * ROW, 0xFFE06060);
		row++;
		label(g, font, y, row, "Свет");
		for (LightKey k : scene.light()) {
			if (!visible(k.tick())) continue;
			// ромбик серый от тёмного (−1) через средний (0) к светлому (1)
			int shade = 40 + (int) ((k.level() + 1f) * 107);
			diamond(g, tickToX(k.tick(), w), y + row * ROW + ROW / 2, 0xFF000000 | shade << 16 | shade << 8 | shade);
		}
		if (visible(CutsceneEditor.cursor)) {
			int cx = tickToX(CutsceneEditor.cursor, w);
			if (cx >= x0 && cx <= x1) g.fill(cx, y - 2, cx + 1, y + rows * ROW, CURSOR);
		}
	}

	private static void label(GuiGraphicsExtractor g, Font font, int y, int row, String text) {
		g.fill(0, y + row * ROW, LABEL - 2, y + row * ROW + ROW, 0xC0101418);
		g.text(font, font.substrByWidth(net.minecraft.network.chat.Component.literal(text), LABEL - 8).getString(), 6, y + row * ROW + 2, 0xFFD0D0D0);
	}

	/** Светлая ручка на правом краю полосы: за неё тянут длительность. */
	private static void handle(GuiGraphicsExtractor g, int w, int tick, int y0, int y1) {
		if (!visible(tick)) return;
		int x = tickToX(tick, w);
		if (x < LABEL || x > x1(w)) return;
		g.fill(x - 2, y0 - 1, x + 1, y1 + 1, 0xFFFFFFFF);
	}

	private static void bar(GuiGraphicsExtractor g, int w, int from, int to, int y0, int y1, int color) {
		int a = Math.max(LABEL, tickToX(from, w)), b = Math.min(x1(w), Math.max(tickToX(from, w) + 2, tickToX(to, w)));
		if (b <= a) return;
		g.fill(a, y0, b, y1, color);
	}

	private static void mark(GuiGraphicsExtractor g, int w, int tick, int top, int color) {
		if (!visible(tick)) return;
		int cx = tickToX(tick, w);
		if (cx < LABEL || cx > x1(w)) return;
		g.fill(cx - 1, top + 2, cx + 1, top + ROW - 2, color);
	}

	private static void diamond(GuiGraphicsExtractor g, int cx, int cy, int color) {
		g.fill(cx - 1, cy - 3, cx + 1, cy + 3, color);
		g.fill(cx - 3, cy - 1, cx + 3, cy + 1, color);
	}
}
