package com.maximpolyakov.quicklink.neoforge.compat.wthit;

import com.maximpolyakov.quicklink.neoforge.compat.PlugCompatData;
import mcp.mobius.waila.api.IDataProvider;
import mcp.mobius.waila.api.IDataWriter;
import mcp.mobius.waila.api.IPluginConfig;
import mcp.mobius.waila.api.IServerAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;

public enum WthitPlugDataProvider implements IDataProvider<BlockEntity> {
    INSTANCE;

    @Override
    public void appendData(IDataWriter data, IServerAccessor<BlockEntity> accessor, IPluginConfig config) {
        PlugCompatData.write(data.raw(), accessor.getTarget());
    }
}
