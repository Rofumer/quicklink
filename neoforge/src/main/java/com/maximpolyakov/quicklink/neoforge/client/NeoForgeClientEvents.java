package com.maximpolyakov.quicklink.neoforge.client;

import com.maximpolyakov.quicklink.QuickLink;
import com.maximpolyakov.quicklink.neoforge.QuickLinkNeoForge;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

@EventBusSubscriber(modid = QuickLink.MOD_ID, value = Dist.CLIENT)
public final class NeoForgeClientEvents {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(QuickLinkNeoForge.ITEM_PLUG_BE.get(), ItemPlugBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(QuickLinkNeoForge.FLUID_PLUG_BE.get(), FluidPlugBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(QuickLinkNeoForge.ENERGY_PLUG_BE.get(), EnergyPlugBlockEntityRenderer::new);
    }

    @SubscribeEvent
    public static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                Identifier.fromNamespaceAndPath(QuickLink.MOD_ID, "upgrade_hud"),
                QuickLinkHudOverlay.LAYER
        );
    }
}
