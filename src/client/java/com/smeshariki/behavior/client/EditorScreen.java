package com.smeshariki.behavior.client;

import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.smeshariki.behavior.cutscene.CameraKey;
import com.smeshariki.behavior.cutscene.Cutscene;
import com.smeshariki.behavior.cutscene.Easings;
import com.smeshariki.ui.PromptScreen;
import com.smeshariki.ui.Ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Окно редактора поверх мира: ничего не затемняет и не ставит игру на паузу, нужно ради курсора. Левая кнопка по полосе времени ставит курсор времени
 * (тянуть — перемотка), по ромбику кадра камеры — выбирает кадр и открывает его параметры; правая кнопка вне полосы поворачивает камеру; колесо — масштаб времени
 * (Ctrl — прокрутка). Клавиши обрабатывает {@link CutsceneEditor#onKey}, окно рисует полоса {@link TimelineHud}.
 */
final class EditorScreen extends Screen {
	private static final int PANEL_W = 222, PANEL_H = 114;
	private boolean looking, scrubbing, panning, selecting;
	private int selAnchor;
	/** Виджеты окошка кадра: пока идёт пуск (P), окошко с кривыми скрыто целиком. */
	private final java.util.List<net.minecraft.client.gui.components.AbstractWidget> keyWidgets = new java.util.ArrayList<>();
	/** Для каких кадров построены кнопки окошка: выбор сменили (Esc, Ctrl+A, ПКМ) — кнопки строятся заново, чтобы не действовать на прежние кадры. */
	private List<Integer> builtTicks = List.of();
	/** Перетаскиваемая отметка: строка полосы и её текущий тик; −1 — не тянем. */
	private int dragRow = -1, dragTick;
	private boolean dragStarted;
	/** Тянем правый край полосы (длительность), а не саму полосу; и за какое место полосы взяли (тиков от её начала). */
	private boolean dragResize;
	private int dragGrab;
	/** Тянем фиолетовую линию конца сцены (длительность). */
	private boolean draggingEnd;

	EditorScreen() {
		super(Component.translatable("gui.behaviormod.editor.redaktor_stseny"));
	}

	void refresh() {
		rebuildWidgets();
	}

	private int panelX() {
		return width - PANEL_W - 8;
	}

	private int panelY() {
		Cutscene s = CutsceneEditor.scene;
		// не выше верхних кнопок (они в строке y = 6..24)
		return Math.max(topBottom, height - (s == null ? 0 : TimelineHud.stripH(s)) - PANEL_H - 16);
	}

	private int topX = 6, topY = 6, topBottom = 30;

	private void topButton(String label, Runnable action) {
		int w = Math.max(40, font.width(label) + 14);
		if (topX > 6 && topX + w > width - 6) {
			topX = 6;
			topY += 20;
		}
		addRenderableWidget(Button.builder(Component.literal(label), b -> action.run()).bounds(topX, topY, w, 18).build());
		topX += w + 4;
	}

	@Override
	protected void init() {
		// окно открыто — значит режим панели; Esc или клик по миру вернёт камеру
		CutsceneEditor.focused = false;
		// верхний ряд: ширина кнопки по тексту (текст не скроллится: скролл рисуется через обрезку, а вне экрана она нулевой ширины и игра падает),
		// не помещается в экран — переносим на следующую строку; окно кадра встаёт ниже
		topX = 6;
		topY = 6;
		topButton(I18n.get("gui.behaviormod.editor.kamera_esc"), CutsceneEditor::focus);
		topButton(I18n.get("gui.behaviormod.editor.sohranit_o"), CutsceneEditor::save);
		topButton(I18n.get("gui.behaviormod.editor.vyyti_backspace"), CutsceneEditor::requestExit);
		topButton(CutsceneEditor.lookThrough ? I18n.get("gui.behaviormod.editor.svobodnaya_kamera_g") : I18n.get("gui.behaviormod.editor.vzglyad_iz_stseny_g"), () -> CutsceneEditor.setLookThrough(!CutsceneEditor.lookThrough));
		topButton(CutsceneEditor.barsLabel(), CutsceneEditor::cycleBars);
		topButton(CutsceneEditor.distanceLabel(), CutsceneEditor::cycleDistance);
		topBottom = topY + 18 + 6;
		keyWidgets.clear();
		// кадры, над которыми работает окошко: один выбранный (ПКМ) или все в выделенной области; кнопки ниже действуют на всех сразу
		List<Integer> ticks = CutsceneEditor.selectedKeyTicks();
		builtTicks = ticks;
		if (ticks.isEmpty()) return;
		CameraKey key = CutsceneEditor.keyAt(ticks.get(0));
		if (key == null) return;
		boolean many = ticks.size() > 1;
		int tick = key.tick(), x = panelX() + 6, y = panelY() + 14;
		Identifier common = CutsceneEditor.commonEase(ticks);
		boolean along = ticks.stream().map(CutsceneEditor::keyAt).allMatch(k -> k != null && k.along());
		if (many) {
			keyWidgets.add(addRenderableWidget(Button.builder(Component.literal(I18n.get("gui.behaviormod.editor.udalit") + ticks.size() + I18n.get("gui.behaviormod.editor.kadrov")), b -> CutsceneEditor.deleteKeys(ticks)).bounds(x, y, 210, 16).build()));
		} else {
			keyWidgets.add(addRenderableWidget(Button.builder(Component.translatable("gui.behaviormod.editor.prosmotr"), b -> CutsceneEditor.viewKey(tick)).bounds(x, y, 64, 16).build()));
			keyWidgets.add(addRenderableWidget(Button.builder(Component.translatable("gui.behaviormod.editor.obnovit_vid"), b -> CutsceneEditor.refixKey(tick)).bounds(x + 68, y, 80, 16).build()));
			keyWidgets.add(addRenderableWidget(Button.builder(Component.translatable("gui.uilib.delete"), b -> CutsceneEditor.deleteKey(tick)).bounds(x + 152, y, 58, 16).build()));
		}
		keyWidgets.add(addRenderableWidget(Button.builder(Component.literal("<"), b -> CutsceneEditor.cycleEaseMany(ticks, -1)).bounds(x, y + 20, 18, 16).build()));
		keyWidgets.add(addRenderableWidget(Button.builder(Component.literal(common == null ? I18n.get("gui.behaviormod.editor.raznye") : shortName(common)), b -> CutsceneEditor.cycleEaseMany(ticks, 1)).bounds(x + 20, y + 20, 100, 16).build()));
		keyWidgets.add(addRenderableWidget(Button.builder(Component.literal(">"), b -> CutsceneEditor.cycleEaseMany(ticks, 1)).bounds(x + 122, y + 20, 18, 16).build()));
		keyWidgets.add(addRenderableWidget(Button.builder(Component.literal(along ? I18n.get("gui.behaviormod.editor.vzglyad_vdol_puti") : I18n.get("gui.behaviormod.editor.vzglyad_po_kadram")), b -> CutsceneEditor.toggleAlongMany(ticks)).bounds(x, y + 40, 148, 16).build()));
		keyWidgets.add(addRenderableWidget(Button.builder(Component.translatable("gui.behaviormod.editor.beze"), b -> PromptScreen.open(I18n.get("gui.behaviormod.editor.svoya_funktsiya_dlya") + (many ? ticks.size() + I18n.get("gui.behaviormod.editor.kadrov") : I18n.get("gui.behaviormod.editor.kadra") + tick), I18n.get("gui.behaviormod.editor.x1_y1_x2_y2"), "0.25 0.1 0.25 1", text -> CutsceneEditor.addBezier(ticks, text))).bounds(x + 144, y + 20, 66, 16).build()));
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
		List<Integer> ticks = CutsceneEditor.selectedKeyTicks();
		if (!ticks.equals(builtTicks) && !selecting) rebuildWidgets();
		CameraKey key = ticks.isEmpty() ? null : CutsceneEditor.keyAt(ticks.get(0));
		boolean many = ticks.size() > 1;
		// во время пуска (P) и пока тянут область ПКМ окошко скрыто: не загораживает картинку
		boolean showPanel = !CutsceneEditor.playing && !selecting;
		for (net.minecraft.client.gui.components.AbstractWidget w : keyWidgets) w.visible = showPanel;
		if (key != null && showPanel) {
			int px = panelX(), py = panelY();
			g.fill(px, py, px + PANEL_W, py + PANEL_H, 0xD0101418);
			g.outline(px, py, PANEL_W, PANEL_H, 0xFF5FA8E8);
			g.text(font, many ? I18n.get("gui.behaviormod.editor.kadrov_kamery") + ticks.size() + I18n.get("gui.behaviormod.editor.tiki") + ticks.get(0) + "–" + ticks.get(ticks.size() - 1) + ")" : I18n.get("gui.behaviormod.editor.kadr_kamery_tik") + key.tick() + I18n.get("gui.behaviormod.editor.1f_s", String.format("%.1f", key.tick() / 20f)), px + 6, py + 4, Ui.TEXT);
			Identifier commonEase = CutsceneEditor.commonEase(ticks);
			if (commonEase == null) {
				// у выбранных кадров разные функции: график не рисуем, кнопки назначат одну всем
				g.text(font, I18n.get("gui.behaviormod.editor.u_kadrov_raznye_funktsii"), px + 6, py + 76, 0xFFD0D0D0);
				g.text(font, I18n.get("gui.behaviormod.editor.knopki_nizhe_zadadut_odnu"), px + 6, py + 86, Ui.DIM);
				if (CutsceneEditor.help) help(g);
				super.extractRenderState(g, mouseX, mouseY, delta);
				return;
			}
			// график функции: как камера идёт от этого кадра к следующему
			Easings.Bezier curve = Easings.resolve(commonEase, CutsceneEditor.scene == null ? java.util.Map.of() : CutsceneEditor.scene.easings());
			int gx = px + 6, gy = py + 74, gw = 56, gh = 34;
			g.fill(gx, gy, gx + gw, gy + gh, 0xFF000000);
			g.outline(gx, gy, gw, gh, 0xFF404850);
			for (int i = 0; i <= gw - 2; i++) {
				float v = curve.apply(i / (float) (gw - 2));
				int yy = gy + gh - 2 - Math.round(Math.max(-0.5f, Math.min(1.5f, v)) * (gh - 4)) - 2;
				if (yy >= gy && yy < gy + gh) g.fill(gx + 1 + i, yy, gx + 2 + i, yy + 1, Ui.ACCENT);
			}
			g.text(font, I18n.get("gui.behaviormod.editor.funktsiya") + shortName(commonEase), px + 68, py + 74, 0xFFD0D0D0);
			g.text(font, "(" + curve.text() + ")", px + 68, py + 83, Ui.DIM);
			if (!many) {
				g.text(font, I18n.get("gui.behaviormod.editor.poz_1f_1f_1f", String.format("%.1f", key.pos().x), String.format("%.1f", key.pos().y), String.format("%.1f", key.pos().z)), px + 68, py + 92, Ui.DIM);
				g.text(font, I18n.get("gui.behaviormod.editor.kurs_0f_naklon_0f", String.format("%.0f", key.yaw()), String.format("%.0f", key.pitch()), String.format("%.0f", key.roll())), px + 68, py + 101, Ui.DIM);
			}
		}
		if (CutsceneEditor.help) help(g);
		super.extractRenderState(g, mouseX, mouseY, delta);
	}

	/** Подсказка F1: все клавиши редактора с теми, что стоят сейчас в настройках управления. */
	private void help(GuiGraphicsExtractor g) {
		Object[][] rows = {
				{I18n.get("gui.behaviormod.editor.vremya"), CutsceneEditor.PLAY, I18n.get("gui.behaviormod.editor.pusk_pauza_vydelennaya_oblas"), CutsceneEditor.BACK, I18n.get("gui.behaviormod.editor.tik_nazad"), CutsceneEditor.FORWARD, I18n.get("gui.behaviormod.editor.tik_vpered_shift_10"), CutsceneEditor.TO_START, I18n.get("gui.behaviormod.editor.v_nachalo"), CutsceneEditor.TO_END, I18n.get("gui.behaviormod.editor.v_konets")},
				{I18n.get("gui.behaviormod.editor.instrumenty"), CutsceneEditor.TOOL_PREV, I18n.get("gui.behaviormod.editor.predyduschiy"), CutsceneEditor.TOOL_NEXT, I18n.get("gui.behaviormod.editor.sleduyuschiy"), CutsceneEditor.USE_TOOL, I18n.get("gui.behaviormod.editor.postavit_na_kursor")},
				{I18n.get("gui.behaviormod.editor.kamera"), CutsceneEditor.CAM_KEY, I18n.get("gui.behaviormod.editor.kadr_seychas"), CutsceneEditor.ALONG, I18n.get("gui.behaviormod.editor.vzglyad_vdol_puti_kadr"), CutsceneEditor.FOLLOW, I18n.get("gui.behaviormod.editor.polet_po_dvizheniyu_w"), CutsceneEditor.AUTO_STOP, I18n.get("gui.behaviormod.editor.avtoostanovka_zapisi_vykl_1"), CutsceneEditor.BARS, I18n.get("gui.behaviormod.editor.kinopolosy_net_2_39"), CutsceneEditor.REC_CAM, I18n.get("gui.behaviormod.editor.zapis_proleta"), CutsceneEditor.LOOK, I18n.get("gui.behaviormod.editor.smotret_iz_stseny"), CutsceneEditor.PREV_KEY, I18n.get("gui.behaviormod.editor.prezhniy_kadr"), CutsceneEditor.NEXT_KEY, I18n.get("gui.behaviormod.editor.sleduyuschiy_kadr")},
				{I18n.get("gui.behaviormod.editor.otmetki"), CutsceneEditor.LINE, I18n.get("gui.behaviormod.editor.replika"), CutsceneEditor.SOUND, I18n.get("gui.behaviormod.editor.zvuk"), CutsceneEditor.MUSIC, I18n.get("gui.behaviormod.editor.saundtrek"), CutsceneEditor.ACTION, I18n.get("gui.behaviormod.editor.deystvie_mira"), CutsceneEditor.LIGHT, I18n.get("gui.behaviormod.editor.svet"), CutsceneEditor.DELETE, I18n.get("gui.behaviormod.editor.ubrat_oblast_vse_v")},
				{I18n.get("gui.behaviormod.editor.klipy"), CutsceneEditor.ACTOR, I18n.get("gui.behaviormod.editor.akter"), CutsceneEditor.MUTE, I18n.get("gui.behaviormod.editor.vkl_vykl"), CutsceneEditor.SHIFT_BACK, I18n.get("gui.behaviormod.editor.nazad"), CutsceneEditor.SHIFT_FORWARD, I18n.get("gui.behaviormod.editor.vpered"), CutsceneEditor.TRIM_START, I18n.get("gui.behaviormod.editor.obrezat_nachalo"), CutsceneEditor.TRIM_END, I18n.get("gui.behaviormod.editor.obrezat_konets")},
				{I18n.get("gui.behaviormod.editor.stsena"), CutsceneEditor.SET_LENGTH, I18n.get("gui.behaviormod.editor.dlina_kursor"), CutsceneEditor.ZOOM_IN, I18n.get("gui.behaviormod.editor.masshtab"), CutsceneEditor.ZOOM_OUT, I18n.get("gui.behaviormod.editor.masshtab_2"), CutsceneEditor.TAKE, I18n.get("gui.behaviormod.editor.dubl_s_kursora"), CutsceneEditor.SAVE, I18n.get("gui.behaviormod.editor.sohranit"), CutsceneEditor.HELP, I18n.get("gui.behaviormod.editor.eta_podskazka")},
		};
		java.util.List<String> lines = new java.util.ArrayList<>();
		for (Object[] row : rows) {
			lines.add("§e" + row[0]);
			for (int i = 1; i + 1 < row.length; i += 2) {
				lines.add("  " + ((net.minecraft.client.KeyMapping) row[i]).getTranslatedKeyMessage().getString() + " — " + row[i + 1]);
			}
		}
		lines.add(I18n.get("gui.behaviormod.editor.emysh_i_ctrl"));
		for (String l : new String[] { I18n.get("gui.behaviormod.editor.shift_tyanut_po_polose"), I18n.get("gui.behaviormod.editor.ctrl_c_ctrl_v"), I18n.get("gui.behaviormod.editor.ctrl_z_ctrl_y"), I18n.get("gui.behaviormod.editor.tyanut_otmetku_po_polose"), I18n.get("gui.behaviormod.editor.pkm_mysh_vzglyad_kamery"), I18n.get("gui.behaviormod.editor.esc_snyat_vydelenie_vyyti") }) lines.add(l);
		int w = 0;
		for (String l : lines) w = Math.max(w, font.width(l.replace("§e", "")));
		int h = lines.size() * 10 + 10, x = 10, y = 10;
		g.fill(x, y, x + w + 16, y + h, 0xE0101418);
		g.outline(x, y, w + 16, h, 0xFF5FA8E8);
		for (int i = 0; i < lines.size(); i++) {
			String l = lines.get(i);
			boolean head = l.startsWith("§e");
			g.text(font, head ? l.substring(2) : l, x + 8, y + 6 + i * 10, head ? Ui.ACCENT : 0xFFE0E0E0);
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			// Esc: сначала снять выделение области, потом вернуться в режим камеры (выйти из редактора — кнопка или Backspace)
			if (!CutsceneEditor.clearSelection()) CutsceneEditor.focus();
			return true;
		}
		if (BehaviorModClient.MANAGER.matches(event)) {
			// N: из редактора в реестр сцен (выход подтверждается как обычно, если есть несохранённое)
			if (CutsceneEditor.requestExit()) CutsceneManagerScreen.toggle();
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
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			int tool = TimelineHud.toolAt(mx, my, height, s);
			if (tool >= 0) {
				CutsceneEditor.setTool(CutsceneEditor.Tool.values()[tool]);
				return true;
			}
		}
		if (!inStrip) {
			if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
				looking = true;
				return true;
			}
			// левый клик по миру (не по панели кадра) возвращает камеру
			if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && !(!CutsceneEditor.selectedKeyTicks().isEmpty() && !CutsceneEditor.playing && mx >= panelX() && my >= panelY() && my < panelY() + PANEL_H)) {
				CutsceneEditor.focus();
				return true;
			}
			return false;
		}
		if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
			panning = true;
			return true;
		}
		if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			// ПКМ по полосе: на кадре камеры — выбрать его (в уже выделенной области — оставить всю область); на пустом месте — тянуть, чтобы выделить область
			if (mx < TimelineHud.LABEL) return true;
			int rrow = TimelineHud.rowAt(my, height, s);
			if (rrow == 0) {
				int near = -1;
				double best = 6;
				for (int t : CutsceneEditor.markTicks(0)) {
					double d = Math.abs(TimelineHud.tickToX(t, width) - mx);
					if (d <= best) {
						best = d;
						near = t;
					}
				}
				if (near >= 0) {
					if (CutsceneEditor.hasSelection() && CutsceneEditor.selectedKeyTicks().contains(near)) CutsceneEditor.refreshScreen();
					else CutsceneEditor.selectKey(near);
					return true;
				}
			}
			selecting = true;
			selAnchor = Math.max(0, Math.round(TimelineHud.xToTick(mx, width)));
			CutsceneEditor.select(selAnchor, selAnchor);
			return true;
		}
		if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
		int row = TimelineHud.rowAt(my, height, s);
		boolean onEnd = mx >= TimelineHud.LABEL && Math.abs(TimelineHud.tickToX(s.length(), width) - mx) <= 4;
		if (onEnd && my < TimelineHud.rowsTop(height, s) && !shiftDown()) {
			// по «флажку» линии конца над строками — всегда берём конец
			draggingEnd = true;
			dragStarted = false;
			return true;
		}
		if (row >= 0 && mx >= TimelineHud.LABEL && !shiftDown()) {
			// полоса реплики или музыки: правый край тянет длительность, тело — переносит целиком
			for (int[] span : CutsceneEditor.markSpans(row)) {
				double xs = TimelineHud.tickToX(span[0], width), xe = Math.max(xs + 3, TimelineHud.tickToX(span[1], width));
				boolean edge = Math.abs(mx - xe) <= 5;
				if (edge || (mx >= xs - 5 && mx <= xe)) {
					dragRow = row;
					dragTick = span[0];
					dragStarted = false;
					dragResize = edge;
					dragGrab = edge ? 0 : Math.round(TimelineHud.xToTick(mx, width)) - span[0];
					return true;
				}
			}
		}
		if (row >= 0 && mx >= TimelineHud.LABEL && !shiftDown()) {
			// отметка под мышью: клик выбирает (для кадра камеры — открывает параметры), тянуть — переносить по времени
			int near = -1;
			double best = 6;
			for (int t : CutsceneEditor.markTicks(row)) {
				double d = Math.abs(TimelineHud.tickToX(t, width) - mx);
				if (d <= best) {
					best = d;
					near = t;
				}
			}
			if (near >= 0) {
				// ЛКМ по отметке — только тянуть (выбирает ПКМ); кадр из выделенной области тянет всю область
				dragRow = row;
				dragTick = near;
				dragStarted = false;
				dragResize = false;
				dragGrab = 0;
				return true;
			}
		}
		if (onEnd && !shiftDown()) {
			// линия конца сцены между отметками: тянуть — менять длительность
			draggingEnd = true;
			dragStarted = false;
			return true;
		}
		if (row >= 1 && row <= s.actors().size() && mx < TimelineHud.LABEL) {
			CutsceneEditor.selectActor(row - 1);
			return true;
		}
		if (mx >= TimelineHud.LABEL) {
			int tick = Math.round(TimelineHud.xToTick(mx, width));
			if (shiftDown()) {
				// Shift + тянуть — выделить область времени
				selecting = true;
				selAnchor = Math.max(0, tick);
				CutsceneEditor.select(selAnchor, selAnchor);
			} else {
				scrubbing = true;
				CutsceneEditor.clearSelection();
				CutsceneEditor.seekTo(tick);
			}
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
		if (draggingEnd) {
			int target = Math.max(1, Math.round(TimelineHud.xToTick(event.x(), width)));
			if (CutsceneEditor.scene != null && target != CutsceneEditor.scene.length()) {
				if (!dragStarted) {
					CutsceneEditor.beginGroup(true);
					dragStarted = true;
				}
				CutsceneEditor.setLength(target);
			}
			return true;
		}
		if (dragRow >= 0) {
			int mouseTick = Math.round(TimelineHud.xToTick(event.x(), width));
			if (dragResize) {
				int end = Math.max(dragTick + 1, mouseTick);
				if (!dragStarted) {
					CutsceneEditor.beginGroup(true);
					dragStarted = true;
				}
				CutsceneEditor.resizeSpan(dragRow, dragTick, end);
				return true;
			}
			int target = Math.max(0, mouseTick - dragGrab);
			if (target != dragTick) {
				if (!dragStarted) {
					CutsceneEditor.beginGroup(true);
					dragStarted = true;
				}
				boolean group = dragRow == 0 && CutsceneEditor.hasSelection() && CutsceneEditor.selectedKeyTicks().size() > 1 && CutsceneEditor.selectedKeyTicks().contains(dragTick);
				if (group ? CutsceneEditor.moveSelectedKeys(target - dragTick) : CutsceneEditor.moveMark(dragRow, dragTick, target)) dragTick = target;
			}
			return true;
		}
		if (selecting) {
			CutsceneEditor.select(selAnchor, Math.round(TimelineHud.xToTick(event.x(), width)));
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
		boolean was = looking || scrubbing || panning || selecting || dragRow >= 0 || draggingEnd;
		if (dragStarted) CutsceneEditor.endGroup();
		if (selecting) CutsceneEditor.finishAreaSelect();
		looking = scrubbing = panning = selecting = false;
		dragRow = -1;
		draggingEnd = false;
		dragStarted = false;
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
		// приближается тот кусок полосы, что под мышью (по горизонтали, на любой высоте над столбцом времени); вне столбца — вокруг курсора времени
		float around = x >= TimelineHud.LABEL && x <= TimelineHud.x1(width) ? TimelineHud.xToTick(x, width) : CutsceneEditor.cursor;
		CutsceneEditor.zoom(scrollY > 0 ? 0.8f : 1.25f, around);
		return true;
	}

	private static boolean shiftDown() {
		Minecraft mc = Minecraft.getInstance();
		return com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT) || com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
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
