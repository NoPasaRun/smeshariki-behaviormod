package com.smeshariki.behavior.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import com.smeshariki.behavior.BehaviorMod;
import com.smeshariki.behavior.cutscene.ActionKey;
import com.smeshariki.behavior.cutscene.ActorTrack;
import com.smeshariki.behavior.cutscene.CameraKey;
import com.smeshariki.behavior.cutscene.Clip;
import com.smeshariki.behavior.cutscene.Cutscene;
import com.smeshariki.behavior.cutscene.CutsceneBlob;
import com.smeshariki.behavior.cutscene.Easings;
import com.smeshariki.behavior.cutscene.LightKey;
import com.smeshariki.behavior.cutscene.LineKey;
import com.smeshariki.behavior.cutscene.SoundKey;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Редактор сцены. Поверх мира открыто окно {@link EditorScreen}: курсор свободен, по полосе времени внизу можно кликать (курсор времени, кадры камеры, актёры),
 * колесо меняет масштаб времени. Камера летит свободно: W A S D, Пробел — вверх, Shift — вниз, Ctrl — быстрее, взгляд — мышь с зажатой правой кнопкой, Q / E — крен.
 * Привязки — обычные клавиши (видно и меняется в настройках управления). Правки живут здесь, пока не нажато «сохранить»; записанные дубли сервер хранит у себя
 * и присылает вместе со сценой. Длина сцены не ограничена: кадр за концом растягивает сцену.
 */
