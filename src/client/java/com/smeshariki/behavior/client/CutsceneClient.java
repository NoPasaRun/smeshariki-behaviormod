package com.smeshariki.behavior.client;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.smeshariki.behavior.BehaviorMod;
import com.smeshariki.behavior.cutscene.Cutscene;
import com.smeshariki.behavior.cutscene.CutsceneBlob;
import com.smeshariki.behavior.cutscene.CutsceneEnd;
import com.smeshariki.behavior.cutscene.CutsceneSkip;
import com.smeshariki.behavior.cutscene.LineKey;
import com.smeshariki.behavior.cutscene.ViewCenter;
import com.smeshariki.behavior.cutscene.SoundKey;
import com.smeshariki.world.ViewLight;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;

/**
 * Кат-сцена на клиенте. Режимы: {@link Mode#WATCH} — смотрим (камера по кадрам, управление заблокировано, конец присылает сервер); {@link Mode#RECORD} —
 * записывается дубль, остальные актёры играют куклами, а игрок свободен; {@link Mode#EDIT} — редактор ({@link CutsceneEditor}): свободная камера и полоса времени.
 * Время сцены — счётчик клиентских тиков плюс доля тика; при съёмке видео ({@link VideoRender}) — номер кадра, без часов.
 */
public final class CutsceneClient {
	public enum Mode {
		NONE, WATCH, RECORD, EDIT
	}

