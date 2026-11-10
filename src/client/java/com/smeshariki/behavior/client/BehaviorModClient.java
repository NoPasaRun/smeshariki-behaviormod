package com.smeshariki.behavior.client;

import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.smeshariki.behavior.BehaviorMod;
import com.smeshariki.behavior.dialog.DialogChoice;
import com.smeshariki.behavior.dialog.DialogReplies;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class BehaviorModClient implements ClientModInitializer {
	static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(BehaviorMod.id("main"));
	static final KeyMapping TALK = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + BehaviorMod.NAMESPACE + ".talk", GLFW.GLFW_KEY_K, CATEGORY));

	/** Последний список реплик от сервера. */
	static List<Component> replies = List.of();
	static int version;
	/** Список реплик открыт внизу экрана: цифры произносят реплику, а не меняют слот панели. */
	static boolean open;

	@Override
	public void onInitializeClient() {
		CutsceneClient.init();
		ClientPlayNetworking.registerGlobalReceiver(DialogReplies.TYPE, (msg, ctx) -> {
			replies = msg.replies();
			version = msg.version();
			if (replies.isEmpty()) open = false;
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			replies = List.of();
			version = 0;
			open = false;
		});
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, BehaviorMod.id("talk"), TalkIcon::extract);
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, BehaviorMod.id("replies"), RepliesHud::extract);
		// в начале тика, до ванильной обработки клавиш: нажатие цифры надо забрать раньше, чем она переключит слот панели
		ClientTickEvents.START_CLIENT_TICK.register(BehaviorModClient::tick);
	}

	private static void tick(Minecraft mc) {
		boolean pressed = false;
		while (TALK.consumeClick()) pressed = true;
		if (mc.player == null || mc.gui.screen() != null) return;
		if (replies.isEmpty()) open = false;
		if (pressed) open = !open && !replies.isEmpty();
		if (!open) return;
		KeyMapping[] slots = mc.options.keyHotbarSlots;
		for (int i = 0; i < slots.length; i++) {
			boolean hit = false;
			while (slots[i].consumeClick()) hit = true;
			if (!hit || i >= replies.size()) continue;
			ClientPlayNetworking.send(new DialogChoice(i, version));
			open = false;
			return;
		}
	}
}
