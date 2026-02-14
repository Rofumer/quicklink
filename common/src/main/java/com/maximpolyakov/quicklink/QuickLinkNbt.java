package com.maximpolyakov.quicklink;

public final class QuickLinkNbt {
    private QuickLinkNbt() {}

    public static final String COLORS = "ColorsPacked"; // int (0..0xFFFF)
    public static final String SIDE_COLORS = "SideColorsPacked"; // int[6] by Direction index
    public static final String SIDE = "Side";           // byte (Direction.get3DDataValue())
    public static final String ENABLED = "Enabled";     // boolean
}
