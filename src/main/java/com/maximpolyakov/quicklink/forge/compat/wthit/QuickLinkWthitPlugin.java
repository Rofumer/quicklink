package com.maximpolyakov.quicklink.forge.compat.wthit;

import com.maximpolyakov.quicklink.forge.QuickLinkForge;
import com.maximpolyakov.quicklink.forge.block.ChemicalPlugBlock;
import com.maximpolyakov.quicklink.forge.block.EnergyPlugBlock;
import com.maximpolyakov.quicklink.forge.block.FluidPlugBlock;
import com.maximpolyakov.quicklink.forge.block.ItemPlugBlock;
import com.maximpolyakov.quicklink.forge.blockentity.ChemicalPlugBlockEntity;
import com.maximpolyakov.quicklink.forge.blockentity.EnergyPlugBlockEntity;
import com.maximpolyakov.quicklink.forge.blockentity.FluidPlugBlockEntity;
import com.maximpolyakov.quicklink.forge.blockentity.ItemPlugBlockEntity;
import mcp.mobius.waila.api.IRegistrar;
import mcp.mobius.waila.api.IServerDataProvider;
import mcp.mobius.waila.api.IWailaPlugin;
import mcp.mobius.waila.api.TooltipPosition;
import mcp.mobius.waila.api.WailaPlugin;

@WailaPlugin(id = "quicklink:plug_info")
public final class QuickLinkWthitPlugin implements IWailaPlugin {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public void register(IRegistrar reg) {
        IServerDataProvider p = WthitPlugDataProvider.INSTANCE;
        reg.addBlockData(p, EnergyPlugBlockEntity.class);
        reg.addBlockData(p, FluidPlugBlockEntity.class);
        reg.addBlockData(p, ItemPlugBlockEntity.class);
        if (QuickLinkForge.MEKANISM_LOADED) {
            reg.addBlockData(p, ChemicalPlugBlockEntity.class);
        }

        reg.addComponent(WthitPlugRenderer.INSTANCE, TooltipPosition.BODY, EnergyPlugBlock.class);
        reg.addComponent(WthitPlugRenderer.INSTANCE, TooltipPosition.BODY, FluidPlugBlock.class);
        reg.addComponent(WthitPlugRenderer.INSTANCE, TooltipPosition.BODY, ItemPlugBlock.class);
        if (QuickLinkForge.MEKANISM_LOADED) {
            reg.addComponent(WthitPlugRenderer.INSTANCE, TooltipPosition.BODY, ChemicalPlugBlock.class);
        }
    }
}
