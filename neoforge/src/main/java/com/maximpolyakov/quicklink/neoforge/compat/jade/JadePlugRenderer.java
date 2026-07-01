package com.maximpolyakov.quicklink.neoforge.compat.jade;

import com.maximpolyakov.quicklink.neoforge.compat.PlugCompatData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

public final class JadePlugRenderer implements IBlockComponentProvider {

    public static final JadePlugRenderer INSTANCE =
            new JadePlugRenderer(Identifier.fromNamespaceAndPath("quicklink", "plug_info"));

    private final Identifier uid;

    private JadePlugRenderer(Identifier uid) { this.uid = uid; }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        for (Component line : PlugCompatData.buildTooltip(accessor.getServerData())) {
            tooltip.add(line);
        }
    }

    @Override
    public Identifier getUid() { return uid; }
}
