package com.smeshariki.behavior.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.smeshariki.behavior.cutscene.CutsceneAction;
import com.smeshariki.behavior.cutscene.CutsceneList;
import com.smeshariki.behavior.cutscene.CutsceneListRequest;
import com.smeshariki.ui.ListScreen;
import com.smeshariki.ui.PromptScreen;
import com.smeshariki.ui.Ui;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Режим управления кат-сценами (клавиша N открывает и закрывает): реестр сцен с кнопками «Играть», «Править», «Удалить». Список присылает сервер
 * ({@link CutsceneList}); кнопки шлют те же команды, что и чат, так что работают у тех, кому они доступны. Удаление — в два нажатия; удалить можно только сцену,
 * записанную в мир (сцена из датапака — файл, её убирают руками).
 */
final class CutsceneManagerScreen extends ListScreen {
	private static final int ROW = 22, W_MAX = 420;
	private List<CutsceneList.Entry> entries = new ArrayList<>();
	private boolean loaded;
	/** Итог последнего действия от сервера (создана, удалена, не удалось почему). */
	private String message = "";
	@Nullable
	private Identifier confirmDelete;

	CutsceneManagerScreen() {
		super(Component.translatable("gui.behaviormod.cutscene_manager.kat_stseny"));
	}

	static void toggle() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() instanceof CutsceneManagerScreen) {
			mc.gui.setScreen(null);
		} else if (mc.player != null && mc.gui.screen() == null && !CutsceneClient.active()) {
			mc.gui.setScreen(new CutsceneManagerScreen());
		}
	}

	/** Пришёл реестр. */
	static void received(CutsceneList list) {
		if (Minecraft.getInstance().gui.screen() instanceof CutsceneManagerScreen screen) screen.setEntries(list);
	}

	private void setEntries(CutsceneList list) {
		entries = new ArrayList<>(list.entries());
		if (!list.message().isEmpty()) message = list.message();
		loaded = true;
		rebuildWidgets();
	}

	private int visibleRows() {
		return Math.max(3, (height - 90) / ROW);
	}

	private int w() {
		return listWidth(W_MAX);
	}

	private int left() {
		return (width - w()) / 2;
	}

	@Override
	protected void init() {
		if (!loaded) ClientPlayNetworking.send(new CutsceneListRequest());
		int x = left(), y = 30;
		addRenderableWidget(Button.builder(Component.translatable("gui.behaviormod.cutscene_manager.novaya_stsena"), b -> PromptScreen.open(I18n.get("gui.behaviormod.cutscene_manager.imya_novoy_stseny"), I18n.get("gui.behaviormod.cutscene_manager.naprimer_intro_ili_intro"), "", name -> {
			message = "";
			ClientPlayNetworking.send(new CutsceneAction(CutsceneAction.CREATE, name));
		})).bounds(x, y - 6, 110, 18).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.behaviormod.common.obnovit"), b -> ClientPlayNetworking.send(new CutsceneListRequest())).bounds(x + 114, y - 6, 70, 18).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.behaviormod.cutscene_manager.zakryt_n"), b -> onClose()).bounds(x + w() - 90, y - 6, 90, 18).build());
		int visible = visibleRows();
		bar.layout(x + w() + 3, y + 22, visible * ROW - 2, entries.size(), visible);
		int rows = Math.min(visible, entries.size() - bar.scroll);
		for (int i = 0; i < rows; i++) {
			CutsceneList.Entry e = entries.get(bar.scroll + i);
			int ry = y + 22 + i * ROW;
			boolean asking = e.id().equals(confirmDelete);
			Button play = Button.builder(Component.translatable("gui.behaviormod.cutscene_manager.igrat"), b -> command(CutsceneAction.PLAY, e)).bounds(x + w() - 192, ry, 60, 18).build();
			play.active = e.problem().isEmpty();
			addRenderableWidget(play);
			addRenderableWidget(Button.builder(Component.translatable("gui.behaviormod.cutscene_manager.pravit"), b -> command(CutsceneAction.EDIT, e)).bounds(x + w() - 128, ry, 60, 18).build());
			Button delete = Button.builder(Component.literal(asking ? I18n.get("gui.behaviormod.cutscene_manager.tochno") : I18n.get("gui.uilib.delete")), b -> {
				if (!e.id().equals(confirmDelete)) {
					confirmDelete = e.id();
				} else {
					confirmDelete = null;
					// окно остаётся: сервер пришлёт новый список и итог
					message = "";
					ClientPlayNetworking.send(new CutsceneAction(CutsceneAction.DELETE, e.id().toString()));
				}
				rebuildWidgets();
			}).bounds(x + w() - 64, ry, 64, 18).build();
			delete.active = e.world();
			addRenderableWidget(delete);
		}
	}

	/** Править или играть: окно закрывается (откроется редактор или пойдёт сцена), ошибку сервер напишет в чат. */
	private void command(int kind, CutsceneList.Entry e) {
		Minecraft.getInstance().gui.setScreen(null);
		ClientPlayNetworking.send(new CutsceneAction(kind, e.id().toString()));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		int x = left();
		g.text(font, I18n.get("gui.behaviormod.cutscene_manager.kat_stseny") + (loaded ? " (" + entries.size() + ")" : ""), x, 14, Ui.TEXT);
		if (!loaded) g.text(font, I18n.get("gui.behaviormod.cutscene_manager.zagruzka"), x, 56, Ui.HINT);
		else if (entries.isEmpty()) g.text(font, I18n.get("gui.behaviormod.cutscene_manager.stsen_net_novaya_stsena"), x, 56, Ui.HINT);
		int rows = Math.min(visibleRows(), entries.size() - bar.scroll);
		for (int i = 0; i < rows; i++) {
			CutsceneList.Entry e = entries.get(bar.scroll + i);
			int ry = 30 + 22 + i * ROW;
			String name = e.id().getNamespace().equals("behaviormod") ? e.id().getPath() : e.id().toString();
			g.text(font, font.plainSubstrByWidth(name, w() - 230), x + 2, ry + 1, Ui.TEXT);
			if (e.problem().isEmpty()) g.text(font, I18n.get("gui.behaviormod.cutscene_manager.1f_s_s", String.format("%.1f", e.length() / 20f), e.world() ? I18n.get("gui.behaviormod.cutscene_manager.mir") : I18n.get("gui.behaviormod.cutscene_manager.fayl")), x + 2, ry + 10, 0xFF808890);
			else g.text(font, font.plainSubstrByWidth("⚠ " + e.problem(), w() - 200), x + 2, ry + 10, 0xFFFF7070);
		}
		if (!message.isEmpty()) {
			boolean bad = message.contains("не ") || message.contains("нет") || message.contains("уже") || message.contains("Сначала") || message.startsWith("Имя");
			g.text(font, font.plainSubstrByWidth(message, w()), x, height - 26, bad ? 0xFFFF8080 : 0xFF80E080);
		}
		if (entries.size() > visibleRows()) g.text(font, I18n.get("gui.behaviormod.cutscene_manager.koleso_prokrutka"), x + w() - 100, height - 14, 0xFF808890);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (BehaviorModClient.MANAGER.matches(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}
}
