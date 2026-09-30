package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.util.CloudRenderContext;
import me.cortex.voxy.client.core.util.FogCompat;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.FogRenderer.FogMode;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fog capture-and-defer, ported from neo-voxy (NHblock-Johnsnow/neo-voxy).
 *
 * Injecting at TAIL of setupFog means every other fog source has already run: vanilla's
 * medium/effect fog (water, lava, powder snow, blindness, darkness, thick fog) AND any
 * NeoForge ViewportEvent.RenderFog listeners from other mods. Whatever fog state is live
 * here is the fog the frame will actually use, so it is captured and handed to the LOD
 * composite, which reproduces it on LOD terrain (blit_texture_depth_cutout.frag).
 *
 * Only plain distance fog is then pushed to MAX_VALUE (removing the fog wall at vanilla
 * render distance so LODs are visible behind it). Medium/effect fog is left untouched on
 * the vanilla pass and flagged "required" for the composite - that is what makes underwater
 * fog, blindness and darkness apply to LOD terrain instead of the LODs drawing over them.
 *
 * With Better Fog installed this mixin stands down entirely (see FogCompat.BETTER_FOG):
 * Better Fog owns the frame's fog end to end, and the composite samples it live instead.
 */
@Mixin(value = FogRenderer.class, remap = true)
public class MixinFogRenderer {
    @Inject(
            method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V",
            at = @At("TAIL")
    )
    private static void voxy$overrideFog(Camera camera, FogMode fogMode, float viewDistance,
                                         boolean thickFog, float tickDelta, CallbackInfo ci) {
        var renderer = IGetVoxyRenderSystem.getNullable();
        if (renderer == null || IrisUtil.irisShaderPackEnabled() || CloudRenderContext.isActive()
                || FogCompat.BETTER_FOG) {
            return;
        }

        float originalStart = RenderSystem.getShaderFogStart();
        float originalEnd = RenderSystem.getShaderFogEnd();
        boolean shortRangeFog = originalEnd < 10.0f;

        if (fogMode == FogMode.FOG_SKY && !shortRangeFog) {
            RenderSystem.setShaderFogStart(0.0f);
            RenderSystem.setShaderFogEnd(VoxyConfig.CONFIG.skyFogDistance * 16.0f);
            return;
        }

        if (fogMode != FogMode.FOG_TERRAIN) {
            return;
        }

        boolean normalTerrainFog = camera.getFluidInCamera() == FogType.NONE && !shortRangeFog && !thickFog;

        // Cave fog: while the camera sits in full darkness underground, pull the fog in close on
        // BOTH the vanilla pass and the LOD composite. Blended from the LOD horizon-fog distance,
        // so entering/leaving a cave is a quick fade rather than a pop.
        float caveBlend = normalTerrainFog ? FogCompat.updateCaveBlend(camera) : FogCompat.decayCaveBlend();
        if (normalTerrainFog && caveBlend > 0.003f) {
            float farEnd = VoxyConfig.CONFIG.skyFogDistance * 16.0f;
            float farStart = farEnd * (VoxyConfig.CONFIG.fogStartPercent / 100.0f);
            float caveEnd = Math.max(8.0f, VoxyConfig.CONFIG.caveFogDistance);
            float caveStart = caveEnd * 0.15f;
            float start = Mth.lerp(caveBlend, farStart, caveStart);
            float end = Mth.lerp(caveBlend, farEnd, caveEnd);
            RenderSystem.setShaderFogStart(start);
            RenderSystem.setShaderFogEnd(end);
            float[] col = RenderSystem.getShaderFogColor();
            // Caves read darker than the sky-derived fog colour the renderer computed outside.
            float darken = 1.0f - caveBlend * 0.55f;
            float[] caveColor = new float[]{
                    col[0] * darken, col[1] * darken, col[2] * darken,
                    col.length > 3 ? col[3] : 1.0f
            };
            renderer.setCapturedFog(start, end, caveColor, true);
            return;
        }

        // Use the configured LOD fog distance instead of stretching fog across
        // the entire Voxy render distance (which made it effectively invisible).
        float capturedEnd = normalTerrainFog
                ? Math.max(originalStart + 1.01f,
                        VoxyConfig.CONFIG.skyFogDistance * 16.0f)
                : originalEnd;
        float[] capturedColor = RenderSystem.getShaderFogColor();
        if (camera.getFluidInCamera() == FogType.WATER) {
            // Keep the vanilla near terrain untouched, but let the normal Voxy composite
            // retain a little more distant detail and reduce the unnaturally saturated blue.
            // Shader packs return above and therefore receive none of these adjustments.
            capturedEnd = Math.max(capturedEnd, originalEnd * 1.18f);
            float average = (capturedColor[0] + capturedColor[1] + capturedColor[2]) / 3.0f;
            capturedColor = new float[]{
                    capturedColor[0] * 0.86f + average * 0.14f,
                    capturedColor[1] * 0.86f + average * 0.14f,
                    capturedColor[2] * 0.86f + average * 0.14f,
                    capturedColor.length > 3 ? capturedColor[3] : 1.0f
            };
        }
        // Disabling Voxy's optional distance fog must not disable fog that represents the
        // camera's physical medium or a vanilla near-range effect. The LOD colour target is
        // composited after vanilla terrain, so it has to reproduce those mandatory fog values.
        renderer.setCapturedFog(originalStart, capturedEnd,
                capturedColor, !normalTerrainFog);

        if (normalTerrainFog) {
            RenderSystem.setShaderFogStart(Float.MAX_VALUE);
            RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        }
    }
}
