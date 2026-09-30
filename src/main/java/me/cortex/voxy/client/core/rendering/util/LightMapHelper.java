package me.cortex.voxy.client.core.rendering.util;

import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;

import me.cortex.voxy.client.mixin.minecraft.AccessorLightTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;

public class LightMapHelper {
    public static void bind(int lightingIndex) {
        glBindSampler(lightingIndex, 0);
        glBindTextureUnit(lightingIndex, getLightmapTextureId());
    }

    public static int getLightmapTextureId() {
        // MC 1.21.1: Use accessor mixin to get the private DynamicTexture field, then call getId()
        LightTexture lightTexture = Minecraft.getInstance().gameRenderer.lightTexture();
        return ((AccessorLightTexture) lightTexture).voxy$getLightTexture().getId();
    }
}