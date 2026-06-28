package com.maximpolyakov.quicklink.forge.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class QuickLinkUpgradeItem extends Item {

    public QuickLinkUpgradeItem(Properties props) {
        super(props);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tips, TooltipFlag flag) {
        super.appendHoverText(stack, level, tips, flag);
        tips.add(Component.translatable("tooltip." + getDescriptionId() + ".1").withStyle(ChatFormatting.GRAY));
        tips.add(Component.translatable("tooltip." + getDescriptionId() + ".2").withStyle(ChatFormatting.DARK_GRAY));
    }
}
