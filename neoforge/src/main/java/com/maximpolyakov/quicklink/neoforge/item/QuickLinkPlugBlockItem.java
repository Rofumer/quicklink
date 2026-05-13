package com.maximpolyakov.quicklink.neoforge.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

public class QuickLinkPlugBlockItem extends BlockItem {

    public QuickLinkPlugBlockItem(Block block, Properties props) {
        super(block, props);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> tips, TooltipFlag flag) {
        super.appendHoverText(stack, ctx, tips, flag);
        tips.add(Component.translatable("tooltip." + getDescriptionId() + ".1").withStyle(ChatFormatting.GRAY));
        tips.add(Component.translatable("tooltip.quicklink.plug.use").withStyle(ChatFormatting.DARK_GRAY));
    }
}
