package com.maximpolyakov.quicklink.forge.client;

import com.maximpolyakov.quicklink.QuickLink;
import com.maximpolyakov.quicklink.forge.QuickLinkForge;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = QuickLink.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ForgeClientEvents {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(QuickLinkForge.ITEM_PLUG_BE.get(), ItemPlugBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(QuickLinkForge.FLUID_PLUG_BE.get(), FluidPlugBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(QuickLinkForge.ENERGY_PLUG_BE.get(), EnergyPlugBlockEntityRenderer::new);
        if (QuickLinkForge.MEKANISM_LOADED) {
            event.registerBlockEntityRenderer(QuickLinkForge.CHEMICAL_PLUG_BE.get(), ChemicalPlugBlockEntityRenderer::new);
        }
    }
}
