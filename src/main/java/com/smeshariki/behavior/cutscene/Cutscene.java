package com.smeshariki.behavior.cutscene;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Кат-сцена: длина в тиках и дорожки — камера, актёры (записи дублей), реплики, звуки, действия мира, свет. Файл датапака
 * {@code data/<мод>/cutscene/<имя>.json} или файл мира (сжатый NBT, пишет редактор). Время сцены — только счётчик тиков (плюс доля тика на клиенте),
 * без часов: сцену можно отрисовать покадрово в видео. Координаты — от точки привязки; {@code anchor} — где сцену записывали в мире (нет — берёт тот, кто показывает).
 */
public record Cutscene(int length, Optional<Vec3> anchor, List<CameraKey> camera, List<ActorTrack> actors, List<LineKey> lines, List<SoundKey> sounds, List<MusicKey> music, List<ActionKey> actions, List<LightKey> light, Map<Identifier, Easings.Bezier> easings, float aspect, int distance) {
	/** Предел дальности показа, чанков: выше серверный предел игры (ChunkMap.MAX_VIEW_DISTANCE) не пускает. */
	public static final int MAX_DISTANCE = 32;
	public static final Codec<Cutscene> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.intRange(1, Integer.MAX_VALUE).fieldOf("length").forGetter(Cutscene::length),
			Vec3.CODEC.optionalFieldOf("anchor").forGetter(Cutscene::anchor),
			CameraKey.CODEC.listOf().optionalFieldOf("camera", List.of()).forGetter(Cutscene::camera),
			ActorTrack.CODEC.listOf().optionalFieldOf("actors", List.of()).forGetter(Cutscene::actors),
			LineKey.CODEC.listOf().optionalFieldOf("lines", List.of()).forGetter(Cutscene::lines),
			SoundKey.CODEC.listOf().optionalFieldOf("sounds", List.of()).forGetter(Cutscene::sounds),
			MusicKey.CODEC.listOf().optionalFieldOf("music", List.of()).forGetter(Cutscene::music),
			ActionKey.CODEC.listOf().optionalFieldOf("actions", List.of()).forGetter(Cutscene::actions),
			LightKey.CODEC.listOf().optionalFieldOf("light", List.of()).forGetter(Cutscene::light),
			Codec.unboundedMap(Identifier.CODEC, Easings.Bezier.CODEC).optionalFieldOf("easings", Map.of()).forGetter(Cutscene::easings),
			Codec.floatRange(0f, 10f).optionalFieldOf("aspect", 0f).forGetter(Cutscene::aspect),
			Codec.intRange(0, MAX_DISTANCE).optionalFieldOf("distance", 0).forGetter(Cutscene::distance)
	).apply(i, Cutscene::new));


	/** Предел длины, тиков (почти 14 часов): только чтобы число не переполнилось, ограничением для работы не служит. */
	public static final int MAX_LENGTH = 1_000_000;

	/** Положение камеры в момент {@code time} тиков (доли — между тиками); кадры сортируются, до первого и после последнего камера стоит. */
	public record View(Vec3 pos, float yaw, float pitch, float roll, float fov) {
	}

	public Cutscene {
		camera = camera.stream().sorted(Comparator.comparingInt(CameraKey::tick)).toList();
		lines = lines.stream().sorted(Comparator.comparingInt(LineKey::tick)).toList();
		sounds = sounds.stream().sorted(Comparator.comparingInt(SoundKey::tick)).toList();
		music = music.stream().sorted(Comparator.comparingInt(MusicKey::tick)).toList();
		actions = actions.stream().sorted(Comparator.comparingInt(ActionKey::tick)).toList();
		light = light.stream().sorted(Comparator.comparingInt(LightKey::tick)).toList();
		easings = Map.copyOf(easings);
	}

	/** Пустая сцена заданной длины. */
	public static Cutscene empty(int length, Vec3 anchor) {
		return new Cutscene(length, Optional.ofNullable(anchor), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), 0f, 0);
	}

	public Cutscene withLength(int value) {
		return new Cutscene(value, anchor, camera, actors, lines, sounds, music, actions, light, easings, aspect, distance);
	}

	public Cutscene withAnchor(Vec3 value) {
		return new Cutscene(length, Optional.ofNullable(value), camera, actors, lines, sounds, music, actions, light, easings, aspect, distance);
	}

	public Cutscene withCamera(List<CameraKey> value) {
		return new Cutscene(length, anchor, value, actors, lines, sounds, music, actions, light, easings, aspect, distance);
	}

	public Cutscene withActors(List<ActorTrack> value) {
		return new Cutscene(length, anchor, camera, value, lines, sounds, music, actions, light, easings, aspect, distance);
	}

	public Cutscene withLines(List<LineKey> value) {
		return new Cutscene(length, anchor, camera, actors, value, sounds, music, actions, light, easings, aspect, distance);
	}

	public Cutscene withSounds(List<SoundKey> value) {
		return new Cutscene(length, anchor, camera, actors, lines, value, music, actions, light, easings, aspect, distance);
	}

	/** Дальность показа, чанков: на время показа зрители видят так далеко (но не ближе своей настройки); 0 — как у игрока. Предел {@link #MAX_DISTANCE}. */
	public Cutscene withDistance(int value) {
		return new Cutscene(length, anchor, camera, actors, lines, sounds, music, actions, light, easings, aspect, Mth.clamp(value, 0, MAX_DISTANCE));
	}

	/** Кинополосы сверху и снизу при показе: экран кадрируется до этого отношения ширины к высоте (2.39 — «кино»); 0 — без полос. */
	public Cutscene withAspect(float value) {
		return new Cutscene(length, anchor, camera, actors, lines, sounds, music, actions, light, easings, value, distance);
	}

	public Cutscene withMusic(List<MusicKey> value) {
		return new Cutscene(length, anchor, camera, actors, lines, sounds, value, actions, light, easings, aspect, distance);
	}

	public Cutscene withActions(List<ActionKey> value) {
		return new Cutscene(length, anchor, camera, actors, lines, sounds, music, value, light, easings, aspect, distance);
	}

	public Cutscene withEasings(Map<Identifier, Easings.Bezier> value) {
		return new Cutscene(length, anchor, camera, actors, lines, sounds, music, actions, light, value, aspect, distance);
	}

	public Cutscene withLight(List<LightKey> value) {
		return new Cutscene(length, anchor, camera, actors, lines, sounds, music, actions, value, easings, aspect, distance);
	}

	/** Что не так с описанием (пусто — всё хорошо). Сцена без камеры допустима: показывать её можно, камера останется у игрока. */
	public List<String> problems() {
		List<String> out = new ArrayList<>();
		for (int i = 1; i < camera.size(); i++) {
			if (camera.get(i).tick() == camera.get(i - 1).tick()) out.add("два кадра камеры на тике " + camera.get(i).tick());
		}
		for (CameraKey key : camera) {
			if (key.tick() > length) out.add("кадр камеры на тике " + key.tick() + " за концом сцены (" + length + ")");
			if (key.fov() < 1 || key.fov() > 170) out.add("угол обзора " + key.fov() + " на тике " + key.tick() + " вне 1..170");
		}
		for (ActorTrack track : actors) {
			if (!net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(track.character())) out.add("нет такого персонажа: " + track.character());
		}
		for (CameraKey key : camera) {
			if (!easings.containsKey(key.ease()) && Easings.get(key.ease()) == null) out.add("нет функции " + key.ease() + " (кадр на тике " + key.tick() + ")");
		}
		for (SoundKey sound : sounds) {
			if (sound.tick() > length) out.add("звук " + sound.sound() + " на тике " + sound.tick() + " за концом сцены");
		}
		for (MusicKey m : music) {
			if (m.tick() > length) out.add("музыка " + m.title() + " на тике " + m.tick() + " за концом сцены");
			if (m.end() > 0 && m.end() <= m.tick()) out.add("музыка " + m.title() + ": конец (" + m.end() + ") не позже начала (" + m.tick() + ")");
		}
		return out;
	}

	/** Есть ли у сцены своя камера. */
	public boolean hasCamera() {
		return !camera.isEmpty();
	}

	public View view(float time) {
		if (camera.isEmpty()) return new View(Vec3.ZERO, 0, 0, 0, 70);
		CameraKey last = camera.getLast();
		if (time >= last.tick()) {
			// последний кадр: по направлению подхода смотрим, только если «вдоль пути» включено у него самого; иначе у кадра свой взгляд, какой его зафиксировали
			// (раньше подменялось, если «вдоль» стояло у предыдущего: «Просмотр» только что зафиксированного последнего кадра показывал другое направление)
			View v = of(last);
			return camera.size() >= 2 && last.along() ? alongView(v, keyDir(camera.size() - 1), true) : v;
		}
		if (time <= camera.getFirst().tick()) {
			View v = of(camera.getFirst());
			return camera.getFirst().along() && camera.size() >= 2 ? alongView(v, keyDir(0), true) : v;
		}
		int i = 0;
		while (camera.get(i + 1).tick() <= time) i++;
		CameraKey a = camera.get(i);
		CameraKey b = camera.get(i + 1);
		float f = Easings.resolve(a.ease(), easings).apply((time - a.tick()) / (b.tick() - a.tick()));
		View v = smooth(i, f);
		if (!a.along() && !b.along()) return v;
		if (a.along() && b.along()) {
			Vec3 da = keyDir(i), db = keyDir(i + 1);
			return alongView(v, da.lerp(db, f), false);
		}
		// стык «вдоль пути» и кадра со своим взглядом: поворачиваем плавно от взгляда одного кадра к взгляду другого (а не рывком на тике кадра)
		float[] la = look(i), lb = look(i + 1);
		float w = f * f * (3 - 2 * f);
		return new View(v.pos(), la[0] + Mth.wrapDegrees(lb[0] - la[0]) * w, Mth.lerp(w, la[1], lb[1]), v.roll(), v.fov());
	}

	/** Взгляд кадра {@code j}, {@code {yaw, pitch}}: по пути, если у кадра «вдоль пути», иначе тот, что в нём записан. */
	private float[] look(int j) {
		CameraKey k = camera.get(j);
		if (!k.along()) return new float[] {k.yaw(), k.pitch()};
		View v = alongView(of(k), keyDir(j), false);
		return new float[] {v.yaw(), v.pitch()};
	}

	/**
	 * Вид на отрезке {@code i → i+1} при доле пути {@code f} (после функции сглаживания): кривая Эрмита с касательными Кэтмулла — Рома, то есть путь идёт ПЛАВНО через
	 * кадры, а не ломаной (скорость и направление на стыке отрезков не скачут). У крайних отрезков касательная — хорда; у сцены из двух кадров получается прямая. Повороты
	 * разворачиваются по короткой дуге, крен и угол обзора сглаживаются так же.
	 */
	private View smooth(int i, float f) {
		CameraKey a = camera.get(i), b = camera.get(i + 1);
		CameraKey before = i > 0 ? camera.get(i - 1) : null, after = i + 2 < camera.size() ? camera.get(i + 2) : null;
		double h = b.tick() - a.tick();
		double hBefore = before == null ? h : b.tick() - before.tick(), hAfter = after == null ? h : after.tick() - a.tick();
		Vec3 pBefore = before == null ? a.pos() : before.pos(), pAfter = after == null ? b.pos() : after.pos();
		Vec3 ma = b.pos().subtract(pBefore).scale(1.0 / hBefore);
		Vec3 mb = pAfter.subtract(a.pos()).scale(1.0 / hAfter);
		if (before == null) ma = b.pos().subtract(a.pos()).scale(1.0 / h);
		if (after == null) mb = b.pos().subtract(a.pos()).scale(1.0 / h);
		Vec3 pos = new Vec3(hermite(a.pos().x, b.pos().x, ma.x, mb.x, h, f), hermite(a.pos().y, b.pos().y, ma.y, mb.y, h, f), hermite(a.pos().z, b.pos().z, ma.z, mb.z, h, f));
		// углы: разворачиваем по короткой дуге вокруг кадра a, чтобы 350° → 10° шло через 0°
		double ya = a.yaw(), yb = ya + Mth.wrapDegrees(b.yaw() - ya);
		double yBefore = before == null ? ya : ya + Mth.wrapDegrees(before.yaw() - ya), yAfter = after == null ? yb : yb + Mth.wrapDegrees(after.yaw() - yb);
		float yaw = (float) scalar(ya, yb, yBefore, yAfter, before != null, after != null, h, hBefore, hAfter, f);
		float pitch = (float) scalar(a.pitch(), b.pitch(), before == null ? 0 : before.pitch(), after == null ? 0 : after.pitch(), before != null, after != null, h, hBefore, hAfter, f);
		float roll = (float) scalar(a.roll(), b.roll(), before == null ? 0 : before.roll(), after == null ? 0 : after.roll(), before != null, after != null, h, hBefore, hAfter, f);
		float fov = Mth.clamp((float) scalar(a.fov(), b.fov(), before == null ? 0 : before.fov(), after == null ? 0 : after.fov(), before != null, after != null, h, hBefore, hAfter, f), 1f, 170f);
		return new View(pos, yaw, Mth.clamp(pitch, -90f, 90f), roll, fov);
	}

	/** Эрмит по одной величине: значения на концах отрезка, значения у соседних кадров (если есть) для касательных. */
	private static double scalar(double va, double vb, double vBefore, double vAfter, boolean hasBefore, boolean hasAfter, double h, double hBefore, double hAfter, double u) {
		double chord = (vb - va) / h;
		double ma = hasBefore ? (vb - vBefore) / hBefore : chord;
		double mb = hasAfter ? (vAfter - va) / hAfter : chord;
		return hermite(va, vb, ma, mb, h, u);
	}

	private static double hermite(double p0, double p1, double m0, double m1, double h, double u) {
		double u2 = u * u, u3 = u2 * u;
		return (2 * u3 - 3 * u2 + 1) * p0 + (u3 - 2 * u2 + u) * h * m0 + (-2 * u3 + 3 * u2) * p1 + (u3 - u2) * h * m1;
	}

	/** Вид с теми же положением, креном и углом, но взглядом по {@code dir} (вдоль пути). */
	private static View alongView(View v, Vec3 dir, boolean unused) {
		if (dir.lengthSqr() < 1e-9) return v;
		Vec3 d = dir.normalize();
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(-Math.asin(Mth.clamp(d.y, -1, 1)));
		return new View(v.pos(), yaw, pitch, v.roll(), v.fov());
	}

	/**
	 * Направление пути в кадре {@code j}: среднее направлений соседних отрезков (на поворотах взгляд меняется плавно, а не рывком), у краёв — направление единственного
	 * отрезка. Отрезок без движения не учитывается; если двигаться некуда — куда камера смотрит в кадре.
	 */
	private Vec3 keyDir(int j) {
		Vec3 sum = Vec3.ZERO;
		if (j > 0) sum = sum.add(segDir(j - 1));
		if (j < camera.size() - 1) sum = sum.add(segDir(j));
		if (sum.lengthSqr() < 1e-9) {
			CameraKey k = camera.get(j);
			return Vec3.directionFromRotation(k.pitch(), k.yaw());
		}
		return sum.normalize();
	}

	private Vec3 segDir(int i) {
		Vec3 d = camera.get(i + 1).pos().subtract(camera.get(i).pos());
		return d.lengthSqr() < 1e-9 ? Vec3.ZERO : d.normalize();
	}

	/** Свет в момент {@code time}: −1 чёрный экран, 0 как есть, 1 белый; {@code NaN} — дорожки света нет. */
	public float lightLevel(float time) {
		if (light.isEmpty()) return Float.NaN;
		if (time <= light.getFirst().tick()) return light.getFirst().level();
		if (time >= light.getLast().tick()) return light.getLast().level();
		int i = 0;
		while (light.get(i + 1).tick() <= time) i++;
		LightKey a = light.get(i), b = light.get(i + 1);
		return Mth.lerp((time - a.tick()) / (b.tick() - a.tick()), a.level(), b.level());
	}

	private static View of(CameraKey k) {
		return new View(k.pos(), k.yaw(), k.pitch(), k.roll(), k.fov());
	}

	/** Поворот по короткой дуге: 350° → 10° идёт через 0°, а не назад. */
	private static float turn(float from, float to, float f) {
		return Frame.turn(from, to, f);
	}
}
