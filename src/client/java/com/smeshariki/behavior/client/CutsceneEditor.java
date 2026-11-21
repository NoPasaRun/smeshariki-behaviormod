package com.smeshariki.behavior.client;

import java.util.ArrayList;
import java.util.Collections;
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
import com.smeshariki.behavior.cutscene.MusicKey;
import com.smeshariki.behavior.cutscene.SoundKey;
import com.smeshariki.ui.PromptScreen;

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

	/**
	 * Имена ключей новые там, где менялась клавиша по умолчанию: игра хранит привязки в options.txt по имени, и старое значение (пробел на «пуск», N на «взгляд»)
	 * перебило бы новое. Пробел в редакторе — только полёт камеры вверх.
	 */
	static final KeyMapping PLAY = key("play_pause", GLFW.GLFW_KEY_P), BACK = key("step_back", GLFW.GLFW_KEY_COMMA), FORWARD = key("step_forward", GLFW.GLFW_KEY_PERIOD),
			TOOL_PREV = key("tool_prev", GLFW.GLFW_KEY_LEFT), TOOL_NEXT = key("tool_next", GLFW.GLFW_KEY_RIGHT), USE_TOOL = key("use_tool", GLFW.GLFW_KEY_ENTER),
			TO_START = key("to_start", GLFW.GLFW_KEY_HOME), TO_END = key("to_end", GLFW.GLFW_KEY_END),
			CAM_KEY = key("camera_key", GLFW.GLFW_KEY_C), DELETE = key("delete", GLFW.GLFW_KEY_DELETE), PREV_KEY = key("prev_key", GLFW.GLFW_KEY_PAGE_UP),
			NEXT_KEY = key("next_key", GLFW.GLFW_KEY_PAGE_DOWN), LOOK = key("view_scene", GLFW.GLFW_KEY_G),
			REC_CAM = key("record_camera", GLFW.GLFW_KEY_R), LINE = key("line", GLFW.GLFW_KEY_T), SOUND = key("sound", GLFW.GLFW_KEY_Y),
			ACTION = key("action", GLFW.GLFW_KEY_U), LIGHT = key("light", GLFW.GLFW_KEY_I), ACTOR = key("actor", GLFW.GLFW_KEY_TAB),
			MUTE = key("mute_clip", GLFW.GLFW_KEY_M), SHIFT_BACK = key("clip_move_back", GLFW.GLFW_KEY_SEMICOLON), SHIFT_FORWARD = key("clip_move_forward", GLFW.GLFW_KEY_APOSTROPHE),
			TRIM_START = key("trim_start", GLFW.GLFW_KEY_LEFT_BRACKET), TRIM_END = key("trim_end", GLFW.GLFW_KEY_RIGHT_BRACKET), SET_LENGTH = key("set_length", GLFW.GLFW_KEY_J),
			ZOOM_IN = key("zoom_in", GLFW.GLFW_KEY_EQUAL), ZOOM_OUT = key("zoom_out", GLFW.GLFW_KEY_MINUS),
			TAKE = key("take", GLFW.GLFW_KEY_B), SAVE = key("save", GLFW.GLFW_KEY_O), HELP = key("help", GLFW.GLFW_KEY_F1),
			ALONG = key("along", GLFW.GLFW_KEY_X), MUSIC = key("music", GLFW.GLFW_KEY_H), FOLLOW = key("follow_move", GLFW.GLFW_KEY_F), AUTO_STOP = key("auto_stop", GLFW.GLFW_KEY_K), BARS = key("bars", GLFW.GLFW_KEY_Z), DISTANCE = key("distance", GLFW.GLFW_KEY_V);
	/** Кинополосы сцены, по кругу: нет, 2.39, 2.20, 2.00, 1.85 (отношение ширины к высоте кадра). */
	static final float[] ASPECTS = { 0f, 2.39f, 2.2f, 2.0f, 1.85f };

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

	/** Инструмент, который ставит Enter на курсоре; переключается стрелками влево / вправо или кликом по плашке. */
	enum Tool {
		CAMERA("Камера"), LINE("Реплика"), SOUND("Звук"), MUSIC("Музыка"), ACTION("Действие"), LIGHT("Свет");

		final String title;

		Tool(String title) {
			this.title = title;
		}
	}

	static Tool tool = Tool.CAMERA;
	/** Показана подсказка со всеми клавишами (F1). */
	static boolean help;
	/**
	 * Полёт «по движению» (F): камера сама смотрит туда, куда летит, а мышью вращать не нужно. W / S — вперёд / назад по взгляду, A / D — повернуть влево / вправо,
	 * Пробел / Shift — вверх / вниз (с W взгляд плавно наклоняется вслед). Выключено — обычный полёт: A / D боком, взгляд мышью (ПКМ).
	 */
	static boolean followMove = false;
	/** Запись (пролёт камеры R и дубль актёра B) кончается сама, когда после движения прошло столько секунд без него; 0 — только вручную. K листает значения. */
	static int autoStopSeconds = 2;
	private static final int[] AUTO_STOP_STEPS = { 0, 1, 2, 3, 5 };
	/** Последний тик записи пролёта, на котором камеру ещё двигали, и длина сцены до записи (после автоостановки сцена не длиннее нужного). */
	private static int lastMoveTick, lengthBeforeRecording;
	/**
	 * Режим камеры: окна нет, курсор захвачен, мышь поворачивает камеру, W A S D / Пробел / Shift — полёт, как в игре. Esc выходит в режим панели (окно {@link EditorScreen},
	 * курсор свободен — полоса времени, кнопки); клик по миру возвращает камеру.
	 */
	static boolean focused = true;

	// -- отмена и повтор: снимки сцены перед каждой правкой (запись пролёта и перетаскивание — одним шагом) --
	private static final int UNDO_MAX = 100;
	private static final java.util.ArrayDeque<Cutscene> undo = new java.util.ArrayDeque<>(), redo = new java.util.ArrayDeque<>();
	private static boolean grouping, dragging;
	/** Скопированные отметки: тики от начала куска. */
	@Nullable
	private static Cutscene clipboard;
	private static int clipboardSpan;
	/** Выделенная область времени (включительно); {@code selFrom < 0} — нет. Delete убирает в ней всё, что стоит на тиках. */
	static int selFrom = -1, selTo = -1;

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
		selFrom = selTo = -1;
		undo.clear();
		redo.clear();
		grouping = false;
		fit();
		followCursor();
		if (!again && mc.player != null) {
			pos = prevPos = mc.player.getEyePosition();
			yaw = mc.player.getYRot();
			pitch = mc.player.getXRot();
			roll = 0;
			fov = 70;
		}
		focused = true;
		mc.gui.setScreen(null);
		say("Редактор " + newId + ". ←/→ — инструмент, Enter — поставить, Shift+тянуть по полосе — выделить область (Delete — убрать), P — пуск, B — дубль, O — сохранить.");
	}

	/** Выйти по Esc или Backspace: если есть несохранённое, сначала предупредить. */
	static boolean requestExit() {
		if (dirty && System.currentTimeMillis() - exitAskedAt > 4000) {
			exitAskedAt = System.currentTimeMillis();
			say("Есть несохранённое. O — сохранить; Esc или Backspace ещё раз — выйти без сохранения.");
			return false;
		}
		exit(false);
		return true;
	}

	static void exit(boolean save) {
		if (save) save();
		scene = null;
		playing = recordingCamera = armed = false;
		CutsceneClient.stop();
	}

	/** В режим камеры: окно закрывается, курсор захватывается. */
	static void focus() {
		focused = true;
		Minecraft.getInstance().gui.setScreen(null);
	}

	/** В режим панели: курсор свободен, окно редактора открыто. */
	static void unfocus() {
		focused = false;
		Minecraft.getInstance().gui.setScreen(new EditorScreen());
	}

	/** Клавиша в режиме камеры (из {@code KeyboardHandlerMixin}). */
	static boolean focusedKey(KeyEvent e) {
		if (scene == null) return false;
		if (e.isEscape()) {
			unfocus();
			return true;
		}
		if (BehaviorModClient.MANAGER.matches(e)) {
			if (requestExit()) CutsceneManagerScreen.toggle();
			return true;
		}
		return onKey(e);
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

	/**
	 * Взгляд из камеры сцены вкл/выкл (G, кнопка, «Просмотр» в окошке кадра). Выключая, свободная камера встаёт туда, откуда смотрели, и летит дальше; сам он
	 * выключается, как только пошло движение клавишами или мышью — вернуться к обычному полёту можно просто взявшись за управление.
	 */
	static void setLookThrough(boolean on) {
		if (!on && lookThrough && scene != null && scene.hasCamera()) {
			Cutscene.View v = scene.view(cursor);
			pos = prevPos = v.pos().add(origin);
			yaw = v.yaw();
			pitch = v.pitch();
			roll = v.roll();
			fov = v.fov();
		}
		lookThrough = on;
		if (Minecraft.getInstance().gui.screen() instanceof EditorScreen screen) screen.refresh();
	}

	private static boolean flying() {
		return !(lookThrough || (playing && !recordingCamera));
	}

	/** Мышь с зажатой правой кнопкой повернула камеру. */
	static void look(double dx, double dy) {
		// мышь стоит: движением это не считается (иначе R стартовала бы сразу, автоостановка не срабатывала бы, а взгляд из сцены гас бы сам)
		if (Math.abs(dx) < 1e-6 && Math.abs(dy) < 1e-6) return;
		if (lookThrough) setLookThrough(false);
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
		if (ctrl && e.key() == GLFW.GLFW_KEY_Z) {
			if (shift) swap(redo, undo, "повторено");
			else swap(undo, redo, "отменено");
			return true;
		}
		if (ctrl && e.key() == GLFW.GLFW_KEY_Y) {
			swap(redo, undo, "повторено");
			return true;
		}
		if (ctrl && e.key() == GLFW.GLFW_KEY_C) {
			copySelection();
			return true;
		}
		if (ctrl && e.key() == GLFW.GLFW_KEY_V) {
			paste();
			return true;
		}
		if (ctrl && e.key() == GLFW.GLFW_KEY_A) {
			select(0, s.length());
			return true;
		}
		if (HELP.matches(e)) {
			help = !help;
			return true;
		}
		if (CutsceneClient.SKIP.matches(e)) {
			requestExit();
		} else if (PLAY.matches(e)) {
			if (playing) {
				playing = recordingCamera = armed = false;
			} else {
				if (hasSelection() && (cursor < selFrom || cursor > selTo)) cursor = selFrom;
				else if (cursor >= s.length()) cursor = 0;
				CutsceneClient.tick = cursor;
				playing = true;
				Soundtrack.startCovering(s, cursor);
			}
		} else if (BACK.matches(e)) {
			seek(cursor - step);
		} else if (FORWARD.matches(e)) {
			seek(cursor + step);
		} else if (TOOL_PREV.matches(e)) {
			cycleTool(-1);
		} else if (TOOL_NEXT.matches(e)) {
			cycleTool(1);
		} else if (USE_TOOL.matches(e)) {
			useTool();
		} else if (BARS.matches(e)) {
			cycleBars();
		} else if (DISTANCE.matches(e)) {
			cycleDistance();
		} else if (AUTO_STOP.matches(e)) {
			int i = 0;
			while (i < AUTO_STOP_STEPS.length - 1 && AUTO_STOP_STEPS[i] != autoStopSeconds) i++;
			autoStopSeconds = AUTO_STOP_STEPS[(i + 1) % AUTO_STOP_STEPS.length];
			say(autoStopSeconds == 0 ? "Автоостановка записи выключена: стоп — R / P (камера) или Backspace (дубль)." : "Запись кончится сама, когда после движения простоишь " + autoStopSeconds + " с. K — другое значение.");
		} else if (FOLLOW.matches(e)) {
			followMove = !followMove;
			say(followMove ? "Полёт по движению: W/S вперёд-назад, A/D поворот, Пробел/Shift вверх-вниз; камера смотрит туда, куда летит. F — обычный полёт." : "Обычный полёт: W A S D, взгляд — ПКМ+мышь. F — полёт по движению.");
		} else if (ALONG.matches(e)) {
			toggleAlong();
		} else if (MUSIC.matches(e)) {
			askMusic();
		} else if (TO_START.matches(e)) {
			seek(0);
		} else if (TO_END.matches(e)) {
			seek(s.length());
		} else if (ZOOM_IN.matches(e)) {
			zoom(0.7f, cursor);
		} else if (ZOOM_OUT.matches(e)) {
			zoom(1 / 0.7f, cursor);
		} else if (LOOK.matches(e)) {
			setLookThrough(!lookThrough);
		} else if (CAM_KEY.matches(e)) {
			setCameraKey(cursor, easeAt(cursor));
		} else if (DELETE.matches(e)) {
			if (shift && hasSelection()) cutClips(selFrom, selTo);
			else if (shift) deleteClip();
			else if (hasSelection()) deleteRange(selFrom, selTo);
			else deleteAt(cursor);
		} else if (PREV_KEY.matches(e)) {
			jump(-1);
		} else if (NEXT_KEY.matches(e)) {
			jump(1);
		} else if (REC_CAM.matches(e)) {
			toggleRecordCamera();
		} else if (LINE.matches(e)) {
			askLine();
		} else if (SOUND.matches(e)) {
			askSound();
		} else if (ACTION.matches(e)) {
			askAction();
		} else if (LIGHT.matches(e)) {
			askLight();
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

	// -- инструменты --

	static void setTool(Tool value) {
		tool = value;
	}

	private static void cycleTool(int dir) {
		Tool[] all = Tool.values();
		tool = all[Math.floorMod(tool.ordinal() + dir, all.length)];
		say("Инструмент: " + tool.title + ". Enter — поставить на тике " + cursor + ".");
	}

	/** Enter: поставить выбранный инструмент на курсор времени. */
	static void useTool() {
		if (scene == null) return;
		switch (tool) {
			case CAMERA -> setCameraKey(cursor, easeAt(cursor));
			case LINE -> askLine();
			case SOUND -> askSound();
			case MUSIC -> askMusic();
			case ACTION -> askAction();
			case LIGHT -> askLight();
		}
	}

	private static void askMusic() {
		Minecraft.getInstance().gui.setScreen(new MusicPickerScreen());
	}

	/** Ввод по id звука игры или пакета ресурсов (из окна выбора музыки). */
	static void askMusicById() {
		PromptScreen.open("Саундтрек с тика " + cursor, "id [громкость [вход/выход_тиков [конец_тик]]], например minecraft:music.creative 0.8 20/90 600 (нарастание 20 тиков, затухание 90; одно число — для обоих); конец 0 — до конца сцены; «-» — убрать трек с курсора", "", text -> {
			Minecraft.getInstance().gui.setScreen(null);
			addMusic(text);
		});
	}

	/** Файл .mp3 выбран в окне: спросить громкость, плавность и конец. */
	static void askMusicFile(String name) {
		PromptScreen.open("Музыка " + name + " с тика " + cursor, "громкость [вход/выход_тиков [конец_тик]], например 1 20/90 600 (нарастание 20 тиков, затухание 90; одно число — для обоих); конец 0 — до конца сцены", "1 0 0", text -> {
			Minecraft.getInstance().gui.setScreen(null);
			addMusicFile(name, text);
		});
	}

	/** «40» — нарастание и затухание по 40 тиков; «20/90» — нарастание 20, затухание 90. Возвращает {вход, выход}; выход −1 — как вход. */
	private static int[] parseFades(String token) {
		String[] parts = token.split("/");
		int in = Math.max(0, Integer.parseInt(parts[0].trim()));
		int out = parts.length > 1 ? Math.max(0, Integer.parseInt(parts[1].trim())) : -1;
		return new int[] {in, out};
	}

	private static void addMusicFile(String name, String text) {
		String[] p = text.trim().split("\\s+");
		try {
			float volume = p.length > 0 && !p[0].isEmpty() ? Float.parseFloat(p[0].replace(',', '.')) : 1f;
			int[] fades = p.length > 1 ? parseFades(p[1]) : new int[] {0, -1};
			int end = p.length > 2 ? Integer.parseInt(p[2]) : 0;
			if (end != 0 && end <= cursor) {
				say("Конец (" + end + ") должен быть позже начала (" + cursor + ") или 0.");
				return;
			}
			touch(cursor);
			if (end > 0) touch(end);
			List<MusicKey> keys = new ArrayList<>(scene().music());
			keys.add(new MusicKey(cursor, end, MusicKey.NONE, Mth.clamp(volume, 0f, 2f), fades[0], fades[1], name));
			edit(scene().withMusic(keys));
			say("Музыка " + name + " с тика " + cursor + (end > 0 ? " до " + end : " до конца сцены") + ". При сохранении сцены хост переведёт файл в ogg и положит в пак мира: зрителям mp3 не нужен.");
		} catch (NumberFormatException e) {
			say("Громкость — число; вход/выход плавности (например 20/90) и конец — целые тики.");
		}
	}

	private static void addMusic(String text) {
		String[] p = text.trim().split("\\s+");
		if (p[0].equals("-")) {
			// «-» — убрать трек, начатый на курсоре
			int at = cursor;
			edit(scene().withMusic(scene().music().stream().filter(m -> m.tick() != at).toList()));
			return;
		}
		Identifier sound = Identifier.tryParse(p[0]);
		if (sound == null) {
			say("Не id звука: " + p[0]);
			return;
		}
		try {
			float volume = p.length > 1 ? Float.parseFloat(p[1].replace(',', '.')) : 1f;
			int[] fades = p.length > 2 ? parseFades(p[2]) : new int[] {0, -1};
			int end = p.length > 3 ? Integer.parseInt(p[3]) : 0;
			if (end != 0 && end <= cursor) {
				say("Конец (" + end + ") должен быть позже начала (" + cursor + ") или 0.");
				return;
			}
			touch(cursor);
			if (end > 0) touch(end);
			List<MusicKey> keys = new ArrayList<>(scene().music());
			keys.add(new MusicKey(cursor, end, sound, Mth.clamp(volume, 0f, 2f), fades[0], fades[1], ""));
			edit(scene().withMusic(keys));
			if (Minecraft.getInstance().getSoundManager().getSoundEvent(sound) == null) say("Такого звука нет среди загруженных (игра и пакеты ресурсов): трек добавлен, но молчит.");
			else say("Саундтрек " + sound + " с тика " + cursor + (end > 0 ? " до " + end : " до конца сцены") + ".");
		} catch (NumberFormatException e) {
			say("Громкость — число; вход/выход плавности (например 20/90) и конец — целые тики.");
		}
	}

	/** Кнопка в окошке кадра: переключить «вдоль пути» у этого кадра. */
	static void toggleAlongAt(int tick) {
		toggleAlongMany(List.of(tick));
	}

	/** Z / кнопка: следующие кинополосы (нет → 2.39 → 2.20 → 2.00 → 1.85 → нет); сохраняются в сцене. */
	static void cycleBars() {
		if (scene == null) return;
		int i = 0;
		while (i < ASPECTS.length - 1 && Math.abs(ASPECTS[i] - scene.aspect()) > 0.01f) i++;
		float next = ASPECTS[(i + 1) % ASPECTS.length];
		edit(scene().withAspect(next));
		say(next == 0f ? "Кинополосы выключены." : String.format(Locale.ROOT, "Кинополосы %.2f:1 — видны при пуске (P), взгляде из сцены (G) и показе. Z — следующие.", next));
	}

	/** Дальность показа сцены, по кругу: как у игрока, 16, 24, 32 чанка. */
	static final int[] DISTANCES = { 0, 16, 24, 32 };

	/** V / кнопка: следующая дальность показа (0 — как у игрока → 16 → 24 → 32 → 0); хранится в сцене, на время показа шире настроенной у зрителя. */
	static void cycleDistance() {
		if (scene == null) return;
		int i = 0;
		while (i < DISTANCES.length - 1 && DISTANCES[i] != scene.distance()) i++;
		int next = DISTANCES[(i + 1) % DISTANCES.length];
		edit(scene().withDistance(next));
		say(next == 0 ? "Дальность показа — как у игрока." : "Дальность показа " + next + " чанков (если у игрока меньше): грузятся чанки в конусе взгляда впереди камеры. Видно при показе, не в редакторе. V — следующая.");
	}

	static String distanceLabel() {
		return scene == null || scene.distance() <= 0 ? "Дальность: как у игрока (V)" : "Дальность: " + scene.distance() + " (V)";
	}

	static String barsLabel() {
		return scene == null || scene.aspect() <= 0f ? "Полосы: нет (Z)" : String.format(Locale.ROOT, "Полосы %.2f:1 (Z)", scene.aspect());
	}

	/** X: «вдоль пути» у кадров выделенной области, а если её нет — у выбранного кадра или кадра на курсоре. */
	static void toggleAlong() {
		Cutscene s = scene();
		java.util.function.IntPredicate hit;
		if (hasSelection()) {
			int a = selFrom, b = selTo;
			hit = t -> t >= a && t <= b;
		} else {
			int at = keyAt(cursor) != null ? cursor : selectedKey;
			hit = t -> t == at;
		}
		boolean anyOff = false, any = false;
		for (CameraKey k : s.camera()) {
			if (!hit.test(k.tick())) continue;
			any = true;
			if (!k.along()) anyOff = true;
		}
		if (!any) {
			say("Нет кадра камеры на курсоре: поставь кадр (Enter), выбери его кликом или выдели область.");
			return;
		}
		boolean value = anyOff;
		edit(s.withCamera(s.camera().stream().map(k -> hit.test(k.tick()) ? k.withAlong(value) : k).toList()));
		say(value ? "Камера смотрит вдоль пути от этих кадров до следующих." : "Взгляд вдоль пути выключен: камера смотрит по кадрам.");
	}

	private static void askLine() {
		PromptScreen.open("Реплика на тике " + cursor, "Текст; показывается внизу экрана", "", text -> {
			touch(cursor);
			edit(scene().withLines(plus(scene().lines(), new LineKey(cursor, Math.max(40, 20 + text.length() * 2), Component.literal(text)))));
		});
	}

	private static void askSound() {
		PromptScreen.open("Звук на тике " + cursor, "id [громкость [высота]], например minecraft:entity.player.levelup 1 1", "", CutsceneEditor::addSound);
	}

	private static void askAction() {
		PromptScreen.open("Действие мира на тике " + cursor, "id [имя=значение …], например gameplaymod:lava_wave group=a", "", CutsceneEditor::addAction);
	}

	private static void askLight() {
		PromptScreen.open("Свет на тике " + cursor, "от -1 до 1: -1 чёрный экран, 0 как есть, 1 белый экран; «-» — убрать всю дорожку", "", CutsceneEditor::addLight);
	}

	// -- выделение области времени --

	static boolean hasSelection() {
		return selFrom >= 0 && selTo >= selFrom;
	}

	static void select(int a, int b) {
		selFrom = Math.max(0, Math.min(a, b));
		selTo = Math.max(0, Math.max(a, b));
	}

	/** Снять выделение. @return оно было */
	static boolean clearSelection() {
		boolean had = selFrom >= 0;
		selFrom = selTo = -1;
		return had;
	}

	// -- такт --

	static void tick(Minecraft mc) {
		Cutscene s = scene;
		if (s == null) return;
		if (mc.gui.screen() == null) {
			if (!focused) {
				mc.gui.setScreen(new EditorScreen());
				return;
			}
			if (!mc.mouseHandler.isMouseGrabbed() && mc.isWindowActive()) mc.mouseHandler.grabMouse();
		} else if (!(mc.gui.screen() instanceof EditorScreen)) {
			// открыто окно ввода: время стоит на месте, камера не летит, музыка стихает (пуск — заново)
			prevPos = pos;
			if (playing) {
				playing = recordingCamera = armed = false;
				Soundtrack.stopAll();
			}
			return;
		}
		if (!playing) Soundtrack.stopAll();
		// группа правок кончается, когда запись пролёта остановлена любым способом, а перетаскивание отпущено
		if (grouping && !recordingCamera && !dragging) grouping = false;
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

	private static void remember() {
		if (grouping || scene == null) return;
		undo.push(scene);
		while (undo.size() > UNDO_MAX) undo.removeLast();
		redo.clear();
	}

	/** Начать группу правок (перетаскивание, запись пролёта): отмена вернёт всё разом. */
	static void beginGroup(boolean drag) {
		remember();
		grouping = true;
		dragging = drag;
	}

	static void endGroup() {
		grouping = false;
		dragging = false;
	}

	private static void swap(java.util.ArrayDeque<Cutscene> from, java.util.ArrayDeque<Cutscene> to, String what) {
		if (from.isEmpty()) {
			say(what.equals("отменено") ? "Нечего отменять." : "Нечего повторять.");
			return;
		}
		to.push(scene());
		Cutscene back = from.pop();
		grouping = true;
		edit(back);
		grouping = false;
		say(what.substring(0, 1).toUpperCase(Locale.ROOT) + what.substring(1) + " (осталось: " + from.size() + ").");
	}

	private static void edit(Cutscene changed) {
		remember();
		scene = changed;
		CutsceneClient.scene = changed;
		Puppets.retrack(changed, null);
		dirty = true;
		if (selectedKey >= 0 && changed.camera().stream().noneMatch(k -> k.tick() == selectedKey)) selectedKey = -1;
		if (Minecraft.getInstance().gui.screen() instanceof EditorScreen screen) screen.refresh();
	}

	/** Растянуть сцену, если кадр или запись упали за её конец. */
	private static void touch(int tick) {
		if (tick <= scene().length()) return;
		// растяжка сцены не отдельный шаг отмены: за ней идёт сама правка
		boolean was = grouping;
		grouping = true;
		edit(scene().withLength(tick));
		grouping = was;
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
		if (!focused) return;
		// взялся за управление — взгляд из сцены сам выключается, камера летит с того места, откуда смотрели
		if (lookThrough && (down(mc, GLFW.GLFW_KEY_W) || down(mc, GLFW.GLFW_KEY_A) || down(mc, GLFW.GLFW_KEY_S) || down(mc, GLFW.GLFW_KEY_D) || down(mc, GLFW.GLFW_KEY_SPACE) || down(mc, GLFW.GLFW_KEY_LEFT_SHIFT))) setLookThrough(false);
		if (!flying()) return;
		boolean ctrl = down(mc, GLFW.GLFW_KEY_LEFT_CONTROL);
		double speed = ctrl ? 1.0 : 0.3;
		if (followMove) {
			flyFollowing(mc, speed);
			return;
		}
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

	/** Полёт по движению: поворот A / D, ход вперёд-назад по взгляду, вверх-вниз; взгляд плавно ложится на направление хода вперёд. */
	private static void flyFollowing(Minecraft mc, double speed) {
		double fwd = (down(mc, GLFW.GLFW_KEY_W) ? 1 : 0) - (down(mc, GLFW.GLFW_KEY_S) ? 1 : 0);
		double up = (down(mc, GLFW.GLFW_KEY_SPACE) ? 1 : 0) - (down(mc, GLFW.GLFW_KEY_LEFT_SHIFT) ? 1 : 0);
		boolean moved = false;
		if (down(mc, GLFW.GLFW_KEY_A)) {
			yaw -= 3f;
			moved = true;
		}
		if (down(mc, GLFW.GLFW_KEY_D)) {
			yaw += 3f;
			moved = true;
		}
		Vec3 look = Vec3.directionFromRotation(pitch, yaw);
		Vec3 v = look.scale(fwd).add(0, up, 0);
		if (v.lengthSqr() > 1e-9) {
			pos = pos.add(v.normalize().scale(speed));
			moved = true;
			if (fwd > 0) {
				// летим вперёд: взгляд ложится на направление хода (с Пробелом — вверх, с Shift — вниз)
				Vec3 d = v.normalize();
				float targetYaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
				float targetPitch = (float) Math.toDegrees(-Math.asin(Mth.clamp(d.y, -1, 1)));
				yaw += Mth.wrapDegrees(targetYaw - yaw) * 0.2f;
				pitch = Mth.clamp(pitch + (targetPitch - pitch) * 0.2f, -89f, 89f);
			}
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
		if (recordingCamera) lastMoveTick = cursor;
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
			if (autoStopSeconds > 0 && cursor - lastMoveTick >= autoStopSeconds * 20) stopCameraRecordingAuto();
			return;
		}
		if (hasSelection() && cursor > selTo) {
			// выделенная область играет по кругу
			cursor = selFrom;
			CutsceneClient.tick = cursor;
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
		CameraKey key = new CameraKey(tick, pos.subtract(origin), yaw, pitch, roll, fov, ease, keyAt(tick) != null && keyAt(tick).along());
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
			endGroup();
			say("Запись камеры остановлена.");
			return;
		}
		armed = true;
		playing = false;
		lookThrough = false;
		say("Запись камеры с тика " + cursor + ": двинь камеру — пойдёт время. Кадры после курсора будут заменены. R или P — стоп.");
	}

	/** Камера простояла дольше порога: запись кончается, ожидание на конце отрезается (кадры после последнего движения убираются, длина — как была или по последнему движению). */
	private static void stopCameraRecordingAuto() {
		int last = lastMoveTick;
		List<CameraKey> keys = new ArrayList<>();
		for (CameraKey k : scene().camera()) if (k.tick() <= last) keys.add(k);
		recordingCamera = armed = playing = false;
		cursor = last;
		CutsceneClient.tick = cursor;
		edit(scene().withCamera(keys));
		setCameraKey(last, Easings.LINEAR);
		edit(scene().withLength(Math.max(lengthBeforeRecording, last)));
		endGroup();
		say("Запись камеры остановлена сама: камера стоит " + autoStopSeconds + " с. Кадры до тика " + last + " сохранены.");
	}

	private static void startCameraRecording() {
		armed = false;
		List<CameraKey> keep = new ArrayList<>();
		for (CameraKey k : scene().camera()) if (k.tick() < cursor) keep.add(k);
		beginGroup(false);
		lengthBeforeRecording = scene().length();
		lastMoveTick = cursor;
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
			setLookThrough(true);
			return;
		}
	}

	/** Выбрать один кадр (ПКМ по ромбику): область снимается, окошко показывает только его. */
	static void selectKey(int tick) {
		selectedKey = tick;
		selFrom = selTo = -1;
		refreshScreen();
	}

	static void refreshScreen() {
		if (Minecraft.getInstance().gui.screen() instanceof EditorScreen screen) screen.refresh();
	}

	/** Тики выбранных кадров камеры: при выделенной области (ПКМ-тянуть или Shift+ЛКМ-тянуть) — все кадры в ней, иначе выбранный кадр. Над ними работают общие кнопки окошка. */
	static List<Integer> selectedKeyTicks() {
		Cutscene s = scene;
		if (s == null) return List.of();
		if (hasSelection()) return s.camera().stream().map(CameraKey::tick).filter(t -> t >= selFrom && t <= selTo).toList();
		return selectedKey >= 0 && keyAt(selectedKey) != null ? List.of(selectedKey) : List.of();
	}

	/** Область выделена мышью до конца: пустая и без кадров сбрасывается, иначе окошко перестраивается под выбранные кадры. */
	static void finishAreaSelect() {
		if (hasSelection() && selFrom == selTo && selectedKeyTicks().isEmpty()) clearSelection();
		selectedKey = -1;
		refreshScreen();
	}

	static void setEase(int tick, Identifier ease) {
		setEaseMany(List.of(tick), ease);
	}

	/** Одна функция сглаживания всем выбранным кадрам разом (одним шагом отмены). */
	static void setEaseMany(List<Integer> ticks, Identifier ease) {
		java.util.Set<Integer> set = new HashSet<>(ticks);
		edit(scene().withCamera(scene().camera().stream().map(k -> set.contains(k.tick()) ? k.withEase(ease) : k).toList()));
	}

	/** Общая функция выбранных кадров; {@code null} — у них разные. */
	@Nullable
	static Identifier commonEase(List<Integer> ticks) {
		Identifier common = null;
		for (int t : ticks) {
			CameraKey k = keyAt(t);
			if (k == null) continue;
			if (common == null) common = k.ease();
			else if (!common.equals(k.ease())) return null;
		}
		return common;
	}

	static void cycleEaseMany(List<Integer> ticks, int dir) {
		if (ticks.isEmpty()) return;
		CameraKey first = keyAt(ticks.get(0));
		if (first == null) return;
		List<Identifier> ids = Easings.ids();
		Identifier base = commonEase(ticks) != null ? commonEase(ticks) : first.ease();
		setEaseMany(ticks, ids.get(Math.floorMod(ids.indexOf(base) + dir, ids.size())));
	}

	/** «Вдоль пути» у выбранных кадров: если хоть у одного выключено — включить всем, иначе выключить всем. */
	static void toggleAlongMany(List<Integer> ticks) {
		java.util.Set<Integer> set = new HashSet<>(ticks);
		boolean value = scene().camera().stream().anyMatch(k -> set.contains(k.tick()) && !k.along());
		edit(scene().withCamera(scene().camera().stream().map(k -> set.contains(k.tick()) ? k.withAlong(value) : k).toList()));
		say(value ? "Взгляд вдоль пути включён у " + set.size() + " кадр." : "Взгляд по кадрам у " + set.size() + " кадр.");
	}

	/** Сдвинуть все выбранные кадры (область) на {@code delta} тиков, не задев чужие; область уезжает вместе с ними. */
	static boolean moveSelectedKeys(int delta) {
		List<Integer> ticks = selectedKeyTicks();
		if (delta == 0 || ticks.isEmpty() || !hasSelection()) return false;
		java.util.Set<Integer> set = new HashSet<>(ticks);
		if (Collections.min(ticks) + delta < 0) return false;
		for (CameraKey k : scene().camera()) {
			if (!set.contains(k.tick()) && set.contains(k.tick() - delta)) return false;
		}
		touch(Collections.max(ticks) + delta);
		edit(scene().withCamera(scene().camera().stream().map(k -> set.contains(k.tick()) ? k.atTick(k.tick() + delta) : k).toList()));
		selFrom += delta;
		selTo += delta;
		return true;
	}

	/** Перенести кадр на текущий вид свободной камеры. */
	static void refixKey(int tick) {
		setCameraKey(tick, easeAt(tick));
	}

	static void deleteKey(int tick) {
		deleteKeys(List.of(tick));
	}

	static void deleteKeys(List<Integer> ticks) {
		java.util.Set<Integer> set = new HashSet<>(ticks);
		edit(scene().withCamera(scene().camera().stream().filter(k -> !set.contains(k.tick())).toList()));
		selectedKey = -1;
		clearSelection();
		refreshScreen();
	}

	@Nullable
	static CameraKey keyAt(int tick) {
		if (scene == null) return null;
		for (CameraKey k : scene.camera()) if (k.tick() == tick) return k;
		return null;
	}

	/** Следующая / предыдущая функция реестра для кадра. */
	static void cycleEase(int tick, int dir) {
		cycleEaseMany(List.of(tick), dir);
	}

	/** Добавить в реестр свою кривую Безье и назначить её кадру. Ввод: {@code x1 y1 x2 y2 [имя]}. */
	static void addBezier(int tick, String text) {
		addBezier(List.of(tick), text);
	}

	/** То же для нескольких выбранных кадров сразу. */
	static void addBezier(List<Integer> ticks, String text) {
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
			setEaseMany(ticks, eid);
			say("Функция " + eid + " (" + b.text() + ") добавлена в реестр.");
		} catch (NumberFormatException e) {
			say("x1 y1 x2 y2 — числа (x от 0 до 1).");
		}
	}

	// -- пользовательские функции: data/behaviormod/easings.json --

	private static java.nio.file.Path easingFile() {
		return com.smeshariki.behavior.ModData.moved(com.smeshariki.behavior.ModData.config("behaviormod", "easings.json"), com.smeshariki.behavior.ModData.dir().resolve("easings.json"));
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
		deleteRange(tick, tick);
	}

	/** Убрать всё, что стоит на тиках {@code from..to} включительно (дорожки камеры, реплик, звуков, действий, света; клипы актёров не трогает). */
	private static void deleteRange(int from, int to) {
		Cutscene s = scene();
		int before = s.camera().size() + s.lines().size() + s.sounds().size() + s.music().size() + s.actions().size() + s.light().size();
		Cutscene out = s.withCamera(s.camera().stream().filter(k -> k.tick() < from || k.tick() > to).toList())
				.withLines(s.lines().stream().filter(k -> k.tick() < from || k.tick() > to).toList())
				.withSounds(s.sounds().stream().filter(k -> k.tick() < from || k.tick() > to).toList())
				.withMusic(s.music().stream().filter(k -> k.tick() < from || k.tick() > to).toList())
				.withActions(s.actions().stream().filter(k -> k.tick() < from || k.tick() > to).toList())
				.withLight(s.light().stream().filter(k -> k.tick() < from || k.tick() > to).toList());
		int after = out.camera().size() + out.lines().size() + out.sounds().size() + out.music().size() + out.actions().size() + out.light().size();
		String where = from == to ? "на тике " + from : "на тиках " + from + "–" + to;
		if (after == before) {
			say("Тут (" + where + ") ничего нет.");
			return;
		}
		edit(out);
		clearSelection();
		say("Убрано " + where + ": " + (before - after) + ".");
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
			keys.add(new LightKey(cursor, Mth.clamp(d, -1f, 1f)));
			touch(cursor);
			edit(scene().withLight(keys));
		} catch (NumberFormatException e) {
			say("Свет — число от -1 (чёрный экран) до 1 (белый), 0 — как есть.");
		}
	}

	// -- копирование, вырезание клипов, перенос отметок --

	/** Копировать отметки выделенной области (кадры камеры, реплики, звуки, действия, свет). */
	private static void copySelection() {
		if (!hasSelection()) {
			say("Сначала выдели область: Shift + тянуть по полосе (Ctrl+A — всё).");
			return;
		}
		Cutscene s = scene();
		int a = selFrom, b = selTo;
		clipboard = Cutscene.empty(b - a, null)
				.withCamera(s.camera().stream().filter(k -> k.tick() >= a && k.tick() <= b).map(k -> k.atTick(k.tick() - a)).toList())
				.withLines(s.lines().stream().filter(k -> k.tick() >= a && k.tick() <= b).map(k -> new LineKey(k.tick() - a, k.duration(), k.text())).toList())
				.withSounds(s.sounds().stream().filter(k -> k.tick() >= a && k.tick() <= b).map(k -> new SoundKey(k.tick() - a, k.sound(), k.volume(), k.pitch())).toList())
				.withMusic(s.music().stream().filter(k -> k.tick() >= a && k.tick() <= b).map(k -> k.withTick(k.tick() - a)).toList())
				.withActions(s.actions().stream().filter(k -> k.tick() >= a && k.tick() <= b).map(k -> new ActionKey(k.tick() - a, k.action(), k.args())).toList())
				.withLight(s.light().stream().filter(k -> k.tick() >= a && k.tick() <= b).map(k -> new LightKey(k.tick() - a, k.level())).toList());
		clipboardSpan = b - a;
		int n = clipboard.camera().size() + clipboard.lines().size() + clipboard.sounds().size() + clipboard.music().size() + clipboard.actions().size() + clipboard.light().size();
		say("Скопировано отметок: " + n + " (" + a + "–" + b + "). Ctrl+V — вставить на курсор.");
	}

	/** Вставить скопированное так, чтобы начало куска пришлось на курсор; кадр камеры или света на том же тике заменяется. */
	private static void paste() {
		Cutscene c = clipboard;
		if (c == null) {
			say("Буфер пуст: выдели область и Ctrl+C.");
			return;
		}
		Cutscene s = scene();
		int at = cursor;
		List<CameraKey> camera = new ArrayList<>(s.camera());
		for (CameraKey k : c.camera()) {
			camera.removeIf(o -> o.tick() == at + k.tick());
			camera.add(k.atTick(at + k.tick()));
		}
		List<LightKey> light = new ArrayList<>(s.light());
		for (LightKey k : c.light()) {
			light.removeIf(o -> o.tick() == at + k.tick());
			light.add(new LightKey(at + k.tick(), k.level()));
		}
		List<LineKey> lines = new ArrayList<>(s.lines());
		for (LineKey k : c.lines()) lines.add(new LineKey(at + k.tick(), k.duration(), k.text()));
		List<SoundKey> sounds = new ArrayList<>(s.sounds());
		for (SoundKey k : c.sounds()) sounds.add(new SoundKey(at + k.tick(), k.sound(), k.volume(), k.pitch()));
		List<MusicKey> music = new ArrayList<>(s.music());
		for (MusicKey k : c.music()) music.add(k.withTick(at + k.tick()));
		List<ActionKey> actions = new ArrayList<>(s.actions());
		for (ActionKey k : c.actions()) actions.add(new ActionKey(at + k.tick(), k.action(), k.args()));
		touch(at + clipboardSpan);
		edit(scene().withCamera(camera).withLight(light).withLines(lines).withSounds(sounds).withMusic(music).withActions(actions));
		select(at, at + clipboardSpan);
		say("Вставлено на тик " + at + ". Выделено вставленное.");
	}

	/** Вырезать кадры актёров (всех) на тиках {@code from..to}: клипы делятся, остаются куски по краям. */
	private static void cutClips(int from, int to) {
		Cutscene s = scene();
		List<ActorTrack> tracks = new ArrayList<>();
		boolean changed = false;
		for (ActorTrack t : s.actors()) {
			List<Clip> clips = new ArrayList<>();
			for (Clip c : t.clips()) {
				if (c.end() <= from || c.start() > to) {
					clips.add(c);
				} else {
					clips.addAll(c.without(from, to));
					changed = true;
				}
			}
			tracks.add(t.withClips(clips));
		}
		if (!changed) {
			say("В области " + from + "–" + to + " нет клипов актёров.");
			return;
		}
		edit(s.withActors(tracks));
		clearSelection();
		say("Клипы актёров вырезаны на тиках " + from + "–" + to + ".");
	}

	/** Длительность сцены, тиков (перетаскивание линии конца и J): не меньше 1; отметки за новым концом остаются, редактор о них предупредит при проверке сцены. */
	static void setLength(int ticks) {
		if (scene == null) return;
		edit(scene().withLength(Math.max(1, Math.min(Cutscene.MAX_LENGTH, ticks))));
	}

	/** Тики отметок строки полосы (0 камера, потом актёры, реплики, звуки, музыка, действия, свет); у актёров — пусто. */
	static List<Integer> markTicks(int row) {
		Cutscene s = scene;
		if (s == null) return List.of();
		int n = s.actors().size();
		if (row == 0) return s.camera().stream().map(CameraKey::tick).toList();
		if (row == n + 1) return s.lines().stream().map(LineKey::tick).toList();
		if (row == n + 2) return s.sounds().stream().map(SoundKey::tick).toList();
		if (row == n + 3) return s.music().stream().map(MusicKey::tick).toList();
		if (row == n + 4) return s.actions().stream().map(ActionKey::tick).toList();
		if (row == n + 5) return s.light().stream().map(LightKey::tick).toList();
		return List.of();
	}

	/** Отрезки (начало, конец) отметок-полос строки: реплики и музыка; у остальных строк — пусто. */
	static List<int[]> markSpans(int row) {
		Cutscene s = scene;
		if (s == null) return List.of();
		int n = s.actors().size();
		if (row == n + 1) return s.lines().stream().map(k -> new int[] { k.tick(), k.tick() + k.duration() }).toList();
		if (row == n + 3) return s.music().stream().map(k -> new int[] { k.tick(), k.endIn(s.length()) }).toList();
		return List.of();
	}

	/** Изменить длительность полосы (реплики или музыки), начавшейся на тике {@code start}: новый конец {@code end}. */
	static boolean resizeSpan(int row, int start, int end) {
		if (scene == null || end <= start) return false;
		int n = scene().actors().size();
		if (row == n + 1) {
			touch(end);
			edit(scene().withLines(scene().lines().stream().map(k -> k.tick() == start ? new LineKey(k.tick(), end - start, k.text()) : k).toList()));
			return true;
		}
		if (row == n + 3) {
			touch(end);
			edit(scene().withMusic(scene().music().stream().map(k -> k.tick() == start ? k.withEnd(end) : k).toList()));
			return true;
		}
		return false;
	}

	/** Переместить отметку строки {@code row} (0 камера, потом актёры, реплики, звуки, музыка, действия, свет) с тика на тик; камера и свет — по одному на тик. */
	static boolean moveMark(int row, int from, int to) {
		if (to < 0 || from == to) return false;
		Cutscene s = scene();
		int n = s.actors().size();
		if (row == 0) {
			if (s.camera().stream().anyMatch(k -> k.tick() == to)) return false;
			List<CameraKey> out = new ArrayList<>();
			for (CameraKey k : s.camera()) out.add(k.tick() == from ? k.atTick(to) : k);
			touch(to);
			edit(scene().withCamera(out));
			if (selectedKey == from) selectedKey = to;
		} else if (row == n + 1) {
			touch(to);
			edit(scene().withLines(scene().lines().stream().map(k -> k.tick() == from ? new LineKey(to, k.duration(), k.text()) : k).toList()));
		} else if (row == n + 2) {
			touch(to);
			edit(scene().withSounds(scene().sounds().stream().map(k -> k.tick() == from ? new SoundKey(to, k.sound(), k.volume(), k.pitch()) : k).toList()));
		} else if (row == n + 3) {
			touch(to);
			edit(scene().withMusic(scene().music().stream().map(k -> k.tick() == from ? k.withTick(to) : k).toList()));
		} else if (row == n + 4) {
			touch(to);
			edit(scene().withActions(scene().actions().stream().map(k -> k.tick() == from ? new ActionKey(to, k.action(), k.args()) : k).toList()));
		} else if (row == n + 5) {
			if (s.light().stream().anyMatch(k -> k.tick() == to)) return false;
			touch(to);
			edit(scene().withLight(scene().light().stream().map(k -> k.tick() == from ? new LightKey(to, k.level()) : k).toList()));
		} else {
			return false;
		}
		return true;
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
		s = WorldMusicPack.prepare(s.withEasings(used));
		scene = s;
		CutsceneClient.scene = s;
		for (CutsceneBlob b : CutsceneBlob.split(id, CutsceneBlob.SAVE, origin, 0, "", s)) ClientPlayNetworking.send(b);
		dirty = false;
		say("Отправлено на сохранение.");
		WorldMusicPack.export(s);
	}

	/** Записать дубль с курсора: сохранить правки, выйти из редактора (сервер вернёт его после дубля). */
	private static void take() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		save();
		String command = "cutscene record " + id + " " + cursor + " true " + autoStopSeconds;
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
