package com.maximpolyakov.quicklink.neoforge.compat.wthit;

import com.maximpolyakov.quicklink.neoforge.blockentity.EnergyPlugBlockEntity;
import com.maximpolyakov.quicklink.neoforge.blockentity.FluidPlugBlockEntity;
import com.maximpolyakov.quicklink.neoforge.blockentity.ItemPlugBlockEntity;
import mcp.mobius.waila.api.ICommonRegistrar;
import mcp.mobius.waila.api.IWailaCommonPlugin;

public final class QuickLinkWthitCommonPlugin implements IWailaCommonPlugin {

    @Override
    public void register(ICommonRegistrar reg) {
        reg.blockData(WthitPlugDataProvider.INSTANCE, EnergyPlugBlockEntity.class);
        reg.blockData(WthitPlugDataProvider.INSTANCE, FluidPlugBlockEntity.class);
        reg.blockData(WthitPlugDataProvider.INSTANCE, ItemPlugBlockEntity.class);
    }
}
