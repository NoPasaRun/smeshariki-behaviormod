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

	private static boolean wasSilent;

	/** Идёт ли сцена, под которую ванильную фоновую музыку и эмбиент надо выключить: показ, запись дубля, пуск в редакторе (пауза редактора — звуки игры возвращаются). */
	public static boolean silenceVanilla() {
		return mode == Mode.WATCH || mode == Mode.RECORD || (mode == Mode.EDIT && CutsceneEditor.playing);
	}

	/** Не запускать этот звук: ванильная музыка и эмбиент во время сцены (свой саундтрек сцены можно). */
	public static boolean blocksVanilla(net.minecraft.client.resources.sounds.SoundInstance sound) {
		if (!silenceVanilla()) return false;
		net.minecraft.sounds.SoundSource source = sound.getSource();
		return (source == net.minecraft.sounds.SoundSource.MUSIC || source == net.minecraft.sounds.SoundSource.AMBIENT) && !Soundtrack.isTrack(sound);
	}

	/** Редактор открыт в режиме камеры (курсор захвачен, окна нет). */
	public static boolean editorFocused() {
		return mode == Mode.EDIT && CutsceneEditor.focused && Minecraft.getInstance().gui.screen() == null;
	}

	/** Мышь сдвинулась в режиме камеры редактора: {@code dx, dy} — накопленные пиксели, как у игрока в игре. */
	public static void editorLook(double dx, double dy) {
		if (!editorFocused() || (dx == 0 && dy == 0)) return;
		Minecraft mc = Minecraft.getInstance();
		double s = mc.options.sensitivity().get() * 0.6 + 0.2;
		double k = s * s * s * 8.0;
		CutsceneEditor.look(dx * k * (mc.options.invertMouseX().get() ? -1 : 1), dy * k * (mc.options.invertMouseY().get() ? -1 : 1));
	}

	/** Нажатие клавиши в режиме камеры редактора. @return редактор забрал клавишу */
	public static boolean editorKey(net.minecraft.client.input.KeyEvent event) {
		return editorFocused() && CutsceneEditor.focusedKey(event);
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

	/** Свет сцены сейчас: −1 чёрный экран, 0 нет, 1 белый (рисует {@link #drawLight}). */
	private static float lightLevel;

	private static void light(float time) {
		// в редакторе на паузе экран не затягивается (иначе при −1 ничего не видно и не поправить): только при пуске и просмотре из сцены
		boolean live = mode == Mode.WATCH || mode == Mode.RECORD || (mode == Mode.EDIT && (CutsceneEditor.playing || CutsceneEditor.lookThrough));
		float v = scene == null || !live ? Float.NaN : scene.lightLevel(time);
		lightLevel = Float.isNaN(v) ? 0f : v;
	}

	/**
	 * Кинополосы: чёрные полосы сверху и снизу, пока экран шире, чем {@code scene.aspect()} (отношение ширины к высоте кадра, 2.39 — «кино»). Выезжают за 10 тиков
	 * от начала и уезжают за 10 тиков до конца. При показе — всегда, в редакторе — при пуске и «взгляде из сцены» (на паузе они мешали бы править).
	 */
	private static void drawBars(net.minecraft.client.gui.GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
		Cutscene s = scene;
		if (s == null || s.aspect() <= 0f) return;
		boolean live = mode == Mode.WATCH || (mode == Mode.EDIT && (CutsceneEditor.playing || CutsceneEditor.lookThrough));
		if (!live) return;
		int w = g.guiWidth(), h = g.guiHeight();
		float bar = (h - w / s.aspect()) / 2f;
		if (bar <= 0f) return;
		float t = time();
		float p = Math.max(0f, Math.min(1f, Math.min(t, s.length() - t) / 10f));
		p = p * p * (3f - 2f * p);
		int bh = Math.round(bar * p);
		if (bh <= 0) return;
		g.fill(0, 0, w, bh, 0xFF000000);
		g.fill(0, h - bh, w, h, 0xFF000000);
	}

	/** Затяжка экрана чёрным (отрицательный свет) или белым (положительный): −1 и 1 — сплошной цвет. Под полосой времени и окнами. */
	private static void drawLight(net.minecraft.client.gui.GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
		if (lightLevel == 0f || mode == Mode.NONE) return;
		int alpha = Math.round(Math.min(1f, Math.abs(lightLevel)) * 255);
		g.fill(0, 0, g.guiWidth(), g.guiHeight(), alpha << 24 | (lightLevel < 0 ? 0x000000 : 0xFFFFFF));
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
		Soundtrack.stopAll();
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() instanceof EditorScreen) mc.gui.setScreen(null);
		viewPos = null;
		sendView();
		mode = Mode.NONE;
		scene = null;
		recording = null;
		Puppets.clear();
		lightLevel = 0f;
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
		HudElementRegistry.attachElementBefore(VanillaHudElements.HOTBAR, BehaviorMod.id("light"), CutsceneClient::drawLight);
		HudElementRegistry.attachElementBefore(VanillaHudElements.HOTBAR, BehaviorMod.id("bars"), CutsceneClient::drawBars);
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, BehaviorMod.id("timeline"), TimelineHud::extract);
	}

	/** Очередной тик сцены. В редакторе время ведёт редактор. */
	private static void tick(Minecraft mc) {
		boolean pressed = false;
		while (SKIP.consumeClick()) pressed = true;
		// сцена началась — играющую ванильную музыку обрываем (новая блокируется в SoundManagerMixin)
		boolean silent = silenceVanilla();
		if (silent && !wasSilent) mc.getMusicManager().stopPlaying();
		wasSilent = silent;
		if (mode == Mode.NONE || scene == null) return;
		Soundtrack.tick();
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

	/** Дальность показа текущей сцены, чанков (0 — как у игрока): читает {@code OptionsMixin}. Только настоящий показ, не редактор и не запись. */
	public static int streamDistance() {
		return mode == Mode.WATCH && scene != null ? scene.distance() : 0;
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
		Soundtrack.start(scene, from, to);
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
