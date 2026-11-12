package com.smeshariki.behavior.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.smeshariki.behavior.client.CutsceneClient;
import com.smeshariki.behavior.cutscene.Cutscene;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.phys.Vec3;

/** Во время кат-сцены ставит камеру по кадрам сцены вместо глаз игрока. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	protected abstract void setPosition(Vec3 pos);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	private float fov;

	@Shadow
	private boolean detached;

	@Shadow
	private Quaternionf rotation;

	@Shadow
	private Matrix4f cachedViewRotMatrix;

	@Shadow
	public abstract Matrix4f getViewRotationMatrix(Matrix4f dest);

	@Shadow
	protected abstract Matrix4f createProjectionMatrixForCulling();

	@Shadow
	protected abstract void prepareCullFrustum(Matrix4fc modelViewMatrix, Matrix4f projectionMatrixForCulling, Vec3 cameraPos);

	@Inject(method = "update", at = @At("TAIL"))
	private void behaviormod$cutscene(DeltaTracker delta, CallbackInfo ci) {
		Cutscene.View view = CutsceneClient.view(delta.getGameTimeDeltaPartialTick(false));
		if (view == null) return;
		setPosition(view.pos());
		setRotation(view.yaw(), view.pitch());
		if (view.roll() != 0f) rotation.rotateZ((float) Math.toRadians(view.roll()));
		fov = view.fov();
		detached = true; // видно своё тело и не рисуется рука
		// рамку видимости игра строит внутри update() по телу игрока, раньше нашей подмены: без пересчёта секции мира и сущности отсекаются рамкой от тела,
		// и чем дальше камера улетела от него, тем меньше видно (при прыжке на 150 блоков — только небо)
		prepareCullFrustum(getViewRotationMatrix(cachedViewRotMatrix), createProjectionMatrixForCulling(), view.pos());
	}
}
