package com.maximpolyakov.quicklink;

import java.util.Arrays;
import java.util.Objects;

/**
 * 4-slot color key for QuickLink networks.
 *
 * Colors are stored as 0..15 (vanilla DyeColor ids).
 * GRAY (7) is treated as "unset" by default.
 */
public final class QuickLinkColors {
    /** Vanilla DyeColor.GRAY id (0..15). */
    public static final byte UNSET = 7;

    private final byte c0;
    private final byte c1;
    private final byte c2;
    private final byte c3;

    public QuickLinkColors(byte c0, byte c1, byte c2, byte c3) {
        this.c0 = normalize(c0);
        this.c1 = normalize(c1);
        this.c2 = normalize(c2);
        this.c3 = normalize(c3);
    }

    /** Default colors: all unset (GRAY). */
    public static QuickLinkColors unset() {
        return new QuickLinkColors(UNSET, UNSET, UNSET, UNSET);
    }

    /** Create from packed 16-bit value: c0 | c1<<4 | c2<<8 | c3<<12 */
    public static QuickLinkColors unpack(int packed) {
        byte c0 = (byte) (packed & 0xF);
        byte c1 = (byte) ((packed >>> 4) & 0xF);
        byte c2 = (byte) ((packed >>> 8) & 0xF);
        byte c3 = (byte) ((packed >>> 12) & 0xF);
        return new QuickLinkColors(c0, c1, c2, c3);
    }

    /** Pack into 16-bit value: c0 | c1<<4 | c2<<8 | c3<<12 */
    public int pack() {
        return (c0 & 0xF)
                | ((c1 & 0xF) << 4)
                | ((c2 & 0xF) << 8)
                | ((c3 & 0xF) << 12);
    }

    /** Network key for registries (same as pack()). */
    public int networkKey() {
        return pack();
    }

    /** True if at least one slot is not UNSET (GRAY). */
    public boolean isConfigured() {
        return c0 != UNSET || c1 != UNSET || c2 != UNSET || c3 != UNSET;
    }

    /** True if all 4 slots are UNSET (GRAY). */
    public boolean isAllUnset() {
        return c0 == UNSET && c1 == UNSET && c2 == UNSET && c3 == UNSET;
    }

    public byte get(int slot) {
        return switch (slot) {
            case 0 -> c0;
            case 1 -> c1;
            case 2 -> c2;
            case 3 -> c3;
            default -> throw new IllegalArgumentException("slot must be 0..3, got " + slot);
        };
    }

    public QuickLinkColors with(int slot, byte color) {
        byte nc = normalize(color);
        return switch (slot) {
            case 0 -> new QuickLinkColors(nc, c1, c2, c3);
            case 1 -> new QuickLinkColors(c0, nc, c2, c3);
            case 2 -> new QuickLinkColors(c0, c1, nc, c3);
            case 3 -> new QuickLinkColors(c0, c1, c2, nc);
            default -> throw new IllegalArgumentException("slot must be 0..3, got " + slot);
        };
    }

    /** Returns a defensive copy [c0,c1,c2,c3]. */
    public byte[] toArray() {
        return new byte[]{c0, c1, c2, c3};
    }

    /** Human-friendly debug string, e.g. "[7,7,14,0]" */
    @Override
    public String toString() {
        return Arrays.toString(new int[]{c0 & 0xFF, c1 & 0xFF, c2 & 0xFF, c3 & 0xFF});
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof QuickLinkColors that)) return false;
        return c0 == that.c0 && c1 == that.c1 && c2 == that.c2 && c3 == that.c3;
    }

    @Override
    public int hashCode() {
        return Objects.hash(c0, c1, c2, c3);
    }

    private static byte normalize(byte c) {
        int v = c & 0xFF;
        if (v < 0) v = 0;
        if (v > 15) v = 15;
        return (byte) v;
    }
}
