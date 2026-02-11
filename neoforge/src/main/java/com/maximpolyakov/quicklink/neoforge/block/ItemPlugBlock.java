package com.maximpolyakov.quicklink.neoforge.block;

import com.maximpolyakov.quicklink.neoforge.blockentity.ItemPlugBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public class ItemPlugBlock extends BaseEntityBlock {

    public static final MapCodec<ItemPlugBlock> CODEC = simpleCodec(ItemPlugBlock::new);

    public ItemPlugBlock(Properties props) {
        super(props);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ItemPlugBlockEntity(pos, state);
    }

    // ======= TICKER =======
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;

        // безопасная проверка типа (NeoForge)
        return (lvl, pos, st, be) -> {
            if (be instanceof ItemPlugBlockEntity ql) {
                ItemPlugBlockEntity.serverTick(lvl, pos, st, ql);
            }
        };
    }

    // ======= EMPTY HAND USE (mode/side) =======
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {

        if (level.isClientSide) return InteractionResult.SUCCESS;

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ItemPlugBlockEntity plug)) return InteractionResult.PASS;

        boolean shift = player.isShiftKeyDown();

        if (shift) {
            // shift+ПКМ: крутим сторону по циклу
            Direction next = nextSide(plug.getSide());
            plug.setSide(next);
            player.displayClientMessage(Component.literal("Side: " + next.getName()), true);
        } else {
            // ПКМ пустой рукой: PLUG/POINT
            plug.toggleMode();
            player.displayClientMessage(Component.literal("Mode: " + plug.getMode().name()), true);
        }

        return InteractionResult.CONSUME;
    }

    private static Direction nextSide(Direction d) {
        // простой цикл: NORTH -> EAST -> SOUTH -> WEST -> UP -> DOWN -> NORTH ...
        return switch (d) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.UP;
            case UP -> Direction.DOWN;
            case DOWN -> Direction.NORTH;
        };
    }

    // ======= DYE USE (as you had) =======
    @Override
    public ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                           Player player, InteractionHand hand, BlockHitResult hit) {

        if (!(stack.getItem() instanceof DyeItem dye)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ItemPlugBlockEntity plug)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        double lx = hit.getLocation().x - pos.getX();
        double ly = hit.getLocation().y - pos.getY();
        double lz = hit.getLocation().z - pos.getZ();

        int slot = quadSlotFromHit(hit.getDirection(), lx, ly, lz);
        byte colorId = (byte) dye.getDyeColor().getId();

        plug.setColor(slot, colorId);

        return ItemInteractionResult.CONSUME;
    }

    private static int quadSlotFromHit(Direction face, double lx, double ly, double lz) {
        double u, v;

        switch (face) {
            case UP -> { u = lx; v = 1.0 - lz; }
            case DOWN -> { u = lx; v = lz; }
            case NORTH -> { u = 1.0 - lx; v = ly; }
            case SOUTH -> { u = lx; v = ly; }
            case WEST -> { u = lz; v = ly; }
            case EAST -> { u = 1.0 - lz; v = ly; }
            default -> { u = lx; v = ly; }
        }

        int col = (u < 0.5) ? 0 : 1;
        int rowFromTop = (v >= 0.5) ? 0 : 1;
        return rowFromTop * 2 + col;
    }
}
