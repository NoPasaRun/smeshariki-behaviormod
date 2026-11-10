package com.smeshariki.behavior.client;

import java.util.List;

import com.smeshariki.behavior.cutscene.ActionKey;
import com.smeshariki.behavior.cutscene.ActorTrack;
import com.smeshariki.behavior.cutscene.CameraKey;
import com.smeshariki.behavior.cutscene.Clip;
import com.smeshariki.behavior.cutscene.Cutscene;
import com.smeshariki.behavior.cutscene.LightKey;
import com.smeshariki.behavior.cutscene.LineKey;
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
		return 1 + s.actors().size() + 4;
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
		String state = String.format("%s  тик %d/%d (%.1f с)  %s%s%s%s", CutsceneEditor.id, CutsceneEditor.cursor, scene.length(), CutsceneEditor.cursor / 20f, CutsceneEditor.rollText(),
				CutsceneEditor.playing ? "  ▶" : "", CutsceneEditor.recordingCamera ? "  ● камера" : "", CutsceneEditor.armed ? "  ◌ ждёт движения" : "");
		g.text(font, state + (CutsceneEditor.dirty ? "  *не сохранено" : ""), 6, top + 2, CutsceneEditor.dirty ? 0xFFFFC060 : 0xFFFFFFFF);
		String help = "P пуск · Enter кадр · R пролёт · B дубль · O сохранить · ПКМ+мышь взгляд · колесо масштаб";
		g.text(font, help, Math.max(6 + font.width(state) + 20, w - 8 - font.width(help)), top + 2, 0xFF808890);
		if (System.currentTimeMillis() < CutsceneEditor.messageUntil) g.text(font, font.substrByWidth(net.minecraft.network.chat.Component.literal(CutsceneEditor.message), w - 12).getString(), 6, top - 11, 0xFFB0E0FF);
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
		if (endX >= x0 && endX <= x1) g.fill(endX, y - 2, endX + 1, y + rows * ROW, END);
		int row = 0;
		label(g, font, y, row, "Камера");
		for (CameraKey k : scene.camera()) {
			if (!visible(k.tick())) continue;
			int kx = tickToX(k.tick(), w);
			if (kx < x0 || kx > x1) continue;
			if (k.tick() == CutsceneEditor.selectedKey) g.fill(kx - 4, y + row * ROW + 1, kx + 4, y + row * ROW + ROW - 1, 0xFFFFFFFF);
			diamond(g, kx, y + row * ROW + ROW / 2, CAMERA);
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
		for (LineKey k : scene.lines()) bar(g, w, k.tick(), k.tick() + k.duration(), y + row * ROW + 3, y + row * ROW + ROW - 3, 0xFF60C060);
		row++;
		label(g, font, y, row, "Звуки");
		for (SoundKey k : scene.sounds()) mark(g, w, k.tick(), y + row * ROW, 0xFFE08040);
		row++;
		label(g, font, y, row, "Действия");
		for (ActionKey k : scene.actions()) mark(g, w, k.tick(), y + row * ROW, 0xFFE06060);
		row++;
		label(g, font, y, row, "Свет");
		for (LightKey k : scene.light()) {
			if (!visible(k.tick())) continue;
			int shade = 255 - (int) (k.darkness() * 200);
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
