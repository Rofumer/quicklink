package com.maximpolyakov.quicklink;

public final class QuickLinkNbt {
    private QuickLinkNbt() {}

    public static final String COLORS = "ColorsPacked"; // int (0..0xFFFF)
    public static final String SIDE_COLORS = "SideColorsPacked"; // int[6] by Direction index
    public static final String SIDE = "Side";           // byte (Direction.get3DDataValue())
    public static final String ENABLED = "Enabled";     // boolean
    public static final String UPGRADE_TIER = "ql_upgrade_tier"; // int (0..MAX_TIER)
}
