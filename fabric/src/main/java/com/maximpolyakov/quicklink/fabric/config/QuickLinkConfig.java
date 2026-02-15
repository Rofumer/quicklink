package com.maximpolyakov.quicklink.fabric.config;

public final class QuickLinkConfig {
    public static final IntValue ITEM_TICK_PERIOD = new IntValue(10);
    public static final IntValue ITEM_MOVE_BATCH = new IntValue(8);

    public static final IntValue FLUID_TICK_PERIOD = new IntValue(10);
    public static final IntValue FLUID_TRANSFER_MB = new IntValue(250);
    public static final IntValue FLUID_INFINITE_MB_PER_TICK = new IntValue(250);
    public static final IntValue FLUID_INFINITE_MAX_PUSH_PER_TICK = new IntValue(4000);

    public static final IntValue ENERGY_TICK_PERIOD = new IntValue(10);
    public static final IntValue ENERGY_TRANSFER_FE = new IntValue(1000);

    private QuickLinkConfig() {}

    public static final class IntValue {
        private final int value;

        public IntValue(int value) {
            this.value = value;
        }

        public int get() {
            return value;
        }
    }
}
