package me.cortex.voxy.client.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.util.CloudRenderContext;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.immediate.CloudRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;

// Ported from neo-voxy: the render wrap marks the frame section where Sodium's cloud renderer
// runs its own setupFog calls, so MixinFogRenderer leaves cloud fog alone instead of pushing
// it to infinity (which would pop clouds in at full opacity at the fog wall).
@Mixin(value = CloudRenderer.class, remap = false, priority = 1100)
public class MixinCloudRenderer {
    @WrapMethod(method = "render")
    private void voxy$trackCloudRendering(Camera camera, ClientLevel level, Matrix4f projectionMatrix,
                                          PoseStack poseStack, float ticks, float tickDelta,
                                          Operation<Void> original) {
        CloudRenderContext.begin();
        try {
            original.call(camera, level, projectionMatrix, poseStack, ticks, tickDelta);
        } finally {
            CloudRenderContext.end();
        }
    }

    @WrapMethod(method = "getCloudRenderDistance")
    private static int voxy$cloudRenderDistance(Operation<Integer> original) {
        int vanillaRadius = original.call();
        if (!VoxyConfig.CONFIG.isRenderingEnabled() || IrisUtil.irisShaderPackEnabled()) {
            return vanillaRadius;
        }

        int chunks = VoxyConfig.CONFIG.adaptCloudDistance
                ? Math.round(VoxyConfig.CONFIG.sectionRenderDistance * 32.0f)
                : VoxyConfig.CONFIG.cloudDistance;
        if (chunks < 1) {
            return vanillaRadius;
        }

        chunks = Math.min(chunks, VoxyConfig.MAX_CLOUD_DISTANCE);
        int radius = chunks * 2 + 9;
        return VoxyConfig.CONFIG.adaptCloudDistance ? Math.max(vanillaRadius, radius) : radius;
    }
}