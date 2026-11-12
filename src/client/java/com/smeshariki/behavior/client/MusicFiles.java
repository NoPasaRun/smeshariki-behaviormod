package com.smeshariki.behavior.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.loader.api.FabricLoader;

/** Папка с музыкой сцен: {@code config/behaviormod/music/*.mp3} на машине того, кто смотрит сцену. В сцене лежит только имя файла, сам файл надо положить каждому зрителю. */
final class MusicFiles {
	private MusicFiles() {
	}

	static Path dir() {
		return com.smeshariki.behavior.ModData.moved(com.smeshariki.behavior.ModData.config("behaviormod", "music"), com.smeshariki.behavior.ModData.dir().resolve("music"));
	}

	/** Имена файлов .mp3 в папке (создаёт её, если нет), по алфавиту. */
	static List<String> list() {
		Path dir = dir();
		try {
			Files.createDirectories(dir);
			try (Stream<Path> files = Files.list(dir)) {
				return files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).filter(MusicFiles::isMp3).sorted(String.CASE_INSENSITIVE_ORDER).toList();
			}
		} catch (IOException e) {
			return List.of();
		}
	}

	static boolean isMp3(String name) {
		return name.toLowerCase(Locale.ROOT).endsWith(".mp3");
	}

	/** Путь к файлу по имени; {@code null}, если имя с путём (выйти из папки нельзя) или файла нет. */
	@Nullable
	static Path find(String name) {
		if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) return null;
		Path file = dir().resolve(name);
		return Files.isRegularFile(file) ? file : null;
	}
}
