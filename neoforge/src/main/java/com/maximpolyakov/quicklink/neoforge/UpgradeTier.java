package com.maximpolyakov.quicklink.neoforge;

public final class UpgradeTier {

    /** Maximum upgrade tier a plug block can have. Edit this to raise or lower the cap. */
    public static final int MAX_TIER = 4;

    /**
     * Speed multiplier for the given tier:
     * tier 0 → ×1, tier 1 → ×2, tier 2 → ×4, tier 3 → ×8, tier 4 → ×16.
     * Edit this method to change the progression curve.
     */
    public static int multiplier(int tier) {
        if (tier <= 0) return 1;
        return 1 << Math.min(tier, MAX_TIER);
    }

    private UpgradeTier() {}
}
