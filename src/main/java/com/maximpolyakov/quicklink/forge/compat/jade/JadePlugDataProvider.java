package com.maximpolyakov.quicklink.forge.compat.jade;

import com.maximpolyakov.quicklink.forge.compat.PlugCompatData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

public enum JadePlugDataProvider implements IServerDataProvider<BlockAccessor> {
    ENERGY  (new ResourceLocation("quicklink", "energy_plug")),
    FLUID   (new ResourceLocation("quicklink", "fluid_plug")),
    ITEM    (new ResourceLocation("quicklink", "item_plug")),
    CHEMICAL(new ResourceLocation("quicklink", "chemical_plug"));

    private final ResourceLocation uid;

    JadePlugDataProvider(ResourceLocation uid) { this.uid = uid; }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        PlugCompatData.write(data, accessor.getBlockEntity());
    }

    @Override
    public ResourceLocation getUid() { return uid; }
}