	static final KeyMapping SKIP = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + BehaviorMod.NAMESPACE + ".skip_cutscene", GLFW.GLFW_KEY_BACKSPACE, BehaviorModClient.CATEGORY));

	static Mode mode = Mode.NONE;
	@Nullable
	static Cutscene scene;
	static Identifier id = BehaviorMod.id("scene");
	static Vec3 origin = Vec3.ZERO;
	/** Тик сцены. */
	static int tick;
	static float partial;
	/** Кого играет сам игрок при записи: его куклы нет, а настоящий персонаж виден. */
	@Nullable
	static Identifier recording;
	private static boolean lightOn;
	/** Где камера сцены в последнем кадре (нет — камера у игрока) и что мы сообщили серверу. */
	@Nullable
	private static Vec3 viewPos;
	private static boolean viewSent;
	private static final CutsceneBlob.Assembler ASSEMBLER = new CutsceneBlob.Assembler();

	private CutsceneClient() {
	}

	/** Управление игрока заблокировано (смотрим сцену или работаем в редакторе). */
	public static boolean active() {
		return mode == Mode.WATCH || mode == Mode.EDIT;
	}

	public static Mode mode() {
		return mode;
	}

	/** Время сцены в тиках с долей. */
	static float time() {
		if (VideoRender.running()) return VideoRender.time();
		return tick + partial;
	}

	/**
	 * Вид камеры в кадре (с учётом точки привязки), {@code null} — камера у игрока. Заодно ставит кукол и свет на момент кадра: этот вызов — раз за кадр,
	 * до того как мир собран для рисования.
	 */
	@Nullable
	public static Cutscene.View view(float partialTick) {
		if (mode == Mode.NONE || scene == null) return null;
		partial = partialTick;
		float time = time();
		Puppets.frame(origin, time);
		light(time);
		Cutscene.View result;
		if (mode == Mode.EDIT) {
			result = CutsceneEditor.view(partialTick);
		} else if (mode != Mode.WATCH || !scene.hasCamera()) {
			result = null;
		} else {
			Cutscene.View v = scene.view(time);
			result = new Cutscene.View(v.pos().add(origin), v.yaw(), v.pitch(), v.roll(), v.fov());
		}
		viewPos = result == null ? null : result.pos();
		return result;
	}

	private static void light(float time) {
		float d = scene == null ? -1 : scene.darkness(time);
		if (d >= 0) {
			ViewLight.set(1f, d);
			lightOn = true;
		} else if (lightOn) {
			ViewLight.set(0f, 0f);
			lightOn = false;
		}
	}

	static void start(Mode newMode, Identifier newId, Cutscene newScene, Vec3 newOrigin, int at, @Nullable Identifier played) {
		stopQuiet();
		Minecraft mc = Minecraft.getInstance();
		mode = newMode;
		id = newId;
		scene = newScene;
		origin = newOrigin;
		tick = at;
		recording = played;
		Puppets.show(newScene, played);
		if (newMode == Mode.WATCH && mc.gui.screen() != null) mc.setScreenAndShow(null);
		sounds(at - 1, at);
	}

	/** Сцена кончилась или прервана: вернуть камеру, свет, убрать кукол. */
	static void stop() {
		stopQuiet();
		VideoRender.finish();
	}

	private static void stopQuiet() {
		if (mode == Mode.NONE) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() instanceof EditorScreen) mc.gui.setScreen(null);
		viewPos = null;
		sendView();
		mode = Mode.NONE;
		scene = null;
		recording = null;
		Puppets.clear();
		if (lightOn) {
			ViewLight.set(0f, 0f);
			lightOn = false;
		}
	}

	static void init() {
		CutsceneEditor.init();
		ClientPlayNetworking.registerGlobalReceiver(CutsceneBlob.TYPE, (blob, ctx) -> {
			Cutscene got = ASSEMBLER.add(blob, e -> BehaviorMod.LOGGER.warn("Сцена с сервера не разобралась: {}", e));
			if (got == null) return;
			ctx.client().execute(() -> {
				switch (blob.purpose()) {
					case CutsceneBlob.WATCH -> start(Mode.WATCH, blob.id(), got, blob.origin(), 0, null);
					case CutsceneBlob.RENDER -> {
						start(Mode.WATCH, blob.id(), got, blob.origin(), 0, null);
						VideoRender.begin(blob.id(), (int) Math.round(Double.parseDouble(blob.character().isEmpty() ? "30" : blob.character())), got.length());
					}
					case CutsceneBlob.RECORD -> start(Mode.RECORD, blob.id(), got, blob.origin(), blob.at(), Identifier.tryParse(blob.character()));
					case CutsceneBlob.EDIT -> CutsceneEditor.open(blob.id(), got, blob.origin(), blob.at());
					default -> { }
				}
			});
		});
		ClientPlayNetworking.registerGlobalReceiver(CutsceneEnd.TYPE, (msg, ctx) -> ctx.client().execute(CutsceneClient::stop));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> stop());
		ClientTickEvents.END_CLIENT_TICK.register(CutsceneClient::tick);
		// панель и прицел на сцене не нужны
		HudElementRegistry.replaceElement(VanillaHudElements.HOTBAR, original -> (graphics, delta) -> {
			if (!active()) original.extractRenderState(graphics, delta);
		});
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, original -> (graphics, delta) -> {
			if (!active()) original.extractRenderState(graphics, delta);
		});
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, BehaviorMod.id("timeline"), TimelineHud::extract);
	}

	/** Очередной тик сцены. В редакторе время ведёт редактор. */
	private static void tick(Minecraft mc) {
		boolean pressed = false;
		while (SKIP.consumeClick()) pressed = true;
		if (mode == Mode.NONE || scene == null) return;
		if (mc.player != null && mc.player.tickCount % 5 == 0) sendView();
		if (mode == Mode.EDIT) {
			CutsceneEditor.tick(mc);
			return;
		}
		if (pressed) ClientPlayNetworking.send(new CutsceneSkip());
		if (VideoRender.running()) {
			VideoRender.tick();
			return;
		}
		tick++;
		Puppets.tick(origin, tick);
		sounds(tick - 1, tick);
		// сервер не ответил (остановился): не висеть на сцене вечно
		if (tick > scene.length() + 100) stop();
	}

	/** Сообщить серверу, где камера (раз в 5 тиков) или что она вернулась к игроку: вокруг камеры сервер грузит чанки. */
	private static void sendView() {
		if (viewPos != null) {
			ClientPlayNetworking.send(new ViewCenter(true, viewPos));
			viewSent = true;
		} else if (viewSent) {
			ClientPlayNetworking.send(new ViewCenter(false, Vec3.ZERO));
			viewSent = false;
		}
	}

	/** Звуки, чей тик прошёл: в {@code (from, to]}. */
	static void sounds(int from, int to) {
		Minecraft mc = Minecraft.getInstance();
		if (scene == null || mc.level == null) return;
		for (SoundKey key : scene.sounds()) {
			if (key.tick() <= from || key.tick() > to) continue;
			SoundEvent event = SoundEvent.createVariableRangeEvent(key.sound());
			mc.getSoundManager().play(SimpleSoundInstance.forUI(event, key.pitch(), key.volume()));
		}
	}

	/** Реплика, которая звучит сейчас (последняя из начавшихся и не кончившихся), или {@code null}. */
	@Nullable
	static LineKey line() {
		if (scene == null) return null;
		float t = time();
		LineKey best = null;
		for (LineKey l : scene.lines()) if (l.tick() <= t && t < l.tick() + l.duration()) best = l;
		return best;
	}
}
