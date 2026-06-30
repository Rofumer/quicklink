package com.maximpolyakov.quicklink.forge.compat.jade;

import com.maximpolyakov.quicklink.forge.QuickLinkForge;
import com.maximpolyakov.quicklink.forge.block.ChemicalPlugBlock;
import com.maximpolyakov.quicklink.forge.block.EnergyPlugBlock;
import com.maximpolyakov.quicklink.forge.block.FluidPlugBlock;
import com.maximpolyakov.quicklink.forge.block.ItemPlugBlock;
import com.maximpolyakov.quicklink.forge.blockentity.ChemicalPlugBlockEntity;
import com.maximpolyakov.quicklink.forge.blockentity.EnergyPlugBlockEntity;
import com.maximpolyakov.quicklink.forge.blockentity.FluidPlugBlockEntity;
import com.maximpolyakov.quicklink.forge.blockentity.ItemPlugBlockEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

@WailaPlugin("quicklink")
public final class QuickLinkJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration reg) {
        reg.registerBlockDataProvider(JadePlugDataProvider.INSTANCE, EnergyPlugBlockEntity.class);
        reg.registerBlockDataProvider(JadePlugDataProvider.INSTANCE, FluidPlugBlockEntity.class);
        reg.registerBlockDataProvider(JadePlugDataProvider.INSTANCE, ItemPlugBlockEntity.class);
        if (QuickLinkForge.MEKANISM_LOADED) {
            reg.registerBlockDataProvider(JadePlugDataProvider.INSTANCE, ChemicalPlugBlockEntity.class);
        }
    }

    @Override
    public void registerClient(IWailaClientRegistration reg) {
        reg.registerBlockComponent(JadePlugRenderer.INSTANCE, EnergyPlugBlock.class);
        reg.registerBlockComponent(JadePlugRenderer.INSTANCE, FluidPlugBlock.class);
        reg.registerBlockComponent(JadePlugRenderer.INSTANCE, ItemPlugBlock.class);
        if (QuickLinkForge.MEKANISM_LOADED) {
            reg.registerBlockComponent(JadePlugRenderer.INSTANCE, ChemicalPlugBlock.class);
        }
    }
}
