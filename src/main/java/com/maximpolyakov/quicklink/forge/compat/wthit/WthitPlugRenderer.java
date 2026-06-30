package com.maximpolyakov.quicklink.forge.compat.wthit;

import com.maximpolyakov.quicklink.forge.compat.PlugCompatData;
import mcp.mobius.waila.api.IBlockAccessor;
import mcp.mobius.waila.api.IBlockComponentProvider;
import mcp.mobius.waila.api.IPluginConfig;
import mcp.mobius.waila.api.ITooltip;
import net.minecraft.network.chat.Component;

@SuppressWarnings("deprecation")
public enum WthitPlugRenderer implements IBlockComponentProvider {
    INSTANCE;

    @Override
    public void appendBody(ITooltip tooltip, IBlockAccessor accessor, IPluginConfig config) {
        for (Component line : PlugCompatData.buildTooltip(accessor.getServerData())) {
            tooltip.addLine(line);
        }
    }
}
