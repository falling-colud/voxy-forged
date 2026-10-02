package me.cortex.voxy.client.distgen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;

/**
 * Background distant chunk generator for a single ServerLevel (integrated server only).
 *
 * Generates chunks in expanding euclidean rings around the player and feeds the results
 * into Voxy's ingest pipeline so LODs exist far beyond the vanilla render distance.
 *
 * "Smart":   skips chunks already ingested (persistent bitmap), recenters as the player
 *            moves, throttles on server MSPT and Voxy ingest/saving backlog.
 * "Fast":    waits only for ChunkStatus.LIGHT (all blocks, biomes and lighting exist at that
 *            point) and never loads a chunk as ticking. Generation itself runs on the vanilla
 *            chunk-system worker pool.
 * "Layered": concentric distance-sorted rings; the nearest missing terrain always
 *            generates first, so LOD coverage grows outward smoothly.
 *
 * Chunks are kept alive during generation by a dedicated no-timeout ticket
 * (a ticket-level drop completes pending generation futures with UNLOADED_CHUNK -
 * see GenerationChunkHolder.updateHighestAllowedStatus). The ticket is removed after
 * ingest, letting the vanilla chunk system unload (and persist) the chunk again.
 *
 * All methods must be called on the server thread.
 */
public class DistantWorldGenerator {
    //At LIGHT block states, biomes and light are all present, which is everything the voxel ingest needs.
    //The ticket level for LIGHT is 33 - the same level as a full (non-ticking) chunk, since vanilla has no
    // level that stops between INITIALIZE_LIGHT and FULL - so most chunks carry on to FULL before the
    // ticket is released. That costs some server-thread time per chunk, and leaves them saved as finished
    // chunks that load instantly when the player gets there.
    private static final ChunkStatus TARGET_STATUS = ChunkStatus.LIGHT;
    private static final int TICKET_LEVEL = ChunkLevel.byStatus(TARGET_STATUS);
    private static final TicketType<ChunkPos> TICKET = TicketType.create("voxy_distant_gen", Comparator.comparingLong(ChunkPos::toLong));
    //How far (in chunks, chebyshev) the player may drift from the ring center before restarting the rings
    private static final int RECENTER_DISTANCE = 16;
    private static final long NO_CANDIDATE = Long.MIN_VALUE;

    private final ServerLevel level;
    private final WorldIdentifier worldId;
    private final GeneratedChunkBitmap doneMap;
    //Chunks currently generating, keyed by ChunkPos.asLong. Each holds a TICKET at TICKET_LEVEL
    private final Long2ObjectOpenHashMap<CompletableFuture<ChunkResult<ChunkAccess>>> inFlight = new Long2ObjectOpenHashMap<>();
    //Chunks that failed generation this session, so they arent immediately retried in a requeue loop
    private final LongOpenHashSet failed = new LongOpenHashSet();
    private final LongArrayFIFOQueue pending = new LongArrayFIFOQueue();

    private int centerX;
    private int centerZ;
    private int currentRadius = -1;//-1 means "needs (re)centering"
    private int sessionGenerated;
    private int sessionFailed;

    public DistantWorldGenerator(ServerLevel level, Path bitmapDirectory) {
        this.level = level;
        this.worldId = WorldIdentifier.of(level);
        this.doneMap = new GeneratedChunkBitmap(bitmapDirectory.resolve(this.worldId.getWorldId() + ".bin"));
    }

