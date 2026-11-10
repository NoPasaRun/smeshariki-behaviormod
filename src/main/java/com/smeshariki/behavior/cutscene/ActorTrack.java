package com.smeshariki.behavior.cutscene;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

/** Дорожка актёра: персонаж (тип сущности) и его клипы. Играет тот клип, что начался позже всех из покрывающих момент и не выключен. */
public record ActorTrack(Identifier character, List<Clip> clips) {
	public static final Codec<ActorTrack> CODEC = RecordCodecBuilder.create(i -> i.group(
			Identifier.CODEC.fieldOf("character").forGetter(ActorTrack::character),
			Clip.CODEC.listOf().optionalFieldOf("clips", List.of()).forGetter(ActorTrack::clips)
	).apply(i, ActorTrack::new));

	@Nullable
	public Frame at(float time) {
		Clip best = null;
		for (Clip clip : clips) {
			if (!clip.muted() && clip.covers(time) && (best == null || clip.start() >= best.start())) best = clip;
		}
		return best == null ? null : best.at(time);
	}

	/** Где актёр стоит после последнего своего клипа, если тот кончился до {@code time}; иначе {@code null}. */
	@Nullable
	public Frame lastBefore(float time) {
		Clip best = null;
		for (Clip clip : clips) {
			if (!clip.muted() && !clip.frames().isEmpty() && clip.end() - 1 < time && (best == null || clip.end() > best.end())) best = clip;
		}
		return best == null ? null : best.frames().getLast();
	}

	/**
	 * Где актёр в момент {@code time}: играет клип; клип кончился — стоит в его последнем кадре; клип ещё не начался — стоит в первом кадре самого раннего.
	 * {@code null} — играющих клипов нет вовсе.
	 */
	@Nullable
	public Frame pose(float time) {
		Frame f = at(time);
		if (f != null) return f;
		f = lastBefore(time);
		if (f != null) return f;
		Clip first = null;
		for (Clip clip : clips) {
			if (!clip.muted() && !clip.frames().isEmpty() && (first == null || clip.start() < first.start())) first = clip;
		}
		return first == null ? null : first.frames().getFirst();
	}

	public ActorTrack withClips(List<Clip> value) {
		return new ActorTrack(character, value);
	}

	public ActorTrack with(Clip clip) {
		List<Clip> out = new ArrayList<>(clips);
		out.add(clip);
		return withClips(out);
	}
}
