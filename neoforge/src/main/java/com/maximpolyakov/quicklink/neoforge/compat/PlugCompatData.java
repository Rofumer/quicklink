package com.maximpolyakov.quicklink.neoforge.compat;

import com.maximpolyakov.quicklink.neoforge.blockentity.ChemicalPlugBlockEntity;
import com.maximpolyakov.quicklink.neoforge.blockentity.EnergyPlugBlockEntity;
import com.maximpolyakov.quicklink.neoforge.blockentity.FluidPlugBlockEntity;
import com.maximpolyakov.quicklink.neoforge.blockentity.ItemPlugBlockEntity;
import com.maximpolyakov.quicklink.neoforge.config.QuickLinkConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;

public final class PlugCompatData {

    public static final String TYPE      = "ql_type";
    public static final String MAX       = "ql_max";
    public static final String LAST_SENT = "ql_sent";
    public static final String LAST_RECV = "ql_recv";
    public static final String PERIOD    = "ql_period";
    public static final String TIER      = "ql_tier";

    public static final String ENERGY   = "energy";
    public static final String FLUID    = "fluid";
    public static final String ITEM     = "item";
    public static final String CHEMICAL = "chemical";

    private PlugCompatData() {}

    public static void write(CompoundTag data, BlockEntity be) {
        if (be instanceof EnergyPlugBlockEntity e) {
            data.putString(TYPE, ENERGY);
            data.putLong(MAX,       e.effectiveTransferFe());
            data.putLong(LAST_SENT, e.getLastSentFe());
            data.putLong(LAST_RECV, e.getLastReceivedFe());
            data.putInt(PERIOD, QuickLinkConfig.ENERGY_TICK_PERIOD.get());
            data.putInt(TIER,   e.getUpgradeTier());
        } else if (be instanceof FluidPlugBlockEntity f) {
            data.putString(TYPE, FLUID);
            data.putLong(MAX,       f.effectiveAmountMb());
            data.putLong(LAST_SENT, f.getLastSentMb());
            data.putLong(LAST_RECV, f.getLastReceivedMb());
            data.putInt(PERIOD, QuickLinkConfig.FLUID_TICK_PERIOD.get());
            data.putInt(TIER,   f.getUpgradeTier());
        } else if (be instanceof ItemPlugBlockEntity i) {
            data.putString(TYPE, ITEM);
            data.putLong(MAX,       i.effectiveMoveBatch());
            data.putLong(LAST_SENT, i.getLastSentItems());
            data.putLong(LAST_RECV, i.getLastReceivedItems());
            data.putInt(PERIOD, QuickLinkConfig.ITEM_TICK_PERIOD.get());
            data.putInt(TIER,   i.getUpgradeTier());
        } else if (be instanceof ChemicalPlugBlockEntity c) {
            data.putString(TYPE, CHEMICAL);
            data.putLong(MAX,       c.effectiveAmountMb());
            data.putLong(LAST_SENT, c.getLastSentMb());
            data.putLong(LAST_RECV, c.getLastReceivedMb());
            data.putInt(PERIOD, QuickLinkConfig.CHEMICAL_TICK_PERIOD.get());
            data.putInt(TIER,   c.getUpgradeTier());
        }
    }

    public static List<Component> buildTooltip(CompoundTag data) {
        if (!data.contains(TYPE)) return List.of();

        String type   = data.getString(TYPE);
        long   max    = data.getLong(MAX);
        long   sent   = data.getLong(LAST_SENT);
        long   recv   = data.getLong(LAST_RECV);
        int    period = data.getInt(PERIOD);
        int    tier   = data.getInt(TIER);

        List<Component> lines = new ArrayList<>();

        if (tier > 0) {
            int mult = 1 << tier;
            lines.add(Component.literal("Tier " + tier + "  (x" + mult + ")")
                    .withStyle(ChatFormatting.GOLD));
        }

        String unit = switch (type) {
            case ENERGY            -> "FE";
            case FLUID, CHEMICAL   -> "mB";
            case ITEM              -> "items";
            default                -> "";
        };

        lines.add(Component.literal(String.format("Max:  %,d %s / %d ticks", max, unit, period))
                .withStyle(ChatFormatting.GRAY));

        if (sent == 0 && recv == 0) {
            lines.add(Component.literal("Idle").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            if (sent > 0) {
                lines.add(Component.literal(String.format("Out:  %,d %s", sent, unit))
                        .withStyle(ChatFormatting.GREEN));
            }
            if (recv > 0) {
                lines.add(Component.literal(String.format("In:   %,d %s", recv, unit))
                        .withStyle(ChatFormatting.AQUA));
            }
        }

        return lines;
    }
}
