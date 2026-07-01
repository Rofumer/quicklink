package com.maximpolyakov.quicklink.forge.compat.ftbteams;

import java.util.Optional;
import java.util.UUID;

/**
 * Soft-dependency bridge for FTB Teams (Forge 1.20.1).
 * Only references FTB Teams types inside method bodies via fully-qualified names —
 * never in field/method signatures — so this class loads safely even when FTB Teams
 * is absent. Callers must guard with QuickLinkForge.FTBTEAMS_LOADED before invoking.
 */
public final class FTBTeamsCompat {

    private FTBTeamsCompat() {}

    /**
     * Component (0..0xFFFF) identifying the plug owner's effective FTB team
     * (party team if in a party, personal team otherwise), or 0 if unavailable.
     */
    public static int teamComponent(UUID ownerUUID) {
        if (ownerUUID == null) return 0;
        try {
            dev.ftb.mods.ftbteams.api.FTBTeamsAPI.API api = dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api();
            if (api == null || !api.isManagerLoaded()) return 0;
            Optional<dev.ftb.mods.ftbteams.api.Team> team = api.getManager().getTeamForPlayerID(ownerUUID);
            if (team.isEmpty()) return 0;
            UUID teamId = team.get().getTeamId();
            int h = teamId.hashCode();
            return (h ^ (h >>> 16)) & 0xFFFF;
        } catch (Throwable t) {
            return 0;
        }
    }
}
