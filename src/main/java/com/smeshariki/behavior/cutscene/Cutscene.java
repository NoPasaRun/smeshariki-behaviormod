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
public record Cutscene(int length, Optional<Vec3> anchor, List<CameraKey> camera, List<ActorTrack> actors, List<LineKey> lines, List<SoundKey> sounds, List<ActionKey> actions, List<LightKey> light, Map<Identifier, Easings.Bezier> easings) {
	public static final Codec<Cutscene> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.intRange(1, Integer.MAX_VALUE).fieldOf("length").forGetter(Cutscene::length),
			Vec3.CODEC.optionalFieldOf("anchor").forGetter(Cutscene::anchor),
			CameraKey.CODEC.listOf().optionalFieldOf("camera", List.of()).forGetter(Cutscene::camera),
			ActorTrack.CODEC.listOf().optionalFieldOf("actors", List.of()).forGetter(Cutscene::actors),
			LineKey.CODEC.listOf().optionalFieldOf("lines", List.of()).forGetter(Cutscene::lines),
			SoundKey.CODEC.listOf().optionalFieldOf("sounds", List.of()).forGetter(Cutscene::sounds),
			ActionKey.CODEC.listOf().optionalFieldOf("actions", List.of()).forGetter(Cutscene::actions),
			LightKey.CODEC.listOf().optionalFieldOf("light", List.of()).forGetter(Cutscene::light),
			Codec.unboundedMap(Identifier.CODEC, Easings.Bezier.CODEC).optionalFieldOf("easings", Map.of()).forGetter(Cutscene::easings)
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
		actions = actions.stream().sorted(Comparator.comparingInt(ActionKey::tick)).toList();
		light = light.stream().sorted(Comparator.comparingInt(LightKey::tick)).toList();
		easings = Map.copyOf(easings);
	}

	/** Пустая сцена заданной длины. */
	public static Cutscene empty(int length, Vec3 anchor) {
		return new Cutscene(length, Optional.ofNullable(anchor), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
	}

	public Cutscene withLength(int value) {
		return new Cutscene(value, anchor, camera, actors, lines, sounds, actions, light, easings);
	}

	public Cutscene withAnchor(Vec3 value) {
		return new Cutscene(length, Optional.ofNullable(value), camera, actors, lines, sounds, actions, light, easings);
	}

	public Cutscene withCamera(List<CameraKey> value) {
		return new Cutscene(length, anchor, value, actors, lines, sounds, actions, light, easings);
	}

	public Cutscene withActors(List<ActorTrack> value) {
		return new Cutscene(length, anchor, camera, value, lines, sounds, actions, light, easings);
	}

	public Cutscene withLines(List<LineKey> value) {
		return new Cutscene(length, anchor, camera, actors, value, sounds, actions, light, easings);
	}

	public Cutscene withSounds(List<SoundKey> value) {
		return new Cutscene(length, anchor, camera, actors, lines, value, actions, light, easings);
	}

	public Cutscene withActions(List<ActionKey> value) {
		return new Cutscene(length, anchor, camera, actors, lines, sounds, value, light, easings);
	}

	public Cutscene withEasings(Map<Identifier, Easings.Bezier> value) {
		return new Cutscene(length, anchor, camera, actors, lines, sounds, actions, light, value);
	}

	public Cutscene withLight(List<LightKey> value) {
		return new Cutscene(length, anchor, camera, actors, lines, sounds, actions, value, easings);
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
		return out;
	}

	/** Есть ли у сцены своя камера. */
	public boolean hasCamera() {
		return !camera.isEmpty();
	}

	public View view(float time) {
		if (camera.isEmpty()) return new View(Vec3.ZERO, 0, 0, 0, 70);
		CameraKey last = camera.getLast();
		if (time >= last.tick()) return of(last);
		if (time <= camera.getFirst().tick()) return of(camera.getFirst());
		int i = 0;
		while (camera.get(i + 1).tick() <= time) i++;
		CameraKey a = camera.get(i);
		CameraKey b = camera.get(i + 1);
		float f = Easings.resolve(a.ease(), easings).apply((time - a.tick()) / (b.tick() - a.tick()));
		return new View(a.pos().lerp(b.pos(), f), turn(a.yaw(), b.yaw(), f), Mth.lerp(f, a.pitch(), b.pitch()), Mth.lerp(f, a.roll(), b.roll()), Mth.lerp(f, a.fov(), b.fov()));
	}

	/** Затемнение света в момент {@code time}; {@code -1} — дорожки света нет. */
	public float darkness(float time) {
		if (light.isEmpty()) return -1;
		if (time <= light.getFirst().tick()) return light.getFirst().darkness();
		if (time >= light.getLast().tick()) return light.getLast().darkness();
		int i = 0;
		while (light.get(i + 1).tick() <= time) i++;
		LightKey a = light.get(i), b = light.get(i + 1);
		return Mth.lerp((time - a.tick()) / (b.tick() - a.tick()), a.darkness(), b.darkness());
	}

	private static View of(CameraKey k) {
		return new View(k.pos(), k.yaw(), k.pitch(), k.roll(), k.fov());
	}

	/** Поворот по короткой дуге: 350° → 10° идёт через 0°, а не назад. */
	private static float turn(float from, float to, float f) {
		return Frame.turn(from, to, f);
	}
}
