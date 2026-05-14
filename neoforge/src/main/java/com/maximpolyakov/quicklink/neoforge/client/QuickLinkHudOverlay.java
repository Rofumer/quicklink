package com.maximpolyakov.quicklink.neoforge.client;

import com.maximpolyakov.quicklink.neoforge.UpgradeTier;
import com.maximpolyakov.quicklink.neoforge.blockentity.EnergyPlugBlockEntity;
import com.maximpolyakov.quicklink.neoforge.blockentity.FluidPlugBlockEntity;
import com.maximpolyakov.quicklink.neoforge.blockentity.ItemPlugBlockEntity;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.client.gui.GuiLayer;

public final class QuickLinkHudOverlay {

    public static final GuiLayer LAYER = QuickLinkHudOverlay::render;

    private static void render(net.minecraft.client.gui.GuiGraphicsExtractor gui, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.options.hideGui) return;
        if (mc.screen != null) return;
        if (!(mc.hitResult instanceof BlockHitResult bhr)) return;

        BlockPos pos = bhr.getBlockPos();
        BlockEntity be = mc.level.getBlockEntity(pos);

        int tier = -1;
        if (be instanceof ItemPlugBlockEntity ipbe)        tier = ipbe.getUpgradeTier();
        else if (be instanceof FluidPlugBlockEntity fpbe)  tier = fpbe.getUpgradeTier();
        else if (be instanceof EnergyPlugBlockEntity epbe) tier = epbe.getUpgradeTier();

        if (tier < 0) return;

        String text = tier == 0
                ? "Upgrade: none"
                : "Upgrade: tier " + tier + " / " + UpgradeTier.MAX_TIER + " (×" + UpgradeTier.multiplier(tier) + ")";

        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        int x = w / 2 - mc.font.width(text) / 2;
        int y = h / 2 + 20;

        int color = tier == 0 ? 0xAAAAAA : 0xFFD700;
        gui.text(mc.font, text, x, y, color, true);
    }
}
