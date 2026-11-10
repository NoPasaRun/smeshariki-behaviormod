package com.smeshariki.behavior.client.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.smeshariki.behavior.client.PuppetAccess;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/** Куклы кат-сцены: своих тиков у них нет (положение задаёт запись), и они никого не толкают. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	@Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
	private void behaviormod$noTick(Entity entity, CallbackInfo ci) {
		if (PuppetAccess.is(entity)) ci.cancel();
	}

	@Inject(method = "getPushableEntities", at = @At("RETURN"), cancellable = true)
	private void behaviormod$noPush(Entity entity, AABB box, CallbackInfoReturnable<List<Entity>> cir) {
		List<Entity> list = cir.getReturnValue();
		if (list == null || list.isEmpty()) return;
		List<Entity> out = new ArrayList<>(list);
		if (out.removeIf(PuppetAccess::is)) cir.setReturnValue(out);
	}
}
