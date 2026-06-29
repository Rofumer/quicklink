package com.maximpolyakov.quicklink.neoforge;

import com.maximpolyakov.quicklink.QuickLink;
import com.maximpolyakov.quicklink.QuickLinkColors;
import com.maximpolyakov.quicklink.neoforge.blockentity.EnergyPlugBlockEntity;
import com.maximpolyakov.quicklink.neoforge.blockentity.FluidPlugBlockEntity;
import com.maximpolyakov.quicklink.neoforge.blockentity.ItemPlugBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = QuickLink.MOD_ID)
public class QuickLinkNeoForgeEvents {

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (!player.isShiftKeyDown()) return;

        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof DyeItem)) return;

        BlockHitResult hit = event.getHitVec();
        if (hit == null) return;

        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockEntity be = level.getBlockEntity(pos);

        if (!(be instanceof ItemPlugBlockEntity)
                && !(be instanceof EnergyPlugBlockEntity)
                && !(be instanceof FluidPlugBlockEntity)) return;

        event.setCanceled(true);

        if (level.isClientSide()) return;

        Direction face = hit.getDirection();
        double lx = hit.getLocation().x - pos.getX();
        double ly = hit.getLocation().y - pos.getY();
        double lz = hit.getLocation().z - pos.getZ();
        int slot = quadSlotFromHit(face, lx, ly, lz);

        if (be instanceof ItemPlugBlockEntity ibe) ibe.setColor(face, slot, QuickLinkColors.UNSET);
        else if (be instanceof EnergyPlugBlockEntity ebe) ebe.setColor(face, slot, QuickLinkColors.UNSET);
        else if (be instanceof FluidPlugBlockEntity fbe) fbe.setColor(face, slot, QuickLinkColors.UNSET);

        player.sendSystemMessage(Component.literal("Color cleared on side " + face));
    }

    private static int quadSlotFromHit(Direction face, double lx, double ly, double lz) {
        double u, v;
        switch (face) {
            case UP    -> { u = lx;       v = 1.0 - lz; }
            case DOWN  -> { u = lx;       v = lz;       }
            case NORTH -> { u = 1.0 - lx; v = ly;       }
            case SOUTH -> { u = lx;       v = ly;       }
            case WEST  -> { u = lz;       v = ly;       }
            case EAST  -> { u = 1.0 - lz; v = ly;       }
            default    -> { u = lx;       v = ly;       }
        }
        int col = (u < 0.5) ? 0 : 1;
        int rowFromTop = (v >= 0.5) ? 0 : 1;
        return rowFromTop * 2 + col;
    }
}
