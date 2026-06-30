package com.maximpolyakov.quicklink.forge.compat.jade;

import com.maximpolyakov.quicklink.forge.compat.PlugCompatData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

public enum JadePlugRenderer implements IBlockComponentProvider {
    ENERGY  (new ResourceLocation("quicklink", "energy_plug")),
    FLUID   (new ResourceLocation("quicklink", "fluid_plug")),
    ITEM    (new ResourceLocation("quicklink", "item_plug")),
    CHEMICAL(new ResourceLocation("quicklink", "chemical_plug"));

    private final ResourceLocation uid;

    JadePlugRenderer(ResourceLocation uid) { this.uid = uid; }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        for (Component line : PlugCompatData.buildTooltip(accessor.getServerData())) {
            tooltip.add(line);
        }
    }

    @Override
    public ResourceLocation getUid() { return uid; }
}
