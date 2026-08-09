package com.maximpolyakov.quicklink.neoforge.compat.ftbteams;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Soft-dependency bridge for FTB Teams.
 * Only references FTB Teams types inside method bodies via fully-qualified names —
 * never in field/method signatures — so this class loads safely even when FTB Teams
 * is absent. Callers must guard with QuickLinkNeoForge.FTBTEAMS_LOADED before invoking.
 */
public final class FTBTeamsCompat {

    private FTBTeamsCompat() {}

    // getNetworkKey() is called uncached, often multiple times per side, inside per-tick
    // network scans. Team membership changes are rare, so a short TTL cache turns dozens
    // of FTB Teams API calls per plug per tick into a single cache hit without meaningfully
    // increasing staleness.
    private static final long CACHE_TTL_MS = 5000L;
    private static final ConcurrentHashMap<UUID, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private record CacheEntry(int teamKey, long expiresAtMs) {}

    /**
     * Component (0..0x7FFF) identifying the plug owner's effective FTB team
     * (party team if in a party, personal team otherwise), or 0 if unavailable.
     * Capped to 15 bits so it fits the 16-bit team slot of a network key without
     * touching the sign bit. FTBChunksCompat derives claim components with the same
     * {@link #hashTeamId} function, so a claim and plain membership of one team
     * deliberately produce the same component and therefore the same network.
     */
    public static int teamComponent(UUID ownerUUID) {
        if (ownerUUID == null) return 0;
        long now = System.currentTimeMillis();
        CacheEntry cached = CACHE.get(ownerUUID);
        if (cached != null && cached.expiresAtMs() > now) return cached.teamKey();
        int teamKey = resolveTeamComponent(ownerUUID);
        CACHE.put(ownerUUID, new CacheEntry(teamKey, now + CACHE_TTL_MS));
        return teamKey;
    }

    /** Hashes an FTB Teams team UUID down to 0..0x7FFF, shared with FTBChunksCompat. */
    public static int hashTeamId(UUID teamId) {
        int h = teamId.hashCode();
        return (h ^ (h >>> 16)) & 0x7FFF;
    }

    private static int resolveTeamComponent(UUID ownerUUID) {
        try {
            dev.ftb.mods.ftbteams.api.FTBTeamsAPI.API api = dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api();
            if (api == null || !api.isManagerLoaded()) return 0;
            Optional<dev.ftb.mods.ftbteams.api.Team> team = api.getManager().getTeamForPlayerID(ownerUUID);
            if (team.isEmpty()) return 0;
            return hashTeamId(team.get().getTeamId());
        } catch (Throwable t) {
            return 0;
        }
    }
}
