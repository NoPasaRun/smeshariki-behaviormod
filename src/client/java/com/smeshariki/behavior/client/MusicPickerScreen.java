package com.smeshariki.behavior.client;

import java.util.List;

import com.smeshariki.ui.ListScreen;
import com.smeshariki.ui.Ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

/**
 * Выбор саундтрека в редакторе: файлы .mp3 из {@code config/behaviormod/music/} кнопками, «Открыть папку», «Звук игры по id…». После выбора спросит громкость,
 * плавность и конец. Мир за окном идёт дальше (время редактора стоит: открыто не окно редактора).
 */
final class MusicPickerScreen extends ListScreen {
	private static final int ROW = 22, W_MAX = 360;
	private List<String> files = MusicFiles.list();
	/** Верх списка файлов: ниже кнопок (они переносятся на вторую строку на узком окне). */
	private int listTop = 64;

	MusicPickerScreen() {
		super(Component.translatable("gui.behaviormod.music_picker.saundtrek"));
	}

	private int w() {
		return listWidth(W_MAX);
	}

	private int visibleRows() {
		return Math.max(3, (height - listTop - 40) / ROW);
	}

	@Override
	protected void init() {
		int w = w(), x = centered(w), rowY = 36, bx = x;
		java.util.List<Component> labels = java.util.List.of(Component.translatable("gui.behaviormod.music_picker.otkryt_papku"), Component.translatable("gui.behaviormod.common.obnovit"),
				Component.translatable("gui.behaviormod.music_picker.zvuk_igry_po_id"), Component.translatable("gui.uilib.cancel"));
		Runnable[] actions = {() -> net.minecraft.util.Util.getPlatform().openPath(MusicFiles.dir()), () -> {
			files = MusicFiles.list();
			rebuildWidgets();
		}, CutsceneEditor::askMusicById, this::onClose};
		for (int i = 0; i < labels.size(); i++) {
			int bw = Math.max(40, font.width(labels.get(i)) + 14);
			if (bx > x && bx + bw > x + w) {
				bx = x;
				rowY += 20;
			}
			Runnable action = actions[i];
			addRenderableWidget(Button.builder(labels.get(i), b -> action.run()).bounds(bx, rowY, bw, 18).build());
			bx += bw + 4;
		}
		listTop = rowY + 26;
		int visible = visibleRows();
		bar.layout(x + w + 3, listTop, visible * ROW - 2, files.size(), visible);
		int rows = Math.min(visible, files.size() - bar.scroll);
		for (int i = 0; i < rows; i++) {
			String name = files.get(bar.scroll + i);
			addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(name, w - 16)), b -> CutsceneEditor.askMusicFile(name)).bounds(x, listTop + i * ROW, w, 18).build());
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		int w = w(), x = centered(w);
		g.text(font, font.plainSubstrByWidth(I18n.get("gui.behaviormod.music_picker.saundtrek_na_tike") + CutsceneEditor.cursor + I18n.get("gui.behaviormod.music_picker.vyberi_fayl_mp3"), w), x, 12, Ui.TEXT);
		g.text(font, font.plainSubstrByWidth(I18n.get("gui.behaviormod.music_picker.papka") + MusicFiles.dir(), w), x, 24, 0xFF808890);
		if (files.isEmpty()) {
			int y = listTop;
			for (var line : font.split(Component.translatable("gui.behaviormod.music_picker.faylov_net_otkryt_papku"), w)) {
				g.text(font, line, x, y, 0xFFFFC060);
				y += 10;
			}
			for (var line : font.split(Component.translatable("gui.behaviormod.music_picker.v_stsene_hranitsya_tolko"), w)) {
				g.text(font, line, x, y + 4, 0xFF808890);
				y += 10;
			}
		}
	}
}
