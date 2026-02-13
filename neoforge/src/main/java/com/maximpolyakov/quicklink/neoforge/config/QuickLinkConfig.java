package com.maximpolyakov.quicklink.neoforge.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class QuickLinkConfig {

    public static final ModConfigSpec SPEC;

    // ===== Items =====
    public static final ModConfigSpec.IntValue ITEM_TICK_PERIOD; // раз в N тиков
    public static final ModConfigSpec.IntValue ITEM_MOVE_BATCH;  // сколько предметов за попытку

    // ===== Fluids =====
    public static final ModConfigSpec.IntValue FLUID_TICK_PERIOD; // раз в N тиков
    public static final ModConfigSpec.IntValue FLUID_TRANSFER_MB; // mB за попытку
    public static final ModConfigSpec.IntValue FLUID_INFINITE_MB_PER_TICK;
    public static final ModConfigSpec.IntValue FLUID_INFINITE_MAX_PUSH_PER_TICK;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("items");
        ITEM_TICK_PERIOD = b
                .comment("Attempt period in ticks (20 ticks = 1 second). Lower = faster.")
                .defineInRange("tickPeriod", 10, 1, 200);

        ITEM_MOVE_BATCH = b
                .comment("How many items to move per attempt.")
                .defineInRange("moveBatch", 8, 1, 64);
        b.pop();

        b.push("fluids");
        FLUID_TICK_PERIOD = b
                .comment("Attempt period in ticks (20 ticks = 1 second). Lower = faster.")
                .defineInRange("tickPeriod", 10, 1, 200);

        FLUID_TRANSFER_MB = b
                .comment("How many millibuckets to transfer per attempt.")
                .defineInRange("transferMb", 250, 1, 8000);

        FLUID_INFINITE_MB_PER_TICK = b
                .comment("Infinite water source speed in millibuckets per tick.")
                .defineInRange("infiniteMbPerTick", 250, 1, 1_000_000);

        FLUID_INFINITE_MAX_PUSH_PER_TICK = b
                .comment("Max millibuckets pushed in a single infinite-source fill operation.")
                .defineInRange("infiniteMaxPushPerTick", 4000, 250, 1_000_000);
        b.pop();

        SPEC = b.build();
    }

    private QuickLinkConfig() {}
}
