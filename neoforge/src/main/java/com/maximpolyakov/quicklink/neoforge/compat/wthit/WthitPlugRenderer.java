package com.maximpolyakov.quicklink.neoforge.compat.wthit;

import com.maximpolyakov.quicklink.neoforge.compat.PlugCompatData;
import mcp.mobius.waila.api.IBlockAccessor;
import mcp.mobius.waila.api.IBlockComponentProvider;
import mcp.mobius.waila.api.IPluginConfig;
import mcp.mobius.waila.api.ITooltip;
import net.minecraft.network.chat.Component;

public enum WthitPlugRenderer implements IBlockComponentProvider {
    INSTANCE;

    @Override
    public void appendBody(ITooltip tooltip, IBlockAccessor accessor, IPluginConfig config) {
        for (Component line : PlugCompatData.buildTooltip(accessor.getData().raw())) {
            tooltip.addLine(line);
        }
    }
}
