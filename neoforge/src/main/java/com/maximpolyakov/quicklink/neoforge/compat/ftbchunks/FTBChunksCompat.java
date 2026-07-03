package com.maximpolyakov.quicklink.neoforge.compat.ftbchunks;

import com.maximpolyakov.quicklink.neoforge.compat.ftbteams.FTBTeamsCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Soft-dependency bridge for FTB Chunks.
 * Only references FTB Chunks/FTB Library types inside method bodies via fully-qualified
 * names — never in field/method signatures — so this class loads safely even when FTB
 * Chunks is absent. Callers must guard with QuickLinkNeoForge.FTBCHUNKS_LOADED before invoking.
 */
public final class FTBChunksCompat {

    private FTBChunksCompat() {}

    public static final int NOT_CLAIMED = -1;

    // getNetworkKey() is called uncached, often multiple times per side, inside per-tick
    // network scans. Claims change rarely, so a short TTL cache turns repeated FTB Chunks
    // API lookups per plug per tick into a single cache hit without meaningfully increasing
    // staleness. Keyed by chunk position (dimension + chunk x/z), shared across all blocks
    // in the same chunk.
    private static final long CACHE_TTL_MS = 5000L;
    private static final ConcurrentHashMap<Object, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private record CacheEntry(int result, long expiresAtMs) {}

    /**
     * Component (0..0x7FFF) identifying the FTB team that owns the claim at the given
     * position, or {@link #NOT_CLAIMED} (-1) if the chunk is unclaimed or FTB Chunks data
     * is unavailable.
     */
    public static int claimTeamComponent(Level level, BlockPos pos) {
        if (level == null || pos == null) return NOT_CLAIMED;
        try {
            dev.ftb.mods.ftblibrary.math.ChunkDimPos chunkPos =
                    new dev.ftb.mods.ftblibrary.math.ChunkDimPos(level, pos);
            long now = System.currentTimeMillis();
            CacheEntry cached = CACHE.get(chunkPos);
            if (cached != null && cached.expiresAtMs() > now) return cached.result();
            int result = resolveClaimTeamComponent(chunkPos);
            CACHE.put(chunkPos, new CacheEntry(result, now + CACHE_TTL_MS));
            return result;
        } catch (Throwable t) {
            return NOT_CLAIMED;
        }
    }

    private static int resolveClaimTeamComponent(dev.ftb.mods.ftblibrary.math.ChunkDimPos chunkPos) {
        try {
            dev.ftb.mods.ftbchunks.api.FTBChunksAPI.API api = dev.ftb.mods.ftbchunks.api.FTBChunksAPI.api();
            if (api == null || !api.isManagerLoaded()) return NOT_CLAIMED;
            dev.ftb.mods.ftbchunks.api.ClaimedChunk claim = api.getManager().getChunk(chunkPos);
            if (claim == null) return NOT_CLAIMED;
            java.util.UUID teamId = claim.getTeamData().getTeam().getTeamId();
            return FTBTeamsCompat.hashTeamId(teamId);
        } catch (Throwable t) {
            return NOT_CLAIMED;
        }
    }
}
