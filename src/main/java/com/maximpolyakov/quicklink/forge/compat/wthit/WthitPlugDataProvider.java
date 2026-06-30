package com.maximpolyakov.quicklink.forge.compat.wthit;

import com.maximpolyakov.quicklink.forge.compat.PlugCompatData;
import mcp.mobius.waila.api.IPluginConfig;
import mcp.mobius.waila.api.IServerAccessor;
import mcp.mobius.waila.api.IServerDataProvider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

@SuppressWarnings("deprecation")
public enum WthitPlugDataProvider implements IServerDataProvider<BlockEntity> {
    INSTANCE;

    @Override
    public void appendServerData(CompoundTag data, IServerAccessor<BlockEntity> accessor, IPluginConfig config) {
        PlugCompatData.write(data, accessor.getTarget());
    }
}
