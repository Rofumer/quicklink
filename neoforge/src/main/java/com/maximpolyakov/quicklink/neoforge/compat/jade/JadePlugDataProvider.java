package com.maximpolyakov.quicklink.neoforge.compat.jade;

import com.maximpolyakov.quicklink.neoforge.compat.PlugCompatData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

public final class JadePlugDataProvider implements IServerDataProvider<BlockAccessor> {

    public static final JadePlugDataProvider INSTANCE =
            new JadePlugDataProvider(Identifier.fromNamespaceAndPath("quicklink", "plug_info"));

    private final Identifier uid;

    private JadePlugDataProvider(Identifier uid) { this.uid = uid; }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        PlugCompatData.write(data, accessor.getBlockEntity());
    }

    @Override
    public Identifier getUid() { return uid; }
}
