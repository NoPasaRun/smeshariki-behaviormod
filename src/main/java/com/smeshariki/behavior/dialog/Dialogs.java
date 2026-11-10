package com.smeshariki.behavior.dialog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Окно реплик: игрок сам открывает его клавишей, когда горит иконка «говорить», и выбирает, что сказать. Какие реплики доступны, решает не этот
 * мод, а тот, кто ведёт игру (уровни): он регистрирует {@link Provider}. Список пересчитывается раз в {@link #SYNC_INTERVAL} тиков и уходит
 * клиенту при изменении; выбор выполняется, только если список с тех пор не менялся.
 */
public final class Dialogs {
	private static final int SYNC_INTERVAL = 10;

	/** Реплика: текст и что сделать на сервере, когда игрок её выбрал. */
	public record Reply(Component text, Consumer<ServerPlayer> action) {
	}

	@FunctionalInterface
	public interface Provider {
		List<Reply> replies(ServerPlayer player);
	}

	private static final List<Provider> PROVIDERS = new ArrayList<>();
	private static final Map<UUID, List<Component>> SENT = new HashMap<>();
	private static final Map<UUID, Integer> VERSIONS = new HashMap<>();

	private Dialogs() {
	}

	public static void register(Provider provider) {
		PROVIDERS.add(provider);
	}

	/** Что игрок может сказать сейчас. */
	public static List<Reply> replies(ServerPlayer player) {
		List<Reply> out = new ArrayList<>();
		for (Provider p : PROVIDERS) out.addAll(p.replies(player));
		return out;
	}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(DialogReplies.TYPE, DialogReplies.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DialogChoice.TYPE, DialogChoice.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(DialogChoice.TYPE, (msg, ctx) -> choose(ctx.player(), msg));
		ServerTickEvents.END_SERVER_TICK.register(Dialogs::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SENT.remove(handler.player.getUUID()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> VERSIONS.remove(handler.player.getUUID()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			SENT.clear();
			VERSIONS.clear();
		});
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % SYNC_INTERVAL != 0) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			List<Component> texts = replies(player).stream().map(Reply::text).toList();
			if (texts.equals(SENT.get(player.getUUID()))) continue;
			if (texts.isEmpty() && !SENT.containsKey(player.getUUID())) continue;
			SENT.put(player.getUUID(), texts);
			int version = VERSIONS.merge(player.getUUID(), 1, Integer::sum);
			ServerPlayNetworking.send(player, new DialogReplies(version, texts));
		}
	}

	private static void choose(ServerPlayer player, DialogChoice msg) {
		if (msg.version() != VERSIONS.getOrDefault(player.getUUID(), 0)) return;
		List<Reply> list = replies(player);
		List<Component> shown = SENT.getOrDefault(player.getUUID(), List.of());
		if (msg.index() < 0 || msg.index() >= list.size() || msg.index() >= shown.size()) return;
		Reply reply = list.get(msg.index());
		if (!reply.text().equals(shown.get(msg.index()))) return;
		reply.action().accept(player);
	}
}
