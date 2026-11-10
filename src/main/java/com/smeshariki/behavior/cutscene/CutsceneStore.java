package com.smeshariki.behavior.cutscene;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import com.smeshariki.behavior.BehaviorMod;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Сцены мира, записанные и правленные в игре: по файлу на сцену, {@code <мир>/behaviormod/cutscenes/<пространство>/<имя>.dat}, сжатый NBT.
 * Сцены из jar и датапаков лежат отдельно и только читаются; сцена мира с тем же id их закрывает.
 */
final class CutsceneStore {
	private CutsceneStore() {
	}

	private static Path root(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("behaviormod").resolve("cutscenes");
	}

	private static Path file(MinecraftServer server, Identifier id) {
		return root(server).resolve(id.getNamespace()).resolve(id.getPath() + ".dat");
	}

	static Map<Identifier, Cutscene> loadAll(MinecraftServer server) {
		Map<Identifier, Cutscene> out = new HashMap<>();
		Path root = root(server);
		if (!Files.isDirectory(root)) return out;
		try (Stream<Path> files = Files.walk(root)) {
			for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".dat")).toList()) {
				Path rel = root.relativize(p);
				if (rel.getNameCount() < 2) continue;
				String namespace = rel.getName(0).toString();
				String path = root.resolve(namespace).relativize(p).toString().replace('\\', '/');
				path = path.substring(0, path.length() - ".dat".length());
				Identifier id = Identifier.tryBuild(namespace, path);
				if (id == null) continue;
				try {
					CompoundTag tag = NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap());
					Cutscene.CODEC.parse(NbtOps.INSTANCE, tag).resultOrPartial(e -> BehaviorMod.LOGGER.warn("Сцена {} не прочиталась: {}", id, e)).ifPresent(s -> out.put(id, s));
				} catch (IOException e) {
					BehaviorMod.LOGGER.warn("Сцена {} не прочиталась", id, e);
				}
			}
		} catch (IOException e) {
			BehaviorMod.LOGGER.warn("Папка сцен мира не прочиталась", e);
		}
		return out;
	}

	/** Пишет сначала во временный файл и подменяет: оборванная запись не портит прежнюю сцену. */
	static boolean save(MinecraftServer server, Identifier id, Cutscene scene) {
		Path target = file(server, id);
		try {
			Files.createDirectories(target.getParent());
			Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
			Tag tag = Cutscene.CODEC.encodeStart(NbtOps.INSTANCE, scene).getOrThrow();
			NbtIo.writeCompressed((CompoundTag) tag, tmp);
			Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			return true;
		} catch (IOException | RuntimeException e) {
			BehaviorMod.LOGGER.warn("Сцена {} не записалась", id, e);
			return false;
		}
	}

	static boolean delete(MinecraftServer server, Identifier id) {
		try {
			return Files.deleteIfExists(file(server, id));
		} catch (IOException e) {
			return false;
		}
	}
}
