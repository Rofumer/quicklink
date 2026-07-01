package com.maximpolyakov.quicklink.neoforge.compat.jade;

import com.maximpolyakov.quicklink.neoforge.QuickLinkNeoForge;
import com.maximpolyakov.quicklink.neoforge.block.ChemicalPlugBlock;
import com.maximpolyakov.quicklink.neoforge.block.EnergyPlugBlock;
import com.maximpolyakov.quicklink.neoforge.block.FluidPlugBlock;
import com.maximpolyakov.quicklink.neoforge.block.ItemPlugBlock;
import com.maximpolyakov.quicklink.neoforge.blockentity.ChemicalPlugBlockEntity;
import com.maximpolyakov.quicklink.neoforge.blockentity.EnergyPlugBlockEntity;
import com.maximpolyakov.quicklink.neoforge.blockentity.FluidPlugBlockEntity;
import com.maximpolyakov.quicklink.neoforge.blockentity.ItemPlugBlockEntity;
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
        if (QuickLinkNeoForge.MEKANISM_LOADED) {
            reg.registerBlockDataProvider(JadePlugDataProvider.INSTANCE, ChemicalPlugBlockEntity.class);
        }
    }

    @Override
    public void registerClient(IWailaClientRegistration reg) {
        reg.registerBlockComponent(JadePlugRenderer.INSTANCE, EnergyPlugBlock.class);
        reg.registerBlockComponent(JadePlugRenderer.INSTANCE, FluidPlugBlock.class);
        reg.registerBlockComponent(JadePlugRenderer.INSTANCE, ItemPlugBlock.class);
        if (QuickLinkNeoForge.MEKANISM_LOADED) {
            reg.registerBlockComponent(JadePlugRenderer.INSTANCE, ChemicalPlugBlock.class);
        }
    }
}
