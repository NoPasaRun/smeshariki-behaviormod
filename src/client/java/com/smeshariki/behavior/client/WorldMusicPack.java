package com.smeshariki.behavior.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.jetbrains.annotations.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.smeshariki.behavior.BehaviorMod;
import com.smeshariki.behavior.cutscene.Cutscene;
import com.smeshariki.behavior.cutscene.MusicKey;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Музыка сцен в ресурспаке мира (2026-11-21, по просьбе: «музыка должна ехать вместе с миром»). Игра 26.2 сама подключает {@code <мир>/resourcepacks/resources.zip}
 * ({@code LevelResource.MAP_RESOURCE_FILE}) при открытии мира, а экспорт и релиз увозят его вместе с миром.
 * <p>
 * При сохранении сцены ({@link #prepare}) у каждого трека-файла .mp3, который лежит у сохраняющего, появляется id звука
 * {@code smeshariki:music.<имя>_<сумма>} (сумма — от содержимого mp3: другой файл под тем же именем — другой звук). Затем ({@link #export}) на хосте
 * (мир на этой машине) недостающие звуки переводятся в ogg через {@code ffmpeg} и дописываются в {@code resources.zip} вместе с {@code sounds.json};
 * пак переподключается, звук сразу доступен. Гость мира на диске не видит: у него id в сцене появится, а ogg — когда хост сохранит сцену у себя.
 * Старые ogg из пака не удаляются (их могут ждать другие сцены).
 */
final class WorldMusicPack {
	static final String NAMESPACE = "smeshariki";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	/** Перевод и запись — по одному, не на потоке игры. */
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "behaviormod-music-pack");
		t.setDaemon(true);
		return t;
	});
	/** Сумма mp3 по пути и времени изменения: не читать мегабайты при каждом сохранении. */
	private static final Map<String, String> HASHES = new HashMap<>();
	/** Наш пак в менеджере пакетов сервера (меняется при каждом переподключении). */
	@Nullable
	private static UUID pushed;

	private WorldMusicPack() {
	}

	/** id звука для mp3-файла {@code name} из папки музыки; {@code null}, если файла нет у этого игрока. */
	@Nullable
	static Identifier soundFor(String name) {
		Path file = MusicFiles.find(name);
		if (file == null) return null;
		String hash = hash(file);
		if (hash == null) return null;
		String base = name.toLowerCase(Locale.ROOT);
		if (base.endsWith(".mp3")) base = base.substring(0, base.length() - 4);
		StringBuilder slug = new StringBuilder();
		for (char c : base.toCharArray()) {
			if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') slug.append(c);
			else if (!slug.isEmpty() && slug.charAt(slug.length() - 1) != '_') slug.append('_');
			if (slug.length() >= 32) break;
		}
		while (!slug.isEmpty() && slug.charAt(slug.length() - 1) == '_') slug.setLength(slug.length() - 1);
		String path = (slug.isEmpty() ? "" : slug + "_") + hash;
		return Identifier.fromNamespaceAndPath(NAMESPACE, "music." + path);
	}

	/** Путь ogg в паке для id звука {@code smeshariki:music.x}: {@code assets/smeshariki/sounds/music/x.ogg}. */
	private static String oggEntry(Identifier sound) {
		return "assets/" + NAMESPACE + "/sounds/music/" + sound.getPath().substring("music.".length()) + ".ogg";
	}

	@Nullable
	private static String hash(Path file) {
		try {
			String key = file.toAbsolutePath() + "|" + Files.getLastModifiedTime(file).toMillis() + "|" + Files.size(file);
			String known = HASHES.get(key);
			if (known != null) return known;
			MessageDigest sha = MessageDigest.getInstance("SHA-1");
			try (InputStream in = Files.newInputStream(file)) {
				byte[] buf = new byte[1 << 16];
				for (int n; (n = in.read(buf)) > 0;) sha.update(buf, 0, n);
			}
			String hex = HexFormat.of().formatHex(sha.digest()).substring(0, 10);
			HASHES.put(key, hex);
			return hex;
		} catch (IOException | NoSuchAlgorithmException e) {
			return null;
		}
	}

	/** Сцена перед отправкой на сохранение: трекам-файлам, которые есть у игрока, проставить id звука в паке мира. */
	static Cutscene prepare(Cutscene scene) {
		boolean changed = false;
		List<MusicKey> keys = new ArrayList<>();
		for (MusicKey k : scene.music()) {
			Identifier id = k.isFile() ? soundFor(k.file()) : null;
			if (id != null && !id.equals(k.sound())) {
				keys.add(new MusicKey(k.tick(), k.end(), id, k.volume(), k.fade(), k.fadeOut(), k.file()));
				changed = true;
			} else {
				keys.add(k);
			}
		}
		return changed ? scene.withMusic(keys) : scene;
	}

	/** Звук есть в загруженных ресурсах (пак мира подключён и ogg в нём есть). */
	static boolean loaded(Identifier sound) {
		return !sound.equals(MusicKey.NONE) && Minecraft.getInstance().getSoundManager().getSoundEvent(sound) != null;
	}

	/** На хосте: дописать в пак мира недостающие ogg для треков-файлов сцены. Итог — сообщением в редактор. */
	static void export(Cutscene scene) {
		Minecraft mc = Minecraft.getInstance();
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null) {
			if (scene.music().stream().anyMatch(MusicKey::isFile))
				CutsceneEditor.say("Музыка в паке мира появится, когда хост сохранит эту сцену у себя (перевести в ogg может только тот, у кого мир на диске).");
			return;
		}
		Path zip = server.getWorldPath(LevelResource.MAP_RESOURCE_FILE).toAbsolutePath().normalize();
		Map<Identifier, Path> wanted = new LinkedHashMap<>();
		for (MusicKey k : scene.music()) {
			if (!k.isFile() || k.sound().equals(MusicKey.NONE)) continue;
			Path file = MusicFiles.find(k.file());
			if (file != null) wanted.putIfAbsent(k.sound(), file);
		}
		if (wanted.isEmpty()) return;
		WORKER.execute(() -> {
			String result;
			boolean reload = false;
			try {
				int added = write(zip, wanted);
				reload = added > 0;
				result = added == 0 ? null : "Музыка в паке мира: добавлено " + added + " (ogg в " + zip.getFileName() + "), пак переподключается.";
			} catch (IOException | RuntimeException e) {
				BehaviorMod.LOGGER.warn("Пак музыки мира {} не записан", zip, e);
				result = "Музыка не записана в пак мира: " + e.getMessage() + ". Сцена будет играть mp3 только у тех, у кого есть файл.";
			}
			String message = result;
			boolean doReload = reload;
			mc.execute(() -> {
				if (message != null) CutsceneEditor.say(message);
				if (doReload) reconnect(zip);
			});
		});
	}

	/** Дописать недостающие звуки в пак. Возвращает, сколько ogg добавлено. Поток — {@link #WORKER}. */
	private static int write(Path zip, Map<Identifier, Path> wanted) throws IOException {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		if (Files.isRegularFile(zip)) {
			try (ZipFile in = new ZipFile(zip.toFile())) {
				for (ZipEntry e : java.util.Collections.list(in.entries())) {
					if (e.isDirectory()) continue;
					try (InputStream s = in.getInputStream(e)) {
						entries.put(e.getName(), s.readAllBytes());
					}
				}
			}
		}
		Map<Identifier, Path> missing = new LinkedHashMap<>();
		for (Map.Entry<Identifier, Path> w : wanted.entrySet())
			if (!entries.containsKey(oggEntry(w.getKey()))) missing.put(w.getKey(), w.getValue());
		if (missing.isEmpty()) return 0;

		String soundsName = "assets/" + NAMESPACE + "/sounds.json";
		JsonObject sounds = new JsonObject();
		if (entries.containsKey(soundsName)) {
			try {
				JsonElement old = JsonParser.parseString(new String(entries.get(soundsName), StandardCharsets.UTF_8));
				if (old.isJsonObject()) sounds = old.getAsJsonObject();
			} catch (RuntimeException e) {
				BehaviorMod.LOGGER.warn("sounds.json пака мира не читается, пишется заново", e);
			}
		}
		Path tmpDir = Files.createTempDirectory("behaviormod-music");
		int added = 0;
		try {
			for (Map.Entry<Identifier, Path> m : missing.entrySet()) {
				Path ogg = tmpDir.resolve("track.ogg");
				convert(m.getValue(), ogg);
				entries.put(oggEntry(m.getKey()), Files.readAllBytes(ogg));
				Files.deleteIfExists(ogg);
				JsonObject sound = new JsonObject();
				sound.addProperty("name", NAMESPACE + ":music/" + m.getKey().getPath().substring("music.".length()));
				sound.addProperty("stream", true);
				JsonArray list = new JsonArray();
				list.add(sound);
				JsonObject event = new JsonObject();
				event.addProperty("replace", true);
				event.add("sounds", list);
				sounds.add(m.getKey().getPath(), event);
				added++;
			}
		} finally {
			try (var left = Files.list(tmpDir)) {
				for (Path p : (Iterable<Path>) left::iterator) Files.deleteIfExists(p);
			}
			Files.deleteIfExists(tmpDir);
		}
		entries.put(soundsName, GSON.toJson(sounds).getBytes(StandardCharsets.UTF_8));
		if (!entries.containsKey("pack.mcmeta")) {
			JsonObject pack = new JsonObject();
			pack.addProperty("description", "Музыка сцен этого мира (BehaviorMod)");
			pack.addProperty("min_format", SharedConstants.RESOURCE_PACK_FORMAT_MAJOR);
			pack.addProperty("max_format", SharedConstants.RESOURCE_PACK_FORMAT_MAJOR);
			JsonObject meta = new JsonObject();
			meta.add("pack", pack);
			entries.put("pack.mcmeta", GSON.toJson(meta).getBytes(StandardCharsets.UTF_8));
		}
		Files.createDirectories(zip.getParent());
		Path tmp = zip.resolveSibling(zip.getFileName() + ".tmp");
		try (OutputStream os = Files.newOutputStream(tmp); ZipOutputStream out = new ZipOutputStream(os)) {
			for (Map.Entry<String, byte[]> e : entries.entrySet()) {
				out.putNextEntry(new ZipEntry(e.getKey()));
				out.write(e.getValue());
				out.closeEntry();
			}
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(tmp);
			throw e;
		}
		Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		BehaviorMod.LOGGER.info("Пак музыки мира {}: добавлено {} ogg", zip, added);
		return added;
	}

	/** mp3 → ogg (Vorbis). Нет ffmpeg или он не справился — исключение с понятным текстом. */
	private static void convert(Path mp3, Path ogg) throws IOException {
		Process p;
		try {
			p = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-i", mp3.toString(), "-vn", "-c:a", "libvorbis", "-q:a", "5", ogg.toString())
					.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		} catch (IOException e) {
			throw new IOException("ffmpeg не найден (нужен, чтобы перевести mp3 в ogg)", e);
		}
		try {
			if (!p.waitFor(10, TimeUnit.MINUTES)) {
				p.destroyForcibly();
				throw new IOException("ffmpeg не уложился в 10 минут: " + mp3.getFileName());
			}
		} catch (InterruptedException e) {
			p.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new IOException("прервано", e);
		}
		if (p.exitValue() != 0 || !Files.isRegularFile(ogg) || Files.size(ogg) == 0)
			throw new IOException("ffmpeg не перевёл " + mp3.getFileName() + " (код " + p.exitValue() + ")");
	}

	/**
	 * Переподключить пак мира, чтобы новые звуки загрузились: свой id в менеджере пакетов сервера (у ванили свой, если пак был при открытии мира;
	 * два одинаковых пака безвредны — у звуков {@code replace}). Игра перезагружает ресурсы, как при открытии мира.
	 */
	private static void reconnect(Path zip) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getSingleplayerServer() == null || !Files.isRegularFile(zip)) return;
		var packs = mc.getDownloadedPackSource();
		if (pushed != null) packs.popPack(pushed);
		else packs.configureForLocalWorld();
		pushed = UUID.randomUUID();
		packs.pushLocalPack(pushed, zip);
	}

	/** Вышли из мира: менеджер пакетов сервера всё сбросил сам. */
	static void forget() {
		pushed = null;
	}
}
