package me.cortex.voxy.client.config;

import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.config.SodiumConfigBuilder.*;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

// On NeoForge, Sodium discovers config pages by scanning for this annotation (ConfigLoaderForge), NOT
// via the Fabric "sodium:config_api_user" entrypoint in fabric.mod.json (which NeoForge-Sodium ignores).
// The value is the owning mod id. Without this, the Voxy page never shows up in Sodium's video settings.
@ConfigEntryPointForge("voxy")
public class VoxyConfigMenu implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder B) {
        if (!VoxyCommon.isAvailable()) return;//Dont even register the config if its not avalible

        var CFG = VoxyConfig.CONFIG;

        var cc = B.registerModOptions("voxy", "Voxy", VoxyCommon.MOD_VERSION)
                .setIcon(ResourceLocation.parse("voxy:icon.png"));

        final var RENDER_RELOAD = OptionFlag.REQUIRES_RENDERER_RELOAD.getId().toString();

        SodiumConfigBuilder.buildToSodium(B, cc, CFG::save, postOp->{
                    postOp.register("voxy:update_threads", ()->{
                        var instance = VoxyCommon.getInstance();
                        if (instance != null) {
                            instance.updateDedicatedThreads();
                        }
                    }, "voxy:enabled").register("voxy:iris_reload", ()->IrisUtil.reload());
                },
                new Page(Component.translatable("voxy.config.general"),
                        new Group(
                                new BoolOption(
                                        "voxy:enabled",
                                        Component.translatable("voxy.config.general.enabled"),
                                        ()->CFG.enabled, v->{
                                            CFG.enabled=v;
                                            //we need to special case enabled, since the render reload flag runs befor us and its quite important we get it right
                                            if (v&&VoxyClientInstance.isInGame) {
                                                VoxyCommon.createInstance();
                                            }
                                        })
                                        .setPostChangeRunner(c->{
                                            if (!c) {
                                                var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                                                if (vrsh != null) {
                                                    vrsh.shutdownRenderer();
                                                }
                                                VoxyCommon.shutdownInstance();
                                            }
                                        }).setPostChangeFlags(RENDER_RELOAD, "voxy:iris_reload").setEnabler(null)
                        ), new Group(
                                new IntOption(
                                        "voxy:thread_count",
                                        Component.translatable("voxy.config.general.serviceThreads"),
                                        ()->CFG.serviceThreads, v->CFG.serviceThreads=v,
                                        new Range(1, CpuLayout.getCoreCount(), 1))
                                        .setPostChangeFlags("voxy:update_threads"),
                                new BoolOption(
                                        "voxy:use_sodium_threads",
                                        Component.translatable("voxy.config.general.useSodiumBuilder"),
                                        ()->!CFG.dontUseSodiumBuilderThreads, v->CFG.dontUseSodiumBuilderThreads=!v)
                                        .setPostChangeFlags("voxy:update_threads")
                        ), new Group(
                                new BoolOption(
                                        "voxy:ingest_enabled",
                                        Component.translatable("voxy.config.general.ingest"),
                                        ()->CFG.ingestEnabled, v->CFG.ingestEnabled=v)
                        )
                ).setEnabler("voxy:enabled"),
                new Page(Component.translatable("voxy.config.rendering"),
                        new Group(
                                new BoolOption(
                                        "voxy:rendering",
                                        Component.translatable("voxy.config.general.rendering"),
                                        ()->CFG.enableRendering, v->CFG.enableRendering=v)
                                        .setPostChangeRunner(c->{
                                            var vrsh = (IGetVoxyRenderSystem)Minecraft.getInstance().levelRenderer;
                                            if (vrsh != null) {
                                                if (c) {
                                                    vrsh.createRenderer();
                                                } else {
                                                    vrsh.shutdownRenderer();
                                                }
                                            }
                                        },"voxy:enabled", RENDER_RELOAD)
                                        .setPostChangeFlags("voxy:iris_reload")
                                        .setEnabler("voxy:enabled")
                        ), new Group(
                                new IntOption(
                                        "voxy:subdivsize",
                                        Component.translatable("voxy.config.general.subDivisionSize"),
                                        ()->subDiv2ln(CFG.subDivisionSize), v->CFG.subDivisionSize=ln2subDiv(v),
                                        new Range(0, SUBDIV_IN_MAX, 1))
                                        .setFormatter(v->Component.literal(Integer.toString(Math.round(ln2subDiv(v))))),
                                new IntOption(
                                        "voxy:render_distance",
                                        Component.translatable("voxy.config.general.renderDistance"),
                                        ()->CFG.sectionRenderDistance, v->CFG.sectionRenderDistance=v,
                                        new Range(2, 64, 1))
                                        .setFormatter(v->Component.literal(Integer.toString(v*32)))//Top level rd == 32 chunks
                                        .setPostChangeRunner(c->{
                                            var vrsh = (IGetVoxyRenderSystem)Minecraft.getInstance().levelRenderer;
                                            if (vrsh != null) {
                                                var vrs = vrsh.getVoxyRenderSystem();
                                                if (vrs != null) {
                                                    vrs.setRenderDistance(c);
                                                }
                                            }
                                        }, "voxy:rendering", RENDER_RELOAD)
                        ), new Group(
                                // All fog values are read per frame in NormalRenderPipeline.finish
                                // (the shader always compiles USE_ENV_FOG), so none need a reload.
                                new BoolOption(
                                        "voxy:eviromental_fog",
                                        Component.translatable("voxy.config.general.environmental_fog"),
                                        ()->CFG.useEnvironmentalFog, v->CFG.useEnvironmentalFog=v),
                                new IntOption(
                                        "voxy:sky_fog_distance",
                                        Component.translatable("voxy.config.general.skyFogDistance"),
                                        ()->CFG.skyFogDistance, v->CFG.skyFogDistance=v,
                                        new Range(16, 512, 8))
                                        .setFormatter(v->Component.literal(v + " chunks")),
                                new IntOption(
                                        "voxy:fog_intensity",
                                        Component.translatable("voxy.config.general.fogIntensity"),
                                        ()->Math.round(CFG.fogIntensity*100), v->CFG.fogIntensity=v/100.0f,
                                        new Range(0, 100, 5))
                                        .setFormatter(v->Component.literal(v + "%")),
                                new IntOption(
                                        "voxy:fog_density",
                                        Component.translatable("voxy.config.general.fogDensity"),
                                        ()->Math.round(CFG.fogDensity*100), v->CFG.fogDensity=v/100.0f,
                                        new Range(0, 100, 5))
                                        .setFormatter(v->Component.literal(v + "%")),
                                new IntOption(
                                        "voxy:fog_distance_percent",
                                        Component.translatable("voxy.config.general.fogDistancePercent"),
                                        ()->CFG.fogDistancePercent, v->CFG.fogDistancePercent=v,
                                        new Range(5, 200, 5))
                                        .setFormatter(v->Component.literal(v + "%")),
                                new IntOption(
                                        "voxy:fog_start_percent",
                                        Component.translatable("voxy.config.general.fogStartPercent"),
                                        ()->CFG.fogStartPercent, v->CFG.fogStartPercent=v,
                                        new Range(0, 95, 5))
                                        .setFormatter(v->Component.literal(v + "%"))
                        ), new Group(
                                new BoolOption(
                                        "voxy:cave_fog",
                                        Component.translatable("voxy.config.general.caveFog"),
                                        ()->CFG.caveFogEnabled, v->CFG.caveFogEnabled=v),
                                new IntOption(
                                        "voxy:cave_fog_distance",
                                        Component.translatable("voxy.config.general.caveFogDistance"),
                                        ()->CFG.caveFogDistance, v->CFG.caveFogDistance=v,
                                        new Range(8, 128, 4))
                                        .setFormatter(v->Component.literal(v + " blocks"))
                        ), new Group(
                                new BoolOption(
                                        "voxy:adapt_cloud_distance",
                                        Component.translatable("voxy.config.general.adaptCloudDistance"),
                                        ()->CFG.adaptCloudDistance, v->CFG.adaptCloudDistance=v),
                                new IntOption(
                                        "voxy:cloud_distance",
                                        Component.translatable("voxy.config.general.cloudDistance"),
                                        ()->CFG.cloudDistance, v->CFG.cloudDistance=v,
                                        new Range(0, VoxyConfig.MAX_CLOUD_DISTANCE, 8))
                                        .setFormatter(v->v==0?Component.literal("Vanilla"):Component.literal(v + " chunks"))
                        ), new Group(
                                new BoolOption(
                                        "voxy:render_debug",
                                        Component.translatable("voxy.config.general.render_statistics"),
                                        ()-> RenderStatistics.enabled, v->RenderStatistics.enabled=v)
                                        .setPostChangeFlags(RENDER_RELOAD))
                ).setEnablerAND("voxy:enabled", "voxy:rendering"),
                new Page(Component.translatable("voxy.config.distantgen"),
                        new Group(
                                new BoolOption(
                                        "voxy:distant_gen",
                                        Component.translatable("voxy.config.distantgen.enabled"),
                                        ()->CFG.distantGenEnabled, v->CFG.distantGenEnabled=v)
                        ), new Group(
                                new IntOption(
                                        "voxy:distant_gen_radius",
                                        Component.translatable("voxy.config.distantgen.radius"),
                                        ()->CFG.distantGenRadius, v->CFG.distantGenRadius=v,
                                        new Range(16, 1024, 16))
                                        .setFormatter(v->Component.literal(v + " chunks")),
                                new IntOption(
                                        "voxy:distant_gen_in_flight",
                                        Component.translatable("voxy.config.distantgen.maxInFlight"),
                                        ()->CFG.distantGenMaxInFlight, v->CFG.distantGenMaxInFlight=v,
                                        new Range(1, 128, 1)),
                                new IntOption(
                                        "voxy:distant_gen_mspt",
                                        Component.translatable("voxy.config.distantgen.maxMspt"),
                                        ()->CFG.distantGenMaxMspt, v->CFG.distantGenMaxMspt=v,
                                        new Range(10, 50, 1))
                                        .setFormatter(v->Component.literal(v + " ms"))
                        )
                ).setEnablerAND("voxy:enabled", "voxy:ingest_enabled"),
                new Page(Component.translatable("voxy.config.advanced"),
                        new Group(
                                // Central-band LOD culling: width (% of monitor width) of the centre column
                                // that stays vanilla; the side wings become LOD. 100% = off. Read per frame,
                                // so no renderer reload needed.
                                new IntOption(
                                        "voxy:lod_center_width",
                                        Component.translatable("voxy.config.advanced.center_lod_width"),
                                        ()->CFG.lodCenterWidthPct, v->CFG.lodCenterWidthPct=v,
                                        new Range(30, 100, 1))
                                        .setFormatter(v->Component.literal(v + "%"))
                        ), new Group(
                                new IntOption(
                                        "voxy:lod_boundary_buffer",
                                        Component.translatable("voxy.config.advanced.lod_boundary_buffer"),
                                        ()->CFG.lodBoundaryBuffer, v->CFG.lodBoundaryBuffer=v,
                                        new Range(0, 4, 1))
                                        .setFormatter(v->v==0?Component.literal("Exact"):Component.literal(v + " blocks"))
                        ), new Group(
                                new IntOption(
                                        "voxy:earth_curve",
                                        Component.translatable("voxy.config.advanced.earth_curve"),
                                        ()->CFG.earthCurveRatio, v->CFG.earthCurveRatio=(v>0&&v<50)?50:v,
                                        new Range(0, 5000, 50))
                                        // Uploaded as a per-frame uniform (MDICSectionRenderer), so it
                                        // applies live - no RENDER_RELOAD flag needed.
                                        .setFormatter(v->v==0?Component.literal("Disabled"):(v<50?Component.literal("50 (min)"):Component.literal(v + "x")))
                        )
                ).setEnablerAND("voxy:enabled", "voxy:rendering"));

    }


    private static final int SUBDIV_IN_MAX = 100;
    private static final double SUBDIV_MIN = 28;
    private static final double SUBDIV_MAX = 256;
    private static final double SUBDIV_CONST = Math.log(SUBDIV_MAX/SUBDIV_MIN)/Math.log(2);

    //In range is 0->200
    //Out range is 28->256
    private static float ln2subDiv(int in) {
        return (float) (SUBDIV_MIN*Math.pow(2, SUBDIV_CONST*((double)in/SUBDIV_IN_MAX)));
    }

    //In range is ... any?
    //Out range is 0->200
    private static int subDiv2ln(float in) {
        return (int) (((Math.log(((double)in)/SUBDIV_MIN)/Math.log(2))/SUBDIV_CONST)*SUBDIV_IN_MAX);
    }
}
