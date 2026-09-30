package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public class MixinClientChunkCache implements ICheekyClientChunkCache {
    @Shadow volatile ClientChunkCache.Storage storage;

    @Override
    public LevelChunk voxy$cheekyGetChunk(int x, int z) {
        //This doesnt do the in range check stuff, it just gets the chunk at all costs
        var chunk = this.storage.getChunk(this.storage.getIndex(x, z));
        if (chunk == null) {
            return null;
        }
        // The storage is a ring buffer indexed by (x, z) modulo the view range, so once a chunk has been dropped
        // its slot may already hold a different chunk. Every caller ingests the returned chunk at the chunk's OWN
        // position, so handing back the wrong one silently re-ingests an unrelated chunk and skips the requested
        // one. Same guard as upstream Voxy's MixinClientChunkCache.
        if (chunk.getPos().x == x && chunk.getPos().z == z) {
            return chunk;
        }
        return null;
    }

    // Capture the chunk's final contents right before it is unloaded, on every setup (upstream only does this when
    // Bobby is installed). Without Bobby the only unload ingest is MixinRenderSectionManager.injectIngest on Sodium's
    // RenderSectionManager.onChunkRemoved, but with Sodium 0.8 that callback is dispatched from
    // SodiumWorldRenderer.processChunkEvents during the NEXT render pass, i.e. after drop() has already replaced the
    // storage slot with null - the dropped chunk itself is never seen there, only its still-loaded neighbours (which
    // lose their "ready" status). The chunk's light data is still available here: handleForgetLevelChunk calls
    // drop() first and only queues the light removal afterwards.
    @Inject(method = "drop", at = @At("HEAD"))
    public void voxy$captureChunkBeforeUnload(ChunkPos pos, CallbackInfo ci) {
        if (VoxyConfig.CONFIG.ingestEnabled) {
            var chunk = this.voxy$cheekyGetChunk(pos.x, pos.z);
            if (chunk != null) {
                VoxelIngestService.tryAutoIngestChunk(chunk);
            }
        }
    }
}
