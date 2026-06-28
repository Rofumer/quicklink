package com.maximpolyakov.quicklink.forge;

public final class UpgradeTier {
    public static final int MAX_TIER = 4;

    public static int multiplier(int tier) {
        if (tier <= 0) return 1;
        return 1 << Math.min(tier, MAX_TIER);
    }

    private UpgradeTier() {}
}
