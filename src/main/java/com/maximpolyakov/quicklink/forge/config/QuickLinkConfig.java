package com.maximpolyakov.quicklink.forge.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class QuickLinkConfig {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue ITEM_TICK_PERIOD;
    public static final ForgeConfigSpec.IntValue ITEM_MOVE_BATCH;

    public static final ForgeConfigSpec.IntValue FLUID_TICK_PERIOD;
    public static final ForgeConfigSpec.IntValue FLUID_TRANSFER_MB;
    public static final ForgeConfigSpec.IntValue FLUID_INFINITE_MB_PER_TICK;
    public static final ForgeConfigSpec.IntValue FLUID_INFINITE_MAX_PUSH_PER_TICK;

    public static final ForgeConfigSpec.IntValue ENERGY_TICK_PERIOD;
    public static final ForgeConfigSpec.IntValue ENERGY_TRANSFER_FE;

    public static final ForgeConfigSpec.IntValue CHEMICAL_TICK_PERIOD;
    public static final ForgeConfigSpec.IntValue CHEMICAL_TRANSFER_MB;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("items");
        ITEM_TICK_PERIOD = b.comment("Attempt period in ticks. Lower = faster.").defineInRange("tickPeriod", 10, 1, 200);
        ITEM_MOVE_BATCH  = b.comment("Items to move per attempt.").defineInRange("moveBatch", 8, 1, 64);
        b.pop();

        b.push("fluids");
        FLUID_TICK_PERIOD              = b.comment("Attempt period in ticks.").defineInRange("tickPeriod", 10, 1, 200);
        FLUID_TRANSFER_MB              = b.comment("mB to transfer per attempt.").defineInRange("transferMb", 250, 1, 8000);
        FLUID_INFINITE_MB_PER_TICK     = b.comment("Infinite water speed in mB/tick.").defineInRange("infiniteMbPerTick", 250, 1, 1_000_000);
        FLUID_INFINITE_MAX_PUSH_PER_TICK = b.comment("Max mB per infinite-source fill op.").defineInRange("infiniteMaxPushPerTick", 4000, 250, 1_000_000);
        b.pop();

        b.push("energy");
        ENERGY_TICK_PERIOD = b.comment("Attempt period in ticks.").defineInRange("tickPeriod", 10, 1, 200);
        ENERGY_TRANSFER_FE = b.comment("FE to transfer per attempt.").defineInRange("transferFe", 1000, 1, 1_000_000);
        b.pop();

        b.push("chemicals");
        CHEMICAL_TICK_PERIOD = b.comment("Attempt period in ticks.").defineInRange("tickPeriod", 10, 1, 200);
        CHEMICAL_TRANSFER_MB = b.comment("mB to transfer per attempt.").defineInRange("transferMb", 250, 1, 8000);
        b.pop();

        SPEC = b.build();
    }

    private QuickLinkConfig() {}
}
