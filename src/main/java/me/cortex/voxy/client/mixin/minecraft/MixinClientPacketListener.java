package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {
    @Inject(method = "handleLogin", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;commonPlayerSpawnInfo()Lnet/minecraft/network/protocol/game/CommonPlayerSpawnInfo;"))
    private void voxy$init(ClientboundLoginPacket packet, CallbackInfo ci) {
        if (VoxyCommon.isAvailable() && !VoxyClientInstance.isInGame) {
            VoxyClientInstance.isInGame = true;
            if (VoxyConfig.CONFIG.enabled) {
                if (VoxyCommon.getInstance() != null) {
                    VoxyCommon.shutdownInstance();
                }
                VoxyCommon.createInstance();
            }
        }
    }

    // Ingest a chunk as soon as it has both its block data and its light. enableChunkLight runs from the queued
    // light update of handleLevelChunkWithLight, right after applyLightData, so the light engine already holds the
    // chunk's (queued) light layers and every non-empty section is marked LIGHT_AND_DATA - exactly what
    // VoxelIngestService.enqueueIngest requires. The existing ingest on Sodium's onChunkAdded only fires once the
    // chunk AND all 8 neighbours are loaded, so the outermost loaded chunk ring is never ingested by the client; this
    // port additionally stops Sodium from drawing that ring (MixinRenderSectionManager.voxy$cullOutermostVanillaRing)
    // and relies on LOD to fill it, which left a void ring at the render-distance edge on servers whenever the LOD
    // for it had not been streamed yet. Chunks that later become "ready" are ingested a second time by the
    // onChunkAdded path; an identical re-ingest changes no section, marks nothing dirty and triggers no save/remesh.
    @Inject(method = "enableChunkLight", at = @At("TAIL"))
    private void voxy$ingestOnChunkLight(LevelChunk chunk, int x, int z, CallbackInfo ci) {
        if (VoxyConfig.CONFIG.ingestEnabled) {
            VoxelIngestService.tryAutoIngestChunk(chunk);
        }
    }
}
