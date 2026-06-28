package com.maximpolyakov.quicklink.forge.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class QuickLinkPlugBlockItem extends BlockItem {

    public QuickLinkPlugBlockItem(Block block, Properties props) {
        super(block, props);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tips, TooltipFlag flag) {
        super.appendHoverText(stack, level, tips, flag);
        tips.add(Component.translatable("tooltip." + getDescriptionId() + ".1").withStyle(ChatFormatting.GRAY));
        tips.add(Component.translatable("tooltip.quicklink.plug.use").withStyle(ChatFormatting.DARK_GRAY));
    }
}
