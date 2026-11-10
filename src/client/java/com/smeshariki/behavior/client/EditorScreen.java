package com.smeshariki.behavior.client;

import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.smeshariki.behavior.cutscene.CameraKey;
import com.smeshariki.behavior.cutscene.Cutscene;
import com.smeshariki.behavior.cutscene.Easings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Окно редактора поверх мира: ничего не затемняет и не ставит игру на паузу, нужно ради курсора. Левая кнопка по полосе времени ставит курсор времени
 * (тянуть — перемотка), по ромбику кадра камеры — выбирает кадр и открывает его параметры; правая кнопка вне полосы поворачивает камеру; колесо — масштаб времени
 * (Ctrl — прокрутка). Клавиши обрабатывает {@link CutsceneEditor#onKey}, окно рисует полоса {@link TimelineHud}.
 */
final class EditorScreen extends Screen {
	private static final int PANEL_W = 236, PANEL_H = 112;
	private boolean looking, scrubbing, panning;

	EditorScreen() {
		super(Component.literal("Редактор сцены"));
	}

	void refresh() {
		rebuildWidgets();
	}

	private int panelX() {
		return width - PANEL_W - 8;
	}

	private int panelY() {
		Cutscene s = CutsceneEditor.scene;
		return height - (s == null ? 0 : TimelineHud.stripH(s)) - PANEL_H - 16;
	}

	@Override
	protected void init() {
		CameraKey key = CutsceneEditor.keyAt(CutsceneEditor.selectedKey);
		if (key == null) return;
		int tick = key.tick(), x = panelX() + 6, y = panelY() + 16;
		addRenderableWidget(Button.builder(Component.literal("Просмотр"), b -> CutsceneEditor.viewKey(tick)).bounds(x, y, 70, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Обновить вид"), b -> CutsceneEditor.refixKey(tick)).bounds(x + 74, y, 84, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Удалить"), b -> CutsceneEditor.deleteKey(tick)).bounds(x + 162, y, 62, 18).build());
		addRenderableWidget(Button.builder(Component.literal("<"), b -> CutsceneEditor.cycleEase(tick, -1)).bounds(x, y + 24, 20, 18).build());
		addRenderableWidget(Button.builder(Component.literal(shortName(key.ease())), b -> CutsceneEditor.cycleEase(tick, 1)).bounds(x + 22, y + 24, 112, 18).build());
		addRenderableWidget(Button.builder(Component.literal(">"), b -> CutsceneEditor.cycleEase(tick, 1)).bounds(x + 136, y + 24, 20, 18).build());
		addRenderableWidget(Button.builder(Component.literal("+ Безье"), b -> PromptScreen.open("Своя функция для кадра " + tick, "x1 y1 x2 y2 [имя] — как cubic-bezier в CSS, x от 0 до 1", "0.25 0.1 0.25 1", text -> CutsceneEditor.addBezier(tick, text))).bounds(x + 160, y + 24, 64, 18).build());
	}

	private static String shortName(Identifier id) {
		return id.getNamespace().equals("behaviormod") ? id.getPath() : id.toString();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		// мир остаётся виден: ни затемнения, ни размытия
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		CameraKey key = CutsceneEditor.keyAt(CutsceneEditor.selectedKey);
		if (key != null) {
			int px = panelX(), py = panelY();
			g.fill(px, py, px + PANEL_W, py + PANEL_H, 0xD0101418);
			g.outline(px, py, PANEL_W, PANEL_H, 0xFF5FA8E8);
			g.text(font, "Кадр камеры, тик " + key.tick() + String.format(" (%.1f с)", key.tick() / 20f), px + 6, py + 5, 0xFFFFFFFF);
			// график функции: как камера идёт от этого кадра к следующему
			Easings.Bezier curve = Easings.resolve(key.ease(), CutsceneEditor.scene == null ? java.util.Map.of() : CutsceneEditor.scene.easings());
			int gx = px + 6, gy = py + 62, gw = 60, gh = 40;
			g.fill(gx, gy, gx + gw, gy + gh, 0xFF000000);
			g.outline(gx, gy, gw, gh, 0xFF404850);
			for (int i = 0; i <= gw - 2; i++) {
				float v = curve.apply(i / (float) (gw - 2));
				int yy = gy + gh - 2 - Math.round(Math.max(-0.5f, Math.min(1.5f, v)) * (gh - 4)) - 2;
				if (yy >= gy && yy < gy + gh) g.fill(gx + 1 + i, yy, gx + 2 + i, yy + 1, 0xFFFFD040);
			}
			g.text(font, "функция: " + shortName(key.ease()), px + 74, py + 64, 0xFFD0D0D0);
			g.text(font, "(" + curve.text() + ")", px + 74, py + 76, 0xFF909090);
			g.text(font, String.format("поз %.1f %.1f %.1f", key.pos().x, key.pos().y, key.pos().z), px + 74, py + 88, 0xFF909090);
			g.text(font, String.format("курс %.0f° наклон %.0f° крен %.0f°", key.yaw(), key.pitch(), key.roll()), px + 74, py + 100, 0xFF909090);
		}
		super.extractRenderState(g, mouseX, mouseY, delta);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			CutsceneEditor.requestExit();
			return true;
		}
		if (CutsceneEditor.onKey(event)) return true;
		// Tab, стрелки и Enter окна не нужны: фокус по кнопкам клавишами не ходит
		return event.key() == GLFW.GLFW_KEY_TAB || super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		Cutscene s = CutsceneEditor.scene;
		if (s == null) return false;
		double mx = event.x(), my = event.y();
		boolean inStrip = my >= TimelineHud.stripTop(height, s);
		int button = event.button();
		if (!inStrip) {
			if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) looking = true;
			return button == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
		}
		if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
			panning = true;
			return true;
		}
		if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
		int row = TimelineHud.rowAt(my, height, s);
		if (row == 0 && mx >= TimelineHud.LABEL) {
			CameraKey near = null;
			double best = 6;
			for (CameraKey k : s.camera()) {
				double d = Math.abs(TimelineHud.tickToX(k.tick(), width) - mx);
				if (d <= best) {
					best = d;
					near = k;
				}
			}
			if (near != null) {
				CutsceneEditor.seekTo(near.tick());
				CutsceneEditor.selectKey(near.tick());
				return true;
			}
		} else if (row >= 1 && row <= s.actors().size() && mx < TimelineHud.LABEL) {
			CutsceneEditor.selectActor(row - 1);
			return true;
		}
		if (mx >= TimelineHud.LABEL) {
			scrubbing = true;
			CutsceneEditor.seekTo(Math.round(TimelineHud.xToTick(mx, width)));
		}
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (looking) {
			double scale = Minecraft.getInstance().getWindow().getGuiScale();
			double s = Minecraft.getInstance().options.sensitivity().get() * 0.6 + 0.2;
			double k = s * s * s * 8.0 * scale;
			CutsceneEditor.look(dx * k, dy * k);
			return true;
		}
		if (scrubbing) {
			CutsceneEditor.seekTo(Math.round(TimelineHud.xToTick(event.x(), width)));
			return true;
		}
		if (panning) {
			CutsceneEditor.pan((float) (-dx / (TimelineHud.x1(width) - TimelineHud.LABEL) * CutsceneEditor.viewLen));
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		boolean was = looking || scrubbing || panning;
		looking = scrubbing = panning = false;
		return was || super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		Cutscene s = CutsceneEditor.scene;
		if (s == null || scrollY == 0) return false;
		Minecraft mc = Minecraft.getInstance();
		boolean ctrl = com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL) || com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL);
		if (ctrl) {
			CutsceneEditor.pan((float) (-scrollY * CutsceneEditor.viewLen * 0.1));
			return true;
		}
		float around = y >= TimelineHud.stripTop(height, s) && x >= TimelineHud.LABEL ? TimelineHud.xToTick(x, width) : CutsceneEditor.cursor;
		CutsceneEditor.zoom(scrollY > 0 ? 0.8f : 1.25f, around);
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void onClose() {
		CutsceneEditor.requestExit();
	}
}
