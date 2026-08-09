package com.maximpolyakov.quicklink;

public final class QuickLinkNbt {
    private QuickLinkNbt() {}

    public static final String COLORS = "ColorsPacked"; // int (0..0xFFFF)
    public static final String SIDE_COLORS = "SideColorsPacked"; // int[6] by Direction index
    public static final String SIDE = "Side";           // byte (Direction.get3DDataValue())
    public static final String ENABLED = "Enabled";     // boolean
    public static final String UPGRADE_TIER = "ql_upgrade_tier"; // int (0..MAX_TIER)
    public static final String OWNER_UUID = "ql_owner";

    // Network keys a plug is currently registered under, so it can withdraw the *old* keys after a
    // reload. Without these the block entity comes back with an empty in-memory set and re-registers
    // under its new key, leaving the stale entry in the saved network forever.
    public static final String REG_PLUG_KEYS = "ql_reg_plug_keys";
    public static final String REG_POINT_KEYS = "ql_reg_point_keys";

    public static int[] packKeys(java.util.Set<Integer> keys) {
        int[] out = new int[keys.size()];
        int i = 0;
        for (int k : keys) out[i++] = k;
        return out;
    }

    public static java.util.Set<Integer> unpackKeys(int[] packed) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        if (packed != null) for (int k : packed) out.add(k);
        return out;
    }
}
