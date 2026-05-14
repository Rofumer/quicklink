package com.maximpolyakov.quicklink.neoforge.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

public class QuickLinkUpgradeItem extends Item {

    public QuickLinkUpgradeItem(Properties props) {
        super(props);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display,
                                Consumer<Component> tooltipAdder, TooltipFlag flag) {
        super.appendHoverText(stack, ctx, display, tooltipAdder, flag);
        tooltipAdder.accept(Component.translatable("tooltip." + getDescriptionId() + ".1").withStyle(ChatFormatting.GRAY));
        tooltipAdder.accept(Component.translatable("tooltip." + getDescriptionId() + ".2").withStyle(ChatFormatting.DARK_GRAY));
    }
}
