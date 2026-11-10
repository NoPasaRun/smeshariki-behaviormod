package com.smeshariki.behavior.client;

import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Строка ввода для редактора сцены: Enter принимает, Esc отменяет; после закрытия возвращает в окно, откуда открыли. Мир за окном идёт дальше. */
final class PromptScreen extends Screen {
	private final String hint;
	private final String initial;
	private final Consumer<String> accept;
	@Nullable
	private final Screen parent;
	private EditBox box;

	private PromptScreen(@Nullable Screen parent, String title, String hint, String initial, Consumer<String> accept) {
		super(Component.literal(title));
		this.parent = parent;
		this.hint = hint;
		this.initial = initial;
		this.accept = accept;
	}

	static void open(String title, String hint, String initial, Consumer<String> accept) {
		Minecraft mc = Minecraft.getInstance();
		mc.gui.setScreen(new PromptScreen(mc.gui.screen(), title, hint, initial, accept));
	}

	@Override
	protected void init() {
		int w = Math.min(360, width - 24);
		box = new EditBox(font, (width - w) / 2, height / 2 - 10, w, 20, title);
		box.setMaxLength(256);
		box.setValue(initial);
		addRenderableWidget(box);
		setInitialFocus(box);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
			String value = box.getValue().trim();
			onClose();
			if (!value.isEmpty()) accept.accept(value);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.centeredText(font, title, width / 2, height / 2 - 34, 0xFFFFFFFF);
		g.centeredText(font, hint, width / 2, height / 2 + 18, 0xFFA0A0A0);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().gui.setScreen(parent);
	}
}
