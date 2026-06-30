package com.maximpolyakov.quicklink.forge.compat.jade;

import com.maximpolyakov.quicklink.forge.compat.PlugCompatData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

public enum JadePlugDataProvider implements IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation UID = new ResourceLocation("quicklink", "plug_info");

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        PlugCompatData.write(data, accessor.getBlockEntity());
    }

    @Override
    public ResourceLocation getUid() { return UID; }
}
