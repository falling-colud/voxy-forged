package me.cortex.voxy.commonImpl.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Queue;

/**
 * Limits one pass over the chunk unload queue to the entries that were in it when the pass began.
 *
 * An unload that finds its chunk claimed by a generation task puts itself straight back on the queue
 * (ChunkMap.scheduleUnload), and vanilla keeps polling until the queue is empty or the tick is out of
 * time. A chunk that was queued for unload and then needed again as a generation neighbour stays claimed
 * for as long as generation around it continues - the normal state of the frontier while distant
 * generation runs. Two things follow from that:
 *  - every server tick spins on those entries until its 50ms are gone, so MSPT reads ~50 with nothing
 *    actually slow, and distant generation (which backs off on MSPT) stalls itself;
 *  - when the world closes the pass gets unlimited time, while the claims can only be released by work
 *    this same thread does after the pass returns. It never returns: the game hangs on "Saving worlds".
 * An entry that put itself back cannot succeed until the server thread has done something else, so
 * retrying it within the same pass is never useful.
 *
 * Optional (require = 0): a replacement chunk system has no such loop and does not need this.
 */
@Mixin(ChunkMap.class)
public class MixinChunkMap {
    //Unloads the running pass may still take; negative means unlimited (vanilla), which is what
    // happens if only the poll hook below applied
    @Unique
    private int voxy$unloadsLeftInPass = -1;

    //int k = Math.max(0, this.unloadQueue.size() - 2000); - evaluated once, right before the poll loop
    @WrapOperation(method = "processUnloads", at = @At(value = "INVOKE", target = "Ljava/util/Queue;size()I"), require = 0)
    private int voxy$beginUnloadPass(Queue<?> queue, Operation<Integer> original) {
        int size = original.call(queue);
        this.voxy$unloadsLeftInPass = size;
        return size;
    }

    //while (... && (runnable = this.unloadQueue.poll()) != null) - null ends the pass
    @WrapOperation(method = "processUnloads", at = @At(value = "INVOKE", target = "Ljava/util/Queue;poll()Ljava/lang/Object;"), require = 0)
    private Object voxy$pollOncePerPass(Queue<?> queue, Operation<Object> original) {
        if (this.voxy$unloadsLeftInPass == 0) {
            return null;
        }
        if (this.voxy$unloadsLeftInPass > 0) {
            this.voxy$unloadsLeftInPass--;
        }
        return original.call(queue);
    }
}
