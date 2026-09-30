package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.neoforged.fml.loading.FMLPaths;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class VoxyConfig {
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static VoxyConfig CONFIG = loadOrCreate();

    public boolean enabled = true;
    public boolean enableRendering = true;
    public boolean ingestEnabled = true;
    public int sectionRenderDistance = 16;
    public int serviceThreads = (int) Math.max(CpuLayout.getCoreCount()/1.5, 1);
    public float subDivisionSize = 64;
    public boolean useEnvironmentalFog = true;

    // End distance of sky fog and of the LOD terrain's own distance fog, in chunks. Also the
    // fog end MixinFogRenderer captures for the composite when only plain distance fog is live.
    public int skyFogDistance = 96;
    // Strength of the optional LOD distance fog: 0 = invisible, 1 = full fog colour at the end.
    public float fogIntensity = 1.0f;
    // 0 = the same smoothstep curve Sodium's terrain fog uses; >0 bends the curve exponential
    // (fog stays thin until near the end distance, then thickens).
    public float fogDensity = 0.0f;
    // Where the optional LOD fog ends, as a percentage of the LOD render distance.
    public int fogDistancePercent = 100;
    // Where the optional LOD fog starts, as a percentage of its end distance.
    public int fogStartPercent = 50;

    // Cave fog: pull the fog in close while the camera sits in full darkness underground
    // (zero sky light; dimensions without sky light are exempt). Applies to both the vanilla
    // pass and the LOD composite, and stands down while Better Fog owns the fog.
    public boolean caveFogEnabled = false;
    public int caveFogDistance = 24;

    // Clouds: when adapt is on, Sodium's cloud render distance follows the LOD render distance
    // (never below vanilla); otherwise cloudDistance chunks (0 = leave vanilla alone).
    public static final int MAX_CLOUD_DISTANCE = 128;
    public boolean adaptCloudDistance = true;
    public int cloudDistance = 0;

    public boolean dontUseSodiumBuilderThreads = true;

    // Runtime toggle for the LOD colour/brightness fix, so it can be compared against raw brightness live.
    // Toggled in-game via "/voxy colorfix" (not exposed in the config screen). true = brightness fix applied
    // (no-shader ~0.955, shader ~1.025), false = raw brightness (1.0, no fix). Read live by MDICSectionRenderer
    // and uploaded to the LOD shaders as uColorFix.
    public boolean colorFix = true;

    // Runtime toggle for the world-curvature fix (seamless start at the vanilla edge + camera-relative, smooth
    // tracking). Toggled in-game via "/voxy curvefix". true = fixed curve, false = original curve (measured from
    // the section origin, so it cuts at the boundary and snaps every 32 blocks). Uploaded to the shader as uCurveFix.
    public boolean curveFix = true;

    // LOD boundary buffer: extra INWARD shrink of each vanilla chunk's occlusion box, on top of the
    // 1-block margin outline.vsh already adds to match Sodium's render test. 0 is correct now that the
    // outermost-ring cull (MixinRenderSectionManager) + exact occlusion alignment (ChunkBoundRenderer)
    // handle the vanilla<->LOD boundary; any overlap (>0) only makes flat water z-fight at the seam.
    // Range: 0-4 blocks.
    public int lodBoundaryBuffer = 0;

    // World curvature: simulates standing on a spherical planet
    // 0 = disabled (flat world)
    // 1 = real Earth curvature (6371km radius)
    // Higher values = more extreme curvature (smaller planet effect)
    // Range: 0, or 50-5000 (values 1-49 are invalid and auto-corrected to 50)
    // Inspired by Distant Horizons' earth curvature feature
    public int earthCurveRatio = 0;

    // Central-band LOD culling: width (% of the monitor's pixel width) of a screen-centred, full-height
    // column that keeps rendering vanilla chunks. Everything to the LEFT/RIGHT of that column renders as
    // Voxy LOD instead of vanilla. The column is sized off the MONITOR width, so at 100% the band is at
    // least as wide as the game window and nothing changes (default). Lower values shrink the vanilla
    // column and turn the side "wings" into LOD - handy for ultrawide setups. Range: 30-100 (100 = off).
    public int lodCenterWidthPct = 100;

    // Distant generation: background-generate chunks beyond the vanilla render distance on the
    // integrated server (singleplayer/LAN host) and ingest them into the LOD store. Chunks are
    // generated only to the LIGHT status (blocks+biomes+light, no entities/ticking) in expanding
    // rings around the player, throttled by server MSPT and the ingest backlog.
    public boolean distantGenEnabled = true;
    // Radius in chunks around the player to generate. 96 chunks = 1536 blocks.
    public int distantGenRadius = 96;
    // How many chunks may generate concurrently. Higher = faster but more server load.
    public int distantGenMaxInFlight = 16;
    // Only start new chunks while the server's average tick time is below this (milliseconds).
    public int distantGenMaxMspt = 45;

    private static VoxyConfig loadOrCreate() {
        if (VoxyCommon.isAvailable()) {
            var path = getConfigPath();
            if (Files.exists(path)) {
                try (FileReader reader = new FileReader(path.toFile())) {
                    var conf = GSON.fromJson(reader, VoxyConfig.class);
                    if (conf != null) {
                        conf.sanitize();
                        conf.save();
                        return conf;
                    } else {
                        Logger.error("Failed to load voxy config, resetting");
                    }
                } catch (Exception e) {
                    // Nothing may escape here: this runs from the static initialiser, so any throw becomes an
                    // ExceptionInInitializerError, which leaves the class permanently unusable and takes mod
                    // loading down with it. Gson throws unchecked on malformed values (e.g. a fractional number
                    // in an int field), so catch broadly and fall back to defaults.
                    Logger.error("Could not parse config, resetting", e);
                }
                backupUnreadableConfig(path);
            }
            var config = new VoxyConfig();
            config.save();
            return config;
        } else {
            var config = new VoxyConfig();
            config.enabled = false;
            config.enableRendering = false;
            return config;
        }
    }

    // Moves a config we could not read aside instead of letting the reset overwrite it, so the user's
    // settings survive for recovery and the bad file is still there to diagnose.
    private static void backupUnreadableConfig(Path path) {
        try {
            Files.move(path, path.resolveSibling(path.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Logger.error("Failed to back up unreadable config", e);
        }
    }

    public void save() {
        try {
            Files.writeString(getConfigPath(), GSON.toJson(this));
        } catch (IOException e) {
            Logger.error("Failed to write config file", e);
        }
    }

    private static Path getConfigPath() {
        return FMLPaths.CONFIGDIR.get()
                .resolve("voxy-config.json");
    }

    public boolean isRenderingEnabled() {
        return VoxyCommon.isAvailable() && this.enabled && this.enableRendering;
    }

    // Clamp hand-edited config values into the ranges the renderer assumes; out-of-range fog or
    // cloud values otherwise reach the shaders/mixins unchecked.
    private void sanitize() {
        this.skyFogDistance = Math.clamp(this.skyFogDistance, 0, 1024);
        this.fogIntensity = Math.clamp(this.fogIntensity, 0.0f, 1.0f);
        this.fogDensity = Math.clamp(this.fogDensity, 0.0f, 1.0f);
        this.fogDistancePercent = Math.clamp(this.fogDistancePercent, 5, 200);
        this.fogStartPercent = Math.clamp(this.fogStartPercent, 0, 95);
        this.caveFogDistance = Math.clamp(this.caveFogDistance, 8, 256);
        this.cloudDistance = Math.clamp(this.cloudDistance, 0, MAX_CLOUD_DISTANCE);
    }

    public int getLodRenderDistanceBlocks() {
        // sectionRenderDistance is in top-level sections of 32 chunks; *32*16 converts to blocks.
        return Math.clamp(this.sectionRenderDistance * 32 * 16, 64, 32768);
    }
}
