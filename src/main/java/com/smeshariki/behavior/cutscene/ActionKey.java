package com.smeshariki.behavior.cutscene;

import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

/** Действие механики мира на тике сцены (то же, что в уровнях: {@code gameplaymod:lava_wave} и др.). Параметры — строки; число и true/false читаются из них. */
public record ActionKey(int tick, Identifier action, Map<String, String> args) {
	public static final Codec<ActionKey> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.intRange(0, Integer.MAX_VALUE).fieldOf("tick").forGetter(ActionKey::tick),
			Identifier.CODEC.fieldOf("action").forGetter(ActionKey::action),
			Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("args", Map.of()).forGetter(ActionKey::args)
	).apply(i, ActionKey::new));
}
