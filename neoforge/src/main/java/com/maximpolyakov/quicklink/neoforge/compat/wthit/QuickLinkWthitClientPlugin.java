package com.maximpolyakov.quicklink.neoforge.compat.wthit;

import com.maximpolyakov.quicklink.neoforge.QuickLinkNeoForge;
import com.maximpolyakov.quicklink.neoforge.block.ChemicalPlugBlock;
import com.maximpolyakov.quicklink.neoforge.block.EnergyPlugBlock;
import com.maximpolyakov.quicklink.neoforge.block.FluidPlugBlock;
import com.maximpolyakov.quicklink.neoforge.block.ItemPlugBlock;
import mcp.mobius.waila.api.IClientRegistrar;
import mcp.mobius.waila.api.IWailaClientPlugin;

public final class QuickLinkWthitClientPlugin implements IWailaClientPlugin {

    @Override
    public void register(IClientRegistrar reg) {
        reg.body(WthitPlugRenderer.INSTANCE, EnergyPlugBlock.class);
        reg.body(WthitPlugRenderer.INSTANCE, FluidPlugBlock.class);
        reg.body(WthitPlugRenderer.INSTANCE, ItemPlugBlock.class);
        if (QuickLinkNeoForge.MEKANISM_LOADED) {
            reg.body(WthitPlugRenderer.INSTANCE, ChemicalPlugBlock.class);
        }
    }
}