    /** Collect finished generation futures, ingest their chunks and release their tickets. */
    public void harvest() {
        if (this.inFlight.isEmpty()) {
            return;
        }
        var it = this.inFlight.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            var entry = it.next();
            var future = entry.getValue();
            if (!future.isDone()) {
                continue;
            }
            long pos = entry.getLongKey();
            it.remove();
            int cx = ChunkPos.getX(pos);
            int cz = ChunkPos.getZ(pos);

            ChunkAccess chunk = null;
            try {
                var result = future.getNow(null);
                if (result != null) {
                    chunk = result.orElse(null);
                }
            } catch (Exception e) {
                Logger.error("Distant generation future failed for chunk [" + cx + ", " + cz + "]", e);
            }

            if (chunk != null) {
                this.ingest(chunk);
                this.doneMap.mark(cx, cz);
                this.sessionGenerated++;
            } else {
                this.failed.add(pos);
                this.sessionFailed++;
            }
            //Release the keepalive ticket; the chunk system will unload and persist the chunk
            var cp = new ChunkPos(cx, cz);
            this.chunkSource().distanceManager.removeTicket(TICKET, cp, TICKET_LEVEL, cp);
        }
    }

    /**
     * Start generation of up to (maxInFlight - inFlight) new chunks around the given player.
     * Returns false when the configured radius is fully generated for the current center.
     */
    public boolean topUp(ServerPlayer player, int maxInFlight, int maxRadius) {
        int pcx = SectionPos.blockToSectionCoord(player.getBlockX());
        int pcz = SectionPos.blockToSectionCoord(player.getBlockZ());
        if (this.currentRadius < 0
                || Math.max(Math.abs(pcx - this.centerX), Math.abs(pcz - this.centerZ)) >= RECENTER_DISTANCE) {
            this.centerX = pcx;
            this.centerZ = pcz;
            this.currentRadius = 0;
            this.pending.clear();
        }

        while (this.inFlight.size() < maxInFlight) {
            long pos = this.nextCandidate(maxRadius);
            if (pos == NO_CANDIDATE) {
                return false;
            }
            this.startGeneration(pos);
        }
        return true;
    }

    private long nextCandidate(int maxRadius) {
        while (true) {
            while (!this.pending.isEmpty()) {
                long pos = this.pending.dequeueLong();
                if (!this.isSkippable(pos)) {
                    return pos;
                }
            }
            if (this.currentRadius > maxRadius) {
                return NO_CANDIDATE;
            }
            this.fillRing(this.currentRadius++);
        }
    }

    private boolean isSkippable(long pos) {
        return this.inFlight.containsKey(pos)
                || this.failed.contains(pos)
                || this.doneMap.contains(ChunkPos.getX(pos), ChunkPos.getZ(pos));
    }

    /**
     * Enqueue all chunk offsets whose euclidean distance d from the center satisfies r <= d < r+1.
     * The union over increasing r covers every chunk exactly once, giving circular "layers".
     */
    private void fillRing(int r) {
        for (int dx = -r; dx <= r; dx++) {
            long min2 = (long) r * r - (long) dx * dx;
            long max2 = (long) (r + 1) * (r + 1) - (long) dx * dx;
            int zlo = (int) Math.ceil(Math.sqrt(min2));
            while ((long) zlo * zlo < min2) zlo++;
            while (zlo > 0 && (long) (zlo - 1) * (zlo - 1) >= min2) zlo--;
            int zhi = (int) Math.ceil(Math.sqrt(max2)) - 1;
            while ((long) (zhi + 1) * (zhi + 1) < max2) zhi++;
            while (zhi >= 0 && (long) zhi * zhi >= max2) zhi--;
            for (int dz = zlo; dz <= zhi; dz++) {
                this.enqueueIfMissing(this.centerX + dx, this.centerZ + dz);
                if (dz != 0) {
                    this.enqueueIfMissing(this.centerX + dx, this.centerZ - dz);
                }
            }
        }
    }

    private void enqueueIfMissing(int cx, int cz) {
        long pos = ChunkPos.asLong(cx, cz);
        if (!this.isSkippable(pos)) {
            this.pending.enqueue(pos);
        }
    }

    private void startGeneration(long pos) {
        int cx = ChunkPos.getX(pos);
        int cz = ChunkPos.getZ(pos);
        var cp = new ChunkPos(cx, cz);
        var chunkSource = this.chunkSource();
        //Keepalive ticket with no timeout: without it the internal UNKNOWN ticket (1 tick timeout)
        // expires mid-generation and the future completes with UNLOADED_CHUNK
        chunkSource.distanceManager.addTicket(TICKET, cp, TICKET_LEVEL, cp);
        //Private method opened via AT; on the server thread this schedules async generation
        // on the chunk-system worker pool and returns immediately (no managedBlock)
        var future = chunkSource.getChunkFutureMainThread(cx, cz, TARGET_STATUS, true);
        this.inFlight.put(pos, future);
    }

    private net.minecraft.server.level.ServerChunkCache chunkSource() {
        return this.level.getChunkSource();
    }

    /** Feed every section of a generated (or disk-loaded) chunk into Voxy's ingest service. */
    private void ingest(ChunkAccess chunk) {
        var lightEngine = this.level.getLightEngine();
        var blockLight = lightEngine.getLayerListener(LightLayer.BLOCK);
        var skyLight = lightEngine.getLayerListener(LightLayer.SKY);
        int cx = chunk.getPos().x;
        int cz = chunk.getPos().z;
        int minSectionY = this.level.getMinSection();
        var sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            var section = sections[i];
            if (section == null) {
                continue;
            }
            int sy = minSectionY + i;
            var spos = SectionPos.of(cx, sy, cz);
            var bl = blockLight.getDataLayerData(spos);
            var sl = skyLight.getDataLayerData(spos);
            VoxelIngestService.rawIngest(this.worldId, section, cx, sy, cz,
                    bl == null ? null : bl.copy(), sl == null ? null : sl.copy());
        }
    }

    /** Release all in-flight tickets and persist the bitmap. Called on disable/world shutdown. */
    public void shutdown() {
        var it = this.inFlight.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            var entry = it.next();
            it.remove();
            var cp = new ChunkPos(ChunkPos.getX(entry.getLongKey()), ChunkPos.getZ(entry.getLongKey()));
            this.chunkSource().distanceManager.removeTicket(TICKET, cp, TICKET_LEVEL, cp);
        }
        this.pending.clear();
        this.currentRadius = -1;
        this.doneMap.saveIfDirty();
    }

    public void saveIfDirty() {
        this.doneMap.saveIfDirty();
    }

    /** Forget all progress (session + persistent) so everything regenerates. */
    public void reset() {
        this.shutdown();
        this.failed.clear();
        this.doneMap.reset();
        this.sessionGenerated = 0;
        this.sessionFailed = 0;
    }

    public String describeStatus(int maxRadius) {
        boolean exhausted = this.pending.isEmpty() && this.currentRadius > maxRadius && this.inFlight.isEmpty();
        return "center [" + this.centerX + ", " + this.centerZ + "]"
                + " ring " + Math.max(0, Math.min(this.currentRadius, maxRadius)) + "/" + maxRadius + (exhausted ? " (complete)" : "")
                + ", in-flight " + this.inFlight.size()
                + ", session generated " + this.sessionGenerated + " (failed " + this.sessionFailed + ")"
                + ", total known " + this.doneMap.getCount();
    }

    public ServerLevel getLevel() {
        return this.level;
    }
}
