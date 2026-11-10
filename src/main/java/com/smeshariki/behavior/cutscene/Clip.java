package com.smeshariki.behavior.cutscene;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Кусок записи актёра (дубль или его часть): с тика {@code start} сцены по кадру на тик. Выключенный ({@code muted}) не играет — так хранятся
 * прежние варианты, когда записан новый дубль.
 */
public record Clip(int start, List<Frame> frames, boolean muted, String label) {
	public static final Codec<Clip> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.intRange(0, Integer.MAX_VALUE).fieldOf("start").forGetter(Clip::start),
			Frame.CODEC.listOf().fieldOf("frames").forGetter(Clip::frames),
			Codec.BOOL.optionalFieldOf("muted", false).forGetter(Clip::muted),
			Codec.STRING.optionalFieldOf("label", "").forGetter(Clip::label)
	).apply(i, Clip::new));

	/** Тик сцены, на котором клип кончается (не включая). */
	public int end() {
		return start + frames.size();
	}

	public boolean covers(float time) {
		return !frames.isEmpty() && time >= start && time <= end() - 1;
	}

	/** Кадр в момент {@code time} (доли — между кадрами); {@code null}, если клип там не играет. */
	@Nullable
	public Frame at(float time) {
		if (!covers(time)) return null;
		float local = time - start;
		int i = (int) Math.floor(local);
		if (i >= frames.size() - 1) return frames.getLast();
		return Frame.lerp(frames.get(i), frames.get(i + 1), local - i);
	}

	public Clip withStart(int value) {
		return new Clip(Math.max(0, value), frames, muted, label);
	}

	public Clip withMuted(boolean value) {
		return new Clip(start, frames, value, label);
	}

	/** Обрезка по тикам сцены: остаётся кусок {@code [from, to)}; пустой — {@code null}. */
	@Nullable
	public Clip trimmed(int from, int to) {
		int a = Math.max(from, start), b = Math.min(to, end());
		if (b - a <= 0) return null;
		return new Clip(a, List.copyOf(frames.subList(a - start, b - start)), muted, label);
	}
}
