package me.cortex.voxy.client.dev;

import com.mojang.blaze3d.platform.NativeImage;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.distgen.DistantGenerationManager;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Scripted screenshots for the dev client: {@code ./gradlew runClient -PquickPlay="New World" -PdevShots=<script>}.
 *
 * LOD bugs are only visible in a running game, from a particular place, once the LODs there have been
 * built - and usually need the same view twice (shaders on/off, a resource pack in/out) to mean anything.
 * This drives that from a text file so a comparison is one command instead of ten minutes of flying around.
 *
 * Dev runs only: the package is excluded from the release jar (see the jar block in build.gradle), and
 * nothing here runs unless {@code -Dvoxy.devShots} names a script.
 *
 * One command per line, {@code #} starts a comment:
 * <pre>
 *   wait &lt;ticks&gt;
 *   idle [maxTicks] [stableTicks]    wait until Voxy has no queued ingest/bake/mesh work
 *   distgen [maxTicks]               wait until distant generation reports complete
 *   tp x y z yaw pitch               spectator teleport
 *   cmd &lt;command&gt;                    run a command as the player (server or client command)
 *   shader &lt;pack&gt;|off                switch Iris shaderpack
 *   packs a,b,c                      select exactly these resource packs (ids as in options.txt) and reload
 *   rd &lt;chunks&gt;                      vanilla render distance
 *   fov &lt;degrees&gt;                    30-110; a low value is a zoom lens for distant LODs
 *   shot &lt;name&gt;                      write screenshots/&lt;name&gt;.png
 *   log &lt;text&gt;
 *   exit
 * </pre>
 */
@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public final class DevShots {
    private static final String SCRIPT = System.getProperty("voxy.devShots", "");
    //Ticks to let the title screen clear and the first chunks arrive before the script starts
    private static final int JOIN_DELAY = 60;

    private static List<String[]> steps;
    private static int index;
    private static int waited;
    private static int stable;
    private static int joinTicks;
    private static CompletableFuture<?> pending;
    private static boolean done;
    private static boolean hasPose;
    private static float poseYaw;
    private static float posePitch;

    private DevShots() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (SCRIPT.isEmpty() || done) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            joinTicks = 0;
            return;
        }
        if (++joinTicks < JOIN_DELAY) {
            return;
        }
        if (steps == null) {
            steps = load();
            if (steps == null) {
                done = true;
                return;
            }
            mc.options.hideGui = true;
            Logger.info("[DevShots] running " + steps.size() + " steps from " + SCRIPT);
        }
        //A screen (pause menu on focus loss, a pack reload overlay) would end up in the picture
        if (mc.screen != null && pending == null) {
            mc.setScreen(null);
        }
        //The window can end up focused while the desktop is in use; a grabbed mouse then turns the camera
        // between the teleport and the shot. Let go of it and pin the view to what the script asked for.
        if (mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse();
        }
        if (hasPose) {
            mc.player.setYRot(poseYaw);
            mc.player.setXRot(posePitch);
            mc.player.yRotO = poseYaw;
            mc.player.xRotO = posePitch;
        }
        try {
            while (index < steps.size() && step(mc, steps.get(index))) {
                index++;
                waited = 0;
                stable = 0;
            }
        } catch (Throwable t) {
            Logger.error("[DevShots] step " + index + " failed: " + String.join(" ", steps.get(index)), t);
            index++;
            waited = 0;
            pending = null;
        }
        if (index >= steps.size()) {
            done = true;
            Logger.info("[DevShots] script finished");
        }
    }

    private static List<String[]> load() {
        try {
            List<String[]> out = new ArrayList<>();
            for (String line : Files.readAllLines(Path.of(SCRIPT))) {
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash);
                line = line.strip();
                if (line.isEmpty()) continue;
                out.add(line.split("\\s+", 2));
            }
            return out;
        } catch (Exception e) {
            Logger.error("[DevShots] cannot read script " + SCRIPT, e);
            return null;
        }
    }

    /** @return true once the step has completed and the next one may run in the same tick */
    private static boolean step(Minecraft mc, String[] step) throws Exception {
        String arg = step.length > 1 ? step[1] : "";
        switch (step[0]) {
            case "wait" -> {
                return ++waited > Integer.parseInt(arg);
            }
            case "idle" -> {
                String[] a = arg.isEmpty() ? new String[0] : arg.split("\\s+");
                int max = a.length > 0 ? Integer.parseInt(a[0]) : 6000;
                int need = a.length > 1 ? Integer.parseInt(a[1]) : 60;
                stable = busy() ? 0 : stable + 1;
                if (++waited % 200 == 0) {
                    Logger.info("[DevShots] idle wait " + waited + "/" + max + " " + status());
                }
                if (stable >= need || waited > max) {
                    Logger.info("[DevShots] idle " + (stable >= need ? "reached" : "TIMED OUT") + " after " + waited + " ticks " + status());
                    return true;
                }
                return false;
            }
            case "distgen" -> {
                int max = arg.isEmpty() ? 12000 : Integer.parseInt(arg);
                var lines = DistantGenerationManager.statusLines();
                boolean complete = !lines.isEmpty() && lines.stream().allMatch(l -> l.contains("(complete)"));
                if (++waited % 200 == 0) {
                    Logger.info("[DevShots] distgen wait " + waited + "/" + max + " " + lines);
                }
                if (complete || waited > max) {
                    Logger.info("[DevShots] distgen " + (complete ? "complete" : "TIMED OUT") + " after " + waited + " ticks " + lines);
                    return true;
                }
                return false;
            }
            case "tp" -> {
                if (waited++ == 0) {
                    mc.player.connection.sendCommand("gamemode spectator");
                    return false;
                }
                mc.player.connection.sendCommand("tp @s " + arg);
                String[] a = arg.split("\\s+");
                if (a.length >= 5) {
                    poseYaw = Float.parseFloat(a[3]);
                    posePitch = Float.parseFloat(a[4]);
                    hasPose = true;
                }
                return true;
            }
            case "cmd" -> {
                mc.player.connection.sendCommand(arg);
                return true;
            }
            case "shader" -> {
                IrisControl.select(arg);
                return true;
            }
            case "packs" -> {
                if (pending == null) {
                    var repo = mc.getResourcePackRepository();
                    repo.reload();
                    var wanted = Arrays.stream(arg.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
                    for (String id : wanted) {
                        if (!repo.getAvailableIds().contains(id)) {
                            Logger.error("[DevShots] resource pack not available: " + id + " (have " + repo.getAvailableIds() + ")");
                        }
                    }
                    repo.setSelected(wanted);
                    mc.options.updateResourcePacks(repo);
                    Logger.info("[DevShots] resource packs now " + repo.getSelectedIds());
                    pending = mc.reloadResourcePacks();
                    return false;
                }
                if (!pending.isDone()) {
                    return false;
                }
                pending = null;
                return true;
            }
            case "rd" -> {
                mc.options.renderDistance().set(Integer.parseInt(arg));
                return true;
            }
            case "fov" -> {
                mc.options.fov().set(Integer.parseInt(arg));
                return true;
            }
            case "shot" -> {
                File dir = new File(mc.gameDirectory, "screenshots");
                dir.mkdirs();
                //Written synchronously so that "exit" on the next line cannot outrun the file
                try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                    image.writeToFile(new File(dir, arg + ".png"));
                }
                Logger.info("[DevShots] shot " + arg + " " + status());
                return true;
            }
            case "log" -> {
                Logger.info("[DevShots] " + arg + " " + status());
                return true;
            }
            case "exit" -> {
                Logger.info("[DevShots] exiting");
                startShutdownWatchdog();
                mc.stop();
                return true;
            }
            default -> {
                Logger.error("[DevShots] unknown step: " + String.join(" ", step));
                return true;
            }
        }
    }

    //A world that never finishes closing would otherwise hold the whole run (and its Gradle parent) forever
    // with nothing in the log. Say where it is stuck, then get out of the way.
    private static void startShutdownWatchdog() {
        int seconds = Integer.getInteger("voxy.devShots.shutdownTimeout", 90);
        var watchdog = new Thread(() -> {
            try {
                Thread.sleep(seconds * 1000L);
            } catch (InterruptedException e) {
                return;
            }
            var out = new StringBuilder("[DevShots] SHUTDOWN HUNG: still running " + seconds + "s after exit\n");
            for (var entry : Thread.getAllStackTraces().entrySet()) {
                String name = entry.getKey().getName();
                if (!name.equals("Server thread") && !name.equals("Render thread") && !name.startsWith("Dedicated Voxy")) {
                    continue;
                }
                out.append('"').append(name).append("\" ").append(entry.getKey().getState()).append('\n');
                for (var frame : entry.getValue()) {
                    out.append("\tat ").append(frame).append('\n');
                }
            }
            Logger.error(out.toString());
            Runtime.getRuntime().halt(42);
        }, "DevShots shutdown watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static boolean busy() {
        var vrs = IGetVoxyRenderSystem.getNullable();
        if (vrs == null || vrs.hasPendingWork()) {
            return true;
        }
        var instance = VoxyCommon.getInstance();
        return instance != null && instance.getIngestService().getTaskCount() != 0;
    }

    private static String status() {
        var vrs = IGetVoxyRenderSystem.getNullable();
        var instance = VoxyCommon.getInstance();
        List<String> debug = new ArrayList<>();
        if (vrs != null) {
            vrs.addDebugInfo(debug);
        }
        var server = Minecraft.getInstance().getSingleplayerServer();
        return "[ingest=" + (instance == null ? "-" : instance.getIngestService().getTaskCount())
                + " work=" + (vrs == null ? "-" : vrs.hasPendingWork())
                + " mspt=" + (server == null ? "-" : String.format("%.1f", server.getAverageTickTimeNanos() / 1e6))
                + " serverChunks=" + (server == null ? "-" : server.overworld().getChunkSource().getLoadedChunksCount())
                + " " + (debug.size() > 3 ? debug.subList(1, 4) : debug)
                + " distgen=" + DistantGenerationManager.statusLines() + "]";
    }

    //Kept apart so Iris classes are only touched when a script actually switches packs
    private static final class IrisControl {
        static void select(String pack) throws Exception {
            var config = net.irisshaders.iris.Iris.getIrisConfig();
            if (pack.equalsIgnoreCase("off")) {
                config.setShadersEnabled(false);
            } else {
                config.setShaderPackName(pack);
                config.setShadersEnabled(true);
            }
            config.save();
            net.irisshaders.iris.Iris.reload();
            Logger.info("[DevShots] shaderpack now " + (config.areShadersEnabled() ? config.getShaderPackName().orElse("?") : "off"));
        }
    }
}
