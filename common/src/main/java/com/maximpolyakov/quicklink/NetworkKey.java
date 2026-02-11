package com.maximpolyakov.quicklink;

import java.util.Objects;

/**
 * Value-object wrapper around the 16-bit packed network key.
 * Helps avoid mixing raw ints everywhere.
 */
public final class NetworkKey {
    private final int value; // 0..65535 (we use only lower 16 bits)

    public NetworkKey(int value) {
        this.value = value & 0xFFFF;
    }

    public static NetworkKey fromColors(QuickLinkColors colors) {
        return new NetworkKey(colors.pack());
    }

    public static NetworkKey unpack(int packed16) {
        return new NetworkKey(packed16);
    }

    public int value() {
        return value;
    }

    public QuickLinkColors colors() {
        return QuickLinkColors.unpack(value);
    }

    public boolean isConfigured() {
        return colors().isConfigured();
    }

    @Override
    public String toString() {
        return "NetworkKey{" + value + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NetworkKey that)) return false;
        return value == that.value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
