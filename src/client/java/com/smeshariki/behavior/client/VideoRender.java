package com.smeshariki.behavior.client;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.smeshariki.behavior.BehaviorMod;
import com.smeshariki.behavior.cutscene.CutsceneSkip;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Покадровая съёмка сцены в видео (как в Replay Mod): время сцены идёт не по часам, а по номеру кадра — кадр {@code n} это тик {@code n·20/fps}, —
 * поэтому камера, куклы и свет точно такие, как должны быть, как бы медленно ни считались тяжёлые шейдеры (в том числе с трассировкой через Iris).
 * Каждый кадр после отрисовки уходит в PNG ({@code <папка игры>/data/behaviormod/render/<сцена>-<время>/frame_000001.png}); если в системе есть {@code ffmpeg}, в конце
 * из них собирается {@code scene.mp4}. Размер кадра — размер окна. Мир вокруг сцены идёт в обычном времени (кроме кукол), так что съёмка годится
 * для сцен, где важны камера, актёры и свет; действия мира сервер выполняет по своим тикам.
 */
final class VideoRender {
	private static boolean running;
	private static int fps = 30, frame, total, lastTick;
	private static Path dir;
	private static Identifier id;
	private static ExecutorService writer;

	private VideoRender() {
	}

	static boolean running() {
		return running;
	}

	/** Время сцены в тиках для кадра, который рисуется сейчас. */
	static float time() {
		return frame * 20f / fps;
	}

	static void begin(Identifier sceneId, int framesPerSecond, int lengthTicks) {
		Minecraft mc = Minecraft.getInstance();
		fps = Math.max(1, Math.min(120, framesPerSecond));
		total = (int) Math.ceil(lengthTicks * (double) fps / 20.0) + 1;
		frame = 0;
		lastTick = 0;
		id = sceneId;
		try {
			dir = com.smeshariki.behavior.ModData.moved(com.smeshariki.behavior.ModData.game("cutscene-render"), com.smeshariki.behavior.ModData.dir().resolve("render")).resolve(sceneId.getNamespace() + "-" + sceneId.getPath().replace('/', '_') + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
			Files.createDirectories(dir);
		} catch (java.io.IOException e) {
			BehaviorMod.LOGGER.warn("Папка съёмки не создалась", e);
			return;
		}
		writer = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "cutscene-render-writer");
			t.setDaemon(true);
			return t;
		});
		running = true;
		say("Съёмка " + sceneId + ": " + total + " кадров, " + fps + " к/с → " + dir);
	}

	/** Раз в клиентский тик во время съёмки: ничего не двигает (время — номер кадра), но держит сцену живой. */
	static void tick() {
		// время идёт по кадрам: см. afterFrame
	}

	/** После отрисовки кадра (миксин {@code GameRendererMixin}): снять его и перейти к следующему. */
	static void afterFrame(RenderTarget target) {
		if (!running) return;
		int number = ++frame;
		Path file = dir.resolve(String.format("frame_%06d.png", number));
		Screenshot.takeScreenshot(target, image -> writer.execute(() -> {
			try (image) {
				image.writeToFile(file);
			} catch (java.io.IOException e) {
				BehaviorMod.LOGGER.warn("Кадр {} не записался", file, e);
			}
		}));
		int tick = (int) Math.floor(time());
		while (lastTick < tick) {
			lastTick++;
			Puppets.tick(CutsceneClient.origin, lastTick);
			CutsceneClient.sounds(lastTick - 1, lastTick);
		}
		if (frame >= total) {
			running = false;
			ClientPlayNetworking.send(new CutsceneSkip());
			encode();
		}
	}

	/** Съёмка прервана (сцену остановили). */
	static void finish() {
		if (!running) return;
		running = false;
		say("Съёмка прервана на кадре " + frame + ".");
		encode();
	}

	private static void encode() {
		Path folder = dir;
		int rate = fps;
		writer.execute(() -> {
			try {
				File out = folder.resolve("scene.mp4").toFile();
				Process p = new ProcessBuilder("ffmpeg", "-y", "-framerate", String.valueOf(rate), "-i", folder.resolve("frame_%06d.png").toString(), "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "12", out.getPath())
						.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
				int code = p.waitFor();
				say(code == 0 ? "Видео готово: " + out : "ffmpeg ответил кодом " + code + ", кадры лежат в " + folder);
			} catch (java.io.IOException e) {
				say("Кадры сняты в " + folder + " (ffmpeg не найден: собери видео сам, " + rate + " к/с).");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		writer.shutdown();
	}

	private static void say(String text) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) mc.player.sendSystemMessage(Component.literal(text));
		});
	}
}
