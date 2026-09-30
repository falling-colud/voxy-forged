package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.neoforged.fml.ModList;

/**
 * Fog coexistence and the cave-fog tracker.
 *
 * BETTER_FOG: when Better Fog is installed it is the fog authority - it wins the RenderFog
 * event at HIGH priority and stretches its fog across Voxy's LOD distance (it reads Voxy's
 * render distance for exactly that purpose). Voxy then changes NOTHING about the frame's fog;
 * the LOD composite samples the live fog at composite time instead, so LOD terrain is fogged
 * with the identical values the near terrain was just drawn with. That is seam-free by
 * construction and does not depend on capture timing.
 */
public final class FogCompat {
    public static final boolean BETTER_FOG = ModList.get().isLoaded("betterfog");

    private static float caveBlend;
    private static long lastNanos;

    private FogCompat() {}

    /** Advance the cave blend toward "in a cave right now?" and return it (0..1). */
    public static float updateCaveBlend(Camera camera) {
        return step(computeTarget(camera));
    }

    /** Advance the cave blend toward 0 (used while fluid/effect fog owns the frame). */
    public static float decayCaveBlend() {
        return step(0.0f);
    }

    private static float step(float target) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.0f : Math.min(0.25f, (now - lastNanos) / 1_000_000_000.0f);
        lastNanos = now;
        // ~0.6s time constant both directions: descending into or surfacing out of a cave reads
        // as a quick fade, not a pop. Real-time based, so repeated setupFog calls per frame
        // (probes, sky pass) advance it correctly instead of multiplying the rate.
        float a = 1.0f - (float) Math.exp(-dt / 0.6f);
        caveBlend += (target - caveBlend) * a;
        if (target == 0.0f && caveBlend < 0.002f) {
            caveBlend = 0.0f;
        }
        return caveBlend;
    }

    private static float computeTarget(Camera camera) {
        if (!VoxyConfig.CONFIG.caveFogEnabled) {
            return 0.0f;
        }
        var level = Minecraft.getInstance().level;
        // Sky light is the cave signal, so dimensions without it (the nether) must opt out or
        // the whole dimension would count as one big cave.
        if (level == null || !level.dimensionType().hasSkyLight()) {
            return 0.0f;
        }
        BlockPos pos = BlockPos.containing(camera.getPosition());
        if (level.isOutsideBuildHeight(pos.getY())) {
            return 0.0f;
        }
        return level.getBrightness(LightLayer.SKY, pos) == 0 ? 1.0f : 0.0f;
    }
}