final class CutsceneEditor {
	private static KeyMapping key(String name, int code) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + BehaviorMod.NAMESPACE + ".edit_" + name, code, BehaviorModClient.CATEGORY));
	}

	static final KeyMapping PLAY = key("play", GLFW.GLFW_KEY_P), BACK = key("back", GLFW.GLFW_KEY_LEFT), FORWARD = key("forward", GLFW.GLFW_KEY_RIGHT),
			TO_START = key("to_start", GLFW.GLFW_KEY_HOME), TO_END = key("to_end", GLFW.GLFW_KEY_END),
			CAM_KEY = key("camera_key", GLFW.GLFW_KEY_ENTER), DELETE = key("delete", GLFW.GLFW_KEY_DELETE), PREV_KEY = key("prev_key", GLFW.GLFW_KEY_PAGE_UP),
			NEXT_KEY = key("next_key", GLFW.GLFW_KEY_PAGE_DOWN), LOOK = key("look_through", GLFW.GLFW_KEY_N),
			REC_CAM = key("record_camera", GLFW.GLFW_KEY_R), LINE = key("line", GLFW.GLFW_KEY_T), SOUND = key("sound", GLFW.GLFW_KEY_Y),
			ACTION = key("action", GLFW.GLFW_KEY_U), LIGHT = key("light", GLFW.GLFW_KEY_I), ACTOR = key("actor", GLFW.GLFW_KEY_TAB),
			MUTE = key("mute_clip", GLFW.GLFW_KEY_M), SHIFT_BACK = key("clip_back", GLFW.GLFW_KEY_COMMA), SHIFT_FORWARD = key("clip_forward", GLFW.GLFW_KEY_PERIOD),
			TRIM_START = key("trim_start", GLFW.GLFW_KEY_LEFT_BRACKET), TRIM_END = key("trim_end", GLFW.GLFW_KEY_RIGHT_BRACKET), SET_LENGTH = key("set_length", GLFW.GLFW_KEY_J),
			ZOOM_IN = key("zoom_in", GLFW.GLFW_KEY_EQUAL), ZOOM_OUT = key("zoom_out", GLFW.GLFW_KEY_MINUS),
			TAKE = key("take", GLFW.GLFW_KEY_B), SAVE = key("save", GLFW.GLFW_KEY_O);

	/** Работающая копия сцены. */
	@Nullable
	static Cutscene scene;
	static Identifier id = BehaviorMod.id("scene");
	static Vec3 origin = Vec3.ZERO;
	static int cursor;
	static boolean playing, recordingCamera, armed, lookThrough, dirty;
	static int selectedActor;
	/** Выбранный кадр камеры (его тик) — для окошка параметров; −1 — не выбран. */
	static int selectedKey = -1;
	static String message = "";
	static long messageUntil;
	/** Видимая часть полосы времени: с тика {@code viewStart}, {@code viewLen} тиков. */
	static float viewStart, viewLen = 200;
	private static long exitAskedAt;

	private static Vec3 pos = Vec3.ZERO, prevPos = Vec3.ZERO;
	private static float yaw, pitch, roll, fov = 70;

	private CutsceneEditor() {
	}

	static void init() {
		loadEasings();
	}

	// -- вход и выход --

	static void open(Identifier newId, Cutscene newScene, Vec3 newOrigin, int at) {
		Minecraft mc = Minecraft.getInstance();
		boolean again = CutsceneClient.mode() == CutsceneClient.Mode.EDIT && id.equals(newId);
		CutsceneClient.start(CutsceneClient.Mode.EDIT, newId, newScene, newOrigin, 0, null);
		scene = newScene;
		id = newId;
		origin = newOrigin;
		for (Map.Entry<Identifier, Easings.Bezier> e : newScene.easings().entrySet()) {
			if (Easings.get(e.getKey()) == null) Easings.register(e.getKey(), e.getValue());
		}
		cursor = Math.max(0, at);
		CutsceneClient.tick = cursor;
		playing = recordingCamera = armed = false;
		lookThrough = false;
		dirty = false;
		selectedActor = 0;
		selectedKey = -1;
		fit();
		followCursor();
		if (!again && mc.player != null) {
			pos = prevPos = mc.player.getEyePosition();
			yaw = mc.player.getYRot();
			pitch = mc.player.getXRot();
			roll = 0;
			fov = 70;
		}
		mc.gui.setScreen(new EditorScreen());
		say("Редактор " + newId + ". P — пуск, Enter — кадр камеры, R — запись пролёта, B — дубль, O — сохранить. Клик по кадру камеры — его параметры, колесо — масштаб.");
	}

	/** Выйти по Esc или Backspace: если есть несохранённое, сначала предупредить. */
	static void requestExit() {
		if (dirty && System.currentTimeMillis() - exitAskedAt > 4000) {
			exitAskedAt = System.currentTimeMillis();
			say("Есть несохранённое. O — сохранить; Esc или Backspace ещё раз — выйти без сохранения.");
			return;
		}
		exit(false);
	}

	static void exit(boolean save) {
		if (save) save();
		scene = null;
		playing = recordingCamera = armed = false;
		CutsceneClient.stop();
	}

	static void say(String text) {
		message = text;
		messageUntil = System.currentTimeMillis() + 6000;
	}

	// -- вид --

	/** Вид камеры редактора: по кадрам сцены, пока идёт пуск или включён «взгляд из сцены»; иначе свободная. */
	@Nullable
	static Cutscene.View view(float partialTick) {
		if (scene == null) return null;
		if ((lookThrough || (playing && !recordingCamera)) && scene.hasCamera()) {
			Cutscene.View v = scene.view(cursor + (playing ? partialTick : 0));
			return new Cutscene.View(v.pos().add(origin), v.yaw(), v.pitch(), v.roll(), v.fov());
		}
		return new Cutscene.View(prevPos.lerp(pos, partialTick), yaw, pitch, roll, fov);
	}

	private static boolean flying() {
		return !(lookThrough || (playing && !recordingCamera));
	}

	/** Мышь с зажатой правой кнопкой повернула камеру. */
	static void look(double dx, double dy) {
		if (!flying()) return;
		yaw += (float) dx * 0.15f;
		pitch = Mth.clamp(pitch + (float) dy * 0.15f, -90f, 90f);
		movedByUser();
	}

	// -- полоса времени: масштаб --

	static int endTick() {
		return Math.max(scene == null ? 100 : scene.length(), cursor);
	}

	static void fit() {
		viewStart = 0;
		viewLen = Math.max(100, endTick() * 1.05f);
	}

	static void zoom(float factor, float aroundTick) {
		float newLen = Mth.clamp(viewLen * factor, 10f, 2_000_000f);
		float rel = viewLen <= 0 ? 0.5f : (aroundTick - viewStart) / viewLen;
		viewStart = Math.max(0, aroundTick - rel * newLen);
		viewLen = newLen;
	}

	static void pan(float ticks) {
		viewStart = Math.max(0, viewStart + ticks);
	}

	static void followCursor() {
		if (cursor < viewStart) viewStart = Math.max(0, cursor - viewLen * 0.1f);
		else if (cursor > viewStart + viewLen * 0.95f) viewStart = cursor - viewLen * 0.1f;
	}

	// -- клавиши --

	private static boolean down(Minecraft mc, int code) {
		return InputConstants.isKeyDown(mc.getWindow(), code);
	}

	/** Нажатие клавиши в окне редактора. @return обработано */
	static boolean onKey(KeyEvent e) {
		Cutscene s = scene;
		if (s == null) return false;
		boolean shift = (e.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0, ctrl = (e.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
		int step = shift ? 10 : ctrl ? 60 : 1;
		if (CutsceneClient.SKIP.matches(e)) {
			requestExit();
		} else if (PLAY.matches(e)) {
			if (playing) {
				playing = recordingCamera = armed = false;
			} else {
				if (cursor >= s.length()) cursor = 0;
				playing = true;
			}
		} else if (BACK.matches(e)) {
			seek(cursor - step);
		} else if (FORWARD.matches(e)) {
			seek(cursor + step);
		} else if (TO_START.matches(e)) {
			seek(0);
		} else if (TO_END.matches(e)) {
			seek(s.length());
		} else if (ZOOM_IN.matches(e)) {
			zoom(0.7f, cursor);
		} else if (ZOOM_OUT.matches(e)) {
			zoom(1 / 0.7f, cursor);
		} else if (LOOK.matches(e)) {
			lookThrough = !lookThrough;
		} else if (CAM_KEY.matches(e)) {
			setCameraKey(cursor, easeAt(cursor));
		} else if (DELETE.matches(e)) {
			if (shift) deleteClip();
			else deleteAt(cursor);
		} else if (PREV_KEY.matches(e)) {
			jump(-1);
		} else if (NEXT_KEY.matches(e)) {
			jump(1);
		} else if (REC_CAM.matches(e)) {
			toggleRecordCamera();
		} else if (LINE.matches(e)) {
			PromptScreen.open("Реплика на тике " + cursor, "Текст; показывается внизу экрана", "", text -> {
				touch(cursor);
				edit(scene().withLines(plus(scene().lines(), new LineKey(cursor, Math.max(40, 20 + text.length() * 2), Component.literal(text)))));
			});
		} else if (SOUND.matches(e)) {
			PromptScreen.open("Звук на тике " + cursor, "id [громкость [высота]], например minecraft:entity.player.levelup 1 1", "", CutsceneEditor::addSound);
		} else if (ACTION.matches(e)) {
			PromptScreen.open("Действие мира на тике " + cursor, "id [имя=значение …], например gameplaymod:lava_wave group=a", "", CutsceneEditor::addAction);
		} else if (LIGHT.matches(e)) {
			PromptScreen.open("Свет на тике " + cursor, "Затемнение 0..1 (0 — как есть); «-» — убрать дорожку", "", CutsceneEditor::addLight);
		} else if (ACTOR.matches(e)) {
			if (!s.actors().isEmpty()) selectedActor = (selectedActor + 1) % s.actors().size();
		} else if (MUTE.matches(e)) {
			muteClip();
		} else if (SHIFT_BACK.matches(e)) {
			shiftClip(-step);
		} else if (SHIFT_FORWARD.matches(e)) {
			shiftClip(step);
		} else if (TRIM_START.matches(e)) {
			trimClip(true);
		} else if (TRIM_END.matches(e)) {
			trimClip(false);
		} else if (SET_LENGTH.matches(e)) {
			if (cursor > 0) edit(s.withLength(cursor));
		} else if (SAVE.matches(e)) {
			save();
		} else if (TAKE.matches(e)) {
			take();
		} else {
			return false;
		}
		return true;
	}

	// -- такт --

	static void tick(Minecraft mc) {
		Cutscene s = scene;
		if (s == null) return;
		if (mc.gui.screen() == null) {
			mc.gui.setScreen(new EditorScreen());
			return;
		}
		if (!(mc.gui.screen() instanceof EditorScreen)) {
			// открыто окно ввода: время стоит на месте, камера не летит
			prevPos = pos;
			return;
		}
		fly(mc);
		advance();
		if (playing) followCursor();
		Puppets.tick(origin, cursor);
		path(mc);
	}

	private static Cutscene scene() {
		return scene;
	}

	private static <T> List<T> plus(List<T> list, T item) {
		List<T> out = new ArrayList<>(list);
		out.add(item);
		return out;
	}

	private static void edit(Cutscene changed) {
		scene = changed;
		CutsceneClient.scene = changed;
		Puppets.retrack(changed, null);
		dirty = true;
		if (selectedKey >= 0 && changed.camera().stream().noneMatch(k -> k.tick() == selectedKey)) selectedKey = -1;
		if (Minecraft.getInstance().gui.screen() instanceof EditorScreen screen) screen.refresh();
	}

	/** Растянуть сцену, если кадр или запись упали за её конец. */
	private static void touch(int tick) {
		if (tick > scene().length()) edit(scene().withLength(tick));
	}

	private static void seek(int tick) {
		cursor = Math.max(0, tick);
		CutsceneClient.tick = cursor;
		playing = false;
		recordingCamera = armed = false;
		followCursor();
	}

	/** Курсор времени поставлен мышью. */
	static void seekTo(int tick) {
		seek(tick);
	}

	private static void fly(Minecraft mc) {
		prevPos = pos;
		if (!flying()) return;
		boolean ctrl = down(mc, GLFW.GLFW_KEY_LEFT_CONTROL);
		double speed = ctrl ? 1.0 : 0.3;
		Vec3 forward = Vec3.directionFromRotation(pitch, yaw);
		Vec3 right = new Vec3(-Math.cos(Math.toRadians(yaw)), 0, -Math.sin(Math.toRadians(yaw)));
		Vec3 move = Vec3.ZERO;
		if (down(mc, GLFW.GLFW_KEY_W)) move = move.add(forward);
		if (down(mc, GLFW.GLFW_KEY_S)) move = move.subtract(forward);
		if (down(mc, GLFW.GLFW_KEY_D)) move = move.add(right);
		if (down(mc, GLFW.GLFW_KEY_A)) move = move.subtract(right);
		if (down(mc, GLFW.GLFW_KEY_SPACE)) move = move.add(0, 1, 0);
		if (down(mc, GLFW.GLFW_KEY_LEFT_SHIFT)) move = move.add(0, -1, 0);
		boolean moved = false;
		if (move.lengthSqr() > 0) {
			pos = pos.add(move.normalize().scale(speed));
			moved = true;
		}
		boolean q = down(mc, GLFW.GLFW_KEY_Q), e = down(mc, GLFW.GLFW_KEY_E);
		if (q && e) {
			if (roll != 0f) moved = true;
			roll = 0f;
		} else if (q) {
			roll -= 1f;
			moved = true;
		} else if (e) {
			roll += 1f;
			moved = true;
		}
		if (moved) movedByUser();
	}

	/** Камера сдвинута рукой: если запись пролёта ждала движения (R), она начинается. */
	private static void movedByUser() {
		if (armed) startCameraRecording();
	}

	private static void advance() {
		if (!playing) return;
		int before = cursor;
		cursor++;
		CutsceneClient.tick = cursor;
		CutsceneClient.sounds(before, cursor);
		if (recordingCamera) {
			// запись пролёта идёт и за концом сцены: сцена растёт вслед за камерой
			if (cursor > scene().length()) edit(scene().withLength(cursor));
			if (cursor % 4 == 0) setCameraKey(cursor, Easings.LINEAR);
			return;
		}
		if (cursor >= scene().length()) {
			cursor = scene().length();
			CutsceneClient.tick = cursor;
			playing = false;
		}
	}

	// -- кадры камеры --

	private static Identifier easeAt(int tick) {
		for (CameraKey k : scene().camera()) if (k.tick() == tick) return k.ease();
		return Easings.SMOOTH;
	}

	private static void setCameraKey(int tick, Identifier ease) {
		CameraKey key = new CameraKey(tick, pos.subtract(origin), yaw, pitch, roll, fov, ease);
		List<CameraKey> keys = new ArrayList<>();
		for (CameraKey k : scene().camera()) if (k.tick() != tick) keys.add(k);
		keys.add(key);
		touch(tick);
		edit(scene().withCamera(keys));
		if (!recordingCamera) say("Кадр камеры на тике " + tick + " (" + keys.size() + " всего).");
	}

	/** Запись пролёта: R ждёт, пока камера сдвинется (чтобы успеть встать), и тогда идёт время и пишутся кадры. */
	private static void toggleRecordCamera() {
		if (recordingCamera || armed) {
			recordingCamera = armed = playing = false;
			say("Запись камеры остановлена.");
			return;
		}
		armed = true;
		playing = false;
		lookThrough = false;
		say("Запись камеры с тика " + cursor + ": двинь камеру — пойдёт время. Кадры после курсора будут заменены. R или P — стоп.");
	}

	private static void startCameraRecording() {
		armed = false;
		List<CameraKey> keep = new ArrayList<>();
		for (CameraKey k : scene().camera()) if (k.tick() < cursor) keep.add(k);
		edit(scene().withCamera(keep));
		recordingCamera = true;
		playing = true;
		setCameraKey(cursor, Easings.LINEAR);
	}

	/** Окошко кадра: «Просмотр» — встать на кадр и смотреть из него. */
	static void viewKey(int tick) {
		for (CameraKey k : scene().camera()) {
			if (k.tick() != tick) continue;
			seek(tick);
			pos = prevPos = k.pos().add(origin);
			yaw = k.yaw();
			pitch = k.pitch();
			roll = k.roll();
			fov = k.fov();
			lookThrough = true;
			return;
		}
	}

	static void selectKey(int tick) {
		selectedKey = tick;
		if (Minecraft.getInstance().gui.screen() instanceof EditorScreen screen) screen.refresh();
	}

	static void setEase(int tick, Identifier ease) {
		List<CameraKey> keys = new ArrayList<>(scene().camera());
		for (int i = 0; i < keys.size(); i++) {
			if (keys.get(i).tick() == tick) keys.set(i, keys.get(i).withEase(ease));
		}
		edit(scene().withCamera(keys));
	}

	/** Перенести кадр на текущий вид свободной камеры. */
	static void refixKey(int tick) {
		setCameraKey(tick, easeAt(tick));
	}

	static void deleteKey(int tick) {
		edit(scene().withCamera(scene().camera().stream().filter(k -> k.tick() != tick).toList()));
		selectedKey = -1;
		if (Minecraft.getInstance().gui.screen() instanceof EditorScreen screen) screen.refresh();
	}

	@Nullable
	static CameraKey keyAt(int tick) {
		if (scene == null) return null;
		for (CameraKey k : scene.camera()) if (k.tick() == tick) return k;
		return null;
	}

	/** Следующая / предыдущая функция реестра для кадра. */
	static void cycleEase(int tick, int dir) {
		CameraKey k = keyAt(tick);
		if (k == null) return;
		List<Identifier> ids = Easings.ids();
		int i = ids.indexOf(k.ease());
		setEase(tick, ids.get(Math.floorMod(i + dir, ids.size())));
	}

	/** Добавить в реестр свою кривую Безье и назначить её кадру. Ввод: {@code x1 y1 x2 y2 [имя]}. */
	static void addBezier(int tick, String text) {
		String[] p = text.trim().replace(',', '.').split("\\s+");
		if (p.length < 4) {
			say("Нужно четыре числа: x1 y1 x2 y2 [имя].");
			return;
		}
		try {
			Easings.Bezier b = new Easings.Bezier(Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]));
			String name = p.length > 4 ? p[4] : "bezier_" + p[0] + "_" + p[1] + "_" + p[2] + "_" + p[3];
			Identifier eid = Easings.parse(name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./:-]", "_"));
			if (!Easings.register(eid, b)) {
				say("Имя " + eid + " занято встроенной функцией.");
				return;
			}
			saveEasings();
			setEase(tick, eid);
			say("Функция " + eid + " (" + b.text() + ") добавлена в реестр.");
		} catch (NumberFormatException e) {
			say("x1 y1 x2 y2 — числа (x от 0 до 1).");
		}
	}

	// -- пользовательские функции: config/behaviormod/easings.json --

	private static java.nio.file.Path easingFile() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("behaviormod").resolve("easings.json");
	}

	private static void loadEasings() {
		java.nio.file.Path file = easingFile();
		if (!java.nio.file.Files.isRegularFile(file)) return;
		try (java.io.Reader r = java.nio.file.Files.newBufferedReader(file)) {
			com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseReader(r).getAsJsonObject();
			for (Map.Entry<String, com.google.gson.JsonElement> e : obj.entrySet()) {
				com.google.gson.JsonArray a = e.getValue().getAsJsonArray();
				Easings.register(Easings.parse(e.getKey()), new Easings.Bezier(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), a.get(3).getAsFloat()));
			}
		} catch (java.io.IOException | RuntimeException e) {
			BehaviorMod.LOGGER.warn("Функции камеры не прочитались из {}", file, e);
		}
	}

	private static void saveEasings() {
		com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
		for (Identifier eid : Easings.ids()) {
			if (Easings.isBuiltin(eid)) continue;
			Easings.Bezier b = Easings.get(eid);
			if (b == null) continue;
			com.google.gson.JsonArray a = new com.google.gson.JsonArray();
			a.add(b.x1());
			a.add(b.y1());
			a.add(b.x2());
			a.add(b.y2());
			obj.add(eid.getNamespace().equals(BehaviorMod.NAMESPACE) ? eid.getPath() : eid.toString(), a);
		}
		java.nio.file.Path file = easingFile();
		try {
			java.nio.file.Files.createDirectories(file.getParent());
			java.nio.file.Files.writeString(file, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(obj));
		} catch (java.io.IOException e) {
			BehaviorMod.LOGGER.warn("Функции камеры не записались в {}", file, e);
		}
	}

	private static void jump(int dir) {
		int best = -1;
		for (CameraKey k : scene().camera()) {
			if (dir < 0 && k.tick() < cursor && (best < 0 || k.tick() > best)) best = k.tick();
			if (dir > 0 && k.tick() > cursor && (best < 0 || k.tick() < best)) best = k.tick();
		}
		if (best < 0) return;
		seek(best);
		CameraKey k = keyAt(best);
		if (k != null) {
			pos = prevPos = k.pos().add(origin);
			yaw = k.yaw();
			pitch = k.pitch();
			roll = k.roll();
			fov = k.fov();
			selectKey(best);
		}
	}

	/** Убрать всё, что стоит ровно на тике: кадр камеры, реплики, звуки, действия, свет. */
	private static void deleteAt(int tick) {
		Cutscene s = scene();
		int before = s.camera().size() + s.lines().size() + s.sounds().size() + s.actions().size() + s.light().size();
		Cutscene out = s.withCamera(s.camera().stream().filter(k -> k.tick() != tick).toList())
				.withLines(s.lines().stream().filter(k -> k.tick() != tick).toList())
				.withSounds(s.sounds().stream().filter(k -> k.tick() != tick).toList())
				.withActions(s.actions().stream().filter(k -> k.tick() != tick).toList())
				.withLight(s.light().stream().filter(k -> k.tick() != tick).toList());
		int after = out.camera().size() + out.lines().size() + out.sounds().size() + out.actions().size() + out.light().size();
		if (after == before) {
			say("На тике " + tick + " ничего нет.");
			return;
		}
		edit(out);
		say("Убрано на тике " + tick + ": " + (before - after));
	}

	private static void addSound(String text) {
		String[] p = text.trim().split("\\s+");
		Identifier sound = Identifier.tryParse(p[0]);
		if (sound == null) {
			say("Не id звука: " + p[0]);
			return;
		}
		try {
			float volume = p.length > 1 ? Float.parseFloat(p[1]) : 1f;
			float pitchValue = p.length > 2 ? Float.parseFloat(p[2]) : 1f;
			touch(cursor);
			edit(scene().withSounds(plus(scene().sounds(), new SoundKey(cursor, sound, volume, pitchValue))));
		} catch (NumberFormatException e) {
			say("Громкость и высота — числа.");
		}
	}

	private static void addAction(String text) {
		String[] p = text.trim().split("\\s+");
		Identifier action = Identifier.tryParse(p[0]);
		if (action == null) {
			say("Не id действия: " + p[0]);
			return;
		}
		Map<String, String> args = new TreeMap<>();
		for (int i = 1; i < p.length; i++) {
			int eq = p[i].indexOf('=');
			if (eq <= 0) {
				say("Параметр «" + p[i] + "» не вида имя=значение.");
				return;
			}
			args.put(p[i].substring(0, eq), p[i].substring(eq + 1));
		}
		touch(cursor);
		edit(scene().withActions(plus(scene().actions(), new ActionKey(cursor, action, args))));
	}

	private static void addLight(String text) {
		if (text.trim().equals("-")) {
			edit(scene().withLight(List.of()));
			return;
		}
		try {
			float d = Float.parseFloat(text.trim().replace(',', '.'));
			List<LightKey> keys = new ArrayList<>();
			for (LightKey k : scene().light()) if (k.tick() != cursor) keys.add(k);
			keys.add(new LightKey(cursor, Mth.clamp(d, 0f, 1f)));
			touch(cursor);
			edit(scene().withLight(keys));
		} catch (NumberFormatException e) {
			say("Затемнение — число от 0 до 1.");
		}
	}

	// -- клипы --

	@Nullable
	private static ActorTrack track() {
		List<ActorTrack> actors = scene().actors();
		return actors.isEmpty() ? null : actors.get(Mth.clamp(selectedActor, 0, actors.size() - 1));
	}

	/** Клип выбранного актёра под курсором (из покрывающих — начавшийся позже всех). */
	private static int clipIndex(ActorTrack track) {
		int best = -1;
		for (int i = 0; i < track.clips().size(); i++) {
			Clip c = track.clips().get(i);
			if (c.covers(cursor) && (best < 0 || c.start() >= track.clips().get(best).start())) best = i;
		}
		return best;
	}

	private static void changeClip(java.util.function.UnaryOperator<Clip> change, String done) {
		ActorTrack track = track();
		if (track == null) {
			say("Нет записанных актёров: запиши дубль (B).");
			return;
		}
		int i = clipIndex(track);
		if (i < 0) {
			say("У " + track.character() + " нет клипа на тике " + cursor + ".");
			return;
		}
		List<Clip> clips = new ArrayList<>(track.clips());
		Clip changed = change.apply(clips.get(i));
		if (changed == null) clips.remove(i);
		else clips.set(i, changed);
		List<ActorTrack> tracks = new ArrayList<>(scene().actors());
		tracks.set(tracks.indexOf(track), track.withClips(clips));
		edit(scene().withActors(tracks));
		say(done);
	}

	private static void muteClip() {
		changeClip(c -> c.withMuted(!c.muted()), "Клип включён/выключен.");
	}

	private static void shiftClip(int by) {
		changeClip(c -> c.withStart(c.start() + by), "Клип сдвинут на " + by + ".");
	}

	private static void trimClip(boolean front) {
		int at = cursor;
		changeClip(c -> front ? c.trimmed(at, Integer.MAX_VALUE) : c.trimmed(0, at), front ? "Начало клипа обрезано до тика " + at + "." : "Конец клипа обрезан на тике " + at + ".");
	}

	private static void deleteClip() {
		changeClip(c -> null, "Клип удалён.");
	}

	/** Выбрать актёра кликом по его строке. */
	static void selectActor(int index) {
		selectedActor = index;
	}

	// -- сеть --

	static void save() {
		Cutscene s = scene;
		if (s == null) return;
		// сцена носит с собой определения своих функций: так она играет и там, где их нет в реестре
		Map<Identifier, Easings.Bezier> used = new HashMap<>();
		Set<Identifier> seen = new HashSet<>();
		for (CameraKey k : s.camera()) {
			if (!seen.add(k.ease()) || Easings.isBuiltin(k.ease())) continue;
			Easings.Bezier b = s.easings().get(k.ease());
			if (b == null) b = Easings.get(k.ease());
			if (b != null) used.put(k.ease(), b);
		}
		s = s.withEasings(used);
		scene = s;
		CutsceneClient.scene = s;
		for (CutsceneBlob b : CutsceneBlob.split(id, CutsceneBlob.SAVE, origin, 0, "", s)) ClientPlayNetworking.send(b);
		dirty = false;
		say("Отправлено на сохранение.");
	}

	/** Записать дубль с курсора: сохранить правки, выйти из редактора (сервер вернёт его после дубля). */
	private static void take() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		save();
		String command = "cutscene record " + id + " " + cursor + " true";
		exit(false);
		mc.player.connection.sendCommand(command);
	}

	/** Путь камеры в мире: искры в кадрах и вдоль пути, раз в полсекунды. */
	private static void path(Minecraft mc) {
		Cutscene s = scene;
		if (s == null || mc.level == null || mc.player == null || mc.player.tickCount % 10 != 0 || s.camera().size() < 1) return;
		for (CameraKey k : s.camera()) {
			Vec3 p = k.pos().add(origin);
			mc.level.addParticle(ParticleTypes.END_ROD, p.x, p.y, p.z, 0, 0, 0);
		}
		int from = s.camera().getFirst().tick(), to = s.camera().getLast().tick();
		for (int t = from; t < to; t += 4) {
			Vec3 p = s.view(t).pos().add(origin);
			mc.level.addParticle(ParticleTypes.GLOW, p.x, p.y, p.z, 0, 0, 0);
		}
	}

	static String rollText() {
		return String.format(Locale.ROOT, "крен %.0f°", roll);
	}
}
