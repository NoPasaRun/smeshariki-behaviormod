package com.smeshariki.behavior.client;

import com.mojang.blaze3d.pipeline.RenderTarget;

import net.minecraft.world.entity.Entity;

/** Вход для миксинов: пакетные классы {@link Puppets} и {@link VideoRender} снаружи не видны. */
public final class PuppetAccess {
	private PuppetAccess() {
	}

	public static boolean is(Entity entity) {
		return Puppets.is(entity);
	}

	public static boolean hidesReal(Entity entity) {
		return Puppets.hidesReal(entity);
	}

	public static void afterFrame(RenderTarget target) {
		VideoRender.afterFrame(target);
	}
}
