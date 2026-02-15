package com.maximpolyakov.quicklink.fabric.client;

import com.maximpolyakov.quicklink.fabric.QuickLinkFabric;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public final class QuickLinkFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BlockEntityRenderers.register(QuickLinkFabric.ITEM_PLUG_BE, ItemPlugBlockEntityRenderer::new);
        BlockEntityRenderers.register(QuickLinkFabric.FLUID_PLUG_BE, FluidPlugBlockEntityRenderer::new);
        BlockEntityRenderers.register(QuickLinkFabric.ENERGY_PLUG_BE, EnergyPlugBlockEntityRenderer::new);
    }
}
