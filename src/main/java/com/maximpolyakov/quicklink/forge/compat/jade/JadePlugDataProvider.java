package com.maximpolyakov.quicklink.forge.compat.jade;

import com.maximpolyakov.quicklink.forge.compat.PlugCompatData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

public final class JadePlugDataProvider implements IServerDataProvider<BlockAccessor> {

    public static final JadePlugDataProvider INSTANCE =
            new JadePlugDataProvider(new ResourceLocation("quicklink", "plug_info"));

    private final ResourceLocation uid;

    private JadePlugDataProvider(ResourceLocation uid) { this.uid = uid; }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        PlugCompatData.write(data, accessor.getBlockEntity());
    }

    @Override
    public ResourceLocation getUid() { return uid; }
}
