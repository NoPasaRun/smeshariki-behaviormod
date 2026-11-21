package com.smeshariki.behavior.client;

import java.util.ArrayList;
import java.util.List;

import com.smeshariki.behavior.cutscene.Cutscene;
import com.smeshariki.behavior.cutscene.MusicKey;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * Саундтрек сцены на клиенте (звуки игры и файлы .mp3 из {@link MusicFiles}): трек запускается, когда время сцены доходит до его тика ({@link #start}), и стихает сам к концу ({@link MusicKey#gain}); пока не нужен
 * (сцена кончилась, редактор на паузе или перемотан) — {@link #stopAll}. Играет как музыка, не привязан к месту.
 */
final class Soundtrack {
	private static final List<Track> PLAYING = new ArrayList<>();
	private static final List<FileTrack> FILES = new ArrayList<>();

	private Soundtrack() {
	}

	private static final class Track extends AbstractTickableSoundInstance {
		final MusicKey key;
		final Cutscene scene;

		Track(MusicKey key, Cutscene scene) {
			super(SoundEvent.createVariableRangeEvent(key.sound()), SoundSource.MUSIC, RandomSource.create());
			this.key = key;
			this.scene = scene;
			this.attenuation = SoundInstance.Attenuation.NONE;
			this.relative = true;
			this.volume = Math.max(0.0001f, key.gain(CutsceneClient.time(), scene.length()));
		}

		@Override
		public void tick() {
			if (CutsceneClient.scene != scene || CutsceneClient.mode() == CutsceneClient.Mode.NONE) {
				stop();
				return;
			}
			float g = key.gain(CutsceneClient.time(), scene.length());
			if (g <= 0f && CutsceneClient.time() >= key.endIn(scene.length())) stop();
			else volume = Math.max(0.0001f, g);
		}

		void halt() {
			stop();
		}
	}

	/** Это наш трек сцены (его фоновое заглушение ванильной музыки не касается). */
	static boolean isTrack(SoundInstance sound) {
		return sound instanceof Track;
	}

	/** Трек из файла .mp3: играет {@link Mp3Player}, громкость ему ставит {@link #tick}. */
	private static final class FileTrack {
		final MusicKey key;
		final Cutscene scene;
		final Mp3Player player;
		boolean reported;

		FileTrack(MusicKey key, Cutscene scene, java.nio.file.Path file, double offsetSeconds) {
			this.key = key;
			this.scene = scene;
			this.player = new Mp3Player(file, offsetSeconds, volume());
		}

		float volume() {
			Minecraft mc = Minecraft.getInstance();
			return key.gain(CutsceneClient.time(), scene.length()) * mc.options.getFinalSoundSourceVolume(SoundSource.MUSIC);
		}
	}

	/** Каждый такт: громкость файловых треков, остановка закончившихся и тех, чья сцена ушла. */
	static void tick() {
		for (java.util.Iterator<FileTrack> it = FILES.iterator(); it.hasNext();) {
			FileTrack t = it.next();
			boolean gone = CutsceneClient.scene != t.scene || CutsceneClient.mode() == CutsceneClient.Mode.NONE || CutsceneClient.time() >= t.key.endIn(t.scene.length());
			if (!t.player.error().isEmpty() && !t.reported) {
				t.reported = true;
				CutsceneEditor.say("Музыка " + t.key.file() + " не играет: " + t.player.error());
			}
			if (gone || t.player.finished()) {
				t.player.stop();
				it.remove();
			} else {
				t.player.gain(t.volume());
			}
		}
	}

	private static void play(MusicKey key, Cutscene scene, double offsetSeconds) {
		Minecraft mc = Minecraft.getInstance();
		if (key.isFile()) {
			java.nio.file.Path file = MusicFiles.find(key.file());
			// ogg из пака мира — у всех одинаково и через звук игры; mp3 — в редакторе (его можно начать с любого места) или когда ogg ещё нет
			boolean ogg = WorldMusicPack.loaded(key.sound());
			if (ogg && (file == null || CutsceneClient.mode() != CutsceneClient.Mode.EDIT)) {
				Track track = new Track(key, scene);
				PLAYING.add(track);
				mc.getSoundManager().play(track);
				return;
			}
			if (file == null) {
				CutsceneEditor.say("Нет музыки " + key.file() + ": ни в паке мира (хост ещё не сохранял сцену с этим файлом), ни в " + MusicFiles.dir() + ".");
				return;
			}
			FileTrack track = new FileTrack(key, scene, file, offsetSeconds);
			FILES.add(track);
			track.player.start();
		} else {
			Track track = new Track(key, scene);
			PLAYING.add(track);
			mc.getSoundManager().play(track);
		}
	}

	/** Запустить треки, чей тик пришёл: в {@code (from, to]}. */
	static void start(Cutscene scene, int from, int to) {
		for (MusicKey key : scene.music()) {
			if (key.tick() <= from || key.tick() > to) continue;
			play(key, scene, 0);
		}
	}

	/** Редактор начал играть посреди трека: звук не перемотать, поэтому трек стартует с начала сейчас. */
	static void startCovering(Cutscene scene, int time) {
		stopAll();
		for (MusicKey key : scene.music()) {
			if (key.tick() > time || time >= key.endIn(scene.length())) continue;
			// файл можно начать с нужного места, звук игры — только сначала
			play(key, scene, key.isFile() ? (time - key.tick()) / 20.0 : 0);
		}
	}

	static void stopAll() {
		for (Track t : PLAYING) t.halt();
		PLAYING.clear();
		for (FileTrack t : FILES) t.player.stop();
		FILES.clear();
	}
}
