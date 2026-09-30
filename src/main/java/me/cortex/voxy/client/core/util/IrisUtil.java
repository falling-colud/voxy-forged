package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.shadows.ShadowRenderer;

import java.io.IOException;

public class IrisUtil {

    // Fog dropped: Voxy's setupViewport no longer takes fog parameters (disabled for Sodium 0.6.x+),
    // so there is nothing to capture or pass through here.
    public record CapturedViewportParameters(ChunkRenderMatrices matrices, double x, double y, double z) {
        public Viewport<?> apply(VoxyRenderSystem vrs) {
            return vrs.setupViewport(this.matrices, this.x, this.y, this.z);
        }
    }

    public static CapturedViewportParameters CAPTURED_VIEWPORT_PARAMETERS;

    // Native NeoForge mod lookup - the FabricLoader call this replaces only worked because
    // Forgified Fabric API happened to be present, making FFAPI a hidden hard dependency.
    public static final boolean IRIS_INSTALLED = net.neoforged.fml.ModList.get().isLoaded("iris");
    public static final boolean SHADER_SUPPORT = true;//System.getProperty("voxy.enableExperimentalIrisPipeline", "false").equalsIgnoreCase("true");


    private static boolean irisShadowActive0() {
        return ShadowRenderer.ACTIVE;
    }

    public static boolean irisShadowActive() {
        return IRIS_INSTALLED && irisShadowActive0();
    }

    public static void clearIrisSamplers() {
        if (IRIS_INSTALLED) clearIrisSamplers0();
    }
    public static void reload() {
        if (IRIS_INSTALLED) reload0();
    }

    private static void reload0() {
        try {
            if (IrisApi.getInstance().isShaderPackInUse()) {//Only reload if there is a shaderpack
                Iris.reload();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void clearIrisSamplers0() {
        for (int i = 0; i < 16; i++) {
            IrisRenderSystem.bindSamplerToUnit(i, 0);
        }
    }

    private static boolean irisShaderPackEnabled0() {
        //isPackInUseQuick() consults the active pipeline and can disagree with reality around shader
        // reloads/startup; getCurrentPack() reflects whether a pack is actually loaded (upstream 4c41a16)
        return Iris.getCurrentPack().isPresent();
    }

    public static boolean irisShaderPackEnabled() {
        return IRIS_INSTALLED && irisShaderPackEnabled0();
    }
    public static void disableIrisShaders() {
        if(IRIS_INSTALLED) disableIrisShaders0();
    }
    private static void disableIrisShaders0() {
        IrisApi.getInstance().getConfig().setShadersEnabledAndApply(false);//Disable shaders
    }
}
