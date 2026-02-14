package com.maximpolyakov.quicklink.neoforge.block;

import com.maximpolyakov.quicklink.neoforge.QuickLinkNeoForge;
import com.maximpolyakov.quicklink.neoforge.blockentity.EnergyPlugBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class EnergyPlugBlock extends BaseEntityBlock {

    public static final MapCodec<EnergyPlugBlock> CODEC = simpleCodec(EnergyPlugBlock::new);
    private static final VoxelShape SHAPE = box(2.0, 2.0, 2.0, 14.0, 14.0, 14.0);

    public EnergyPlugBlock(Properties props) {
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

    @Override
    protected VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EnergyPlugBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, QuickLinkNeoForge.ENERGY_PLUG_BE.get(), EnergyPlugBlockEntity::serverTick);
    }

    @Override
    public ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                           Player player, InteractionHand hand, BlockHitResult hit) {

        if (level.isClientSide) {
            if (stack.getItem() instanceof DyeItem || stack.isEmpty()) {
                return ItemInteractionResult.SUCCESS;
            }
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        BlockEntity be0 = level.getBlockEntity(pos);
        if (!(be0 instanceof EnergyPlugBlockEntity be)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        Direction face = hit.getDirection();

        if (stack.isEmpty()) {
            if (player.isShiftKeyDown()) {
                boolean toggled = be.toggleSideEnabled(face);
                if (!toggled) {
                    player.sendSystemMessage(Component.literal("Side " + face + ": NONE"));
                } else {
                    var role = be.getRole(face);
                    boolean on = be.isSideEnabled(face);
                    player.sendSystemMessage(Component.literal("Side " + face + ": " + role + " (" + (on ? "ON" : "OFF") + ")"));
                }
            } else {
                var role = be.cycleRole(face);
                boolean on = (role != EnergyPlugBlockEntity.SideRole.NONE) && be.isSideEnabled(face);
                player.sendSystemMessage(Component.literal("Side " + face + ": " + role + (role == EnergyPlugBlockEntity.SideRole.NONE ? "" : (" (" + (on ? "ON" : "OFF") + ")"))));
            }
            return ItemInteractionResult.CONSUME;
        }

        if (!(stack.getItem() instanceof DyeItem dye)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        double lx = hit.getLocation().x - pos.getX();
        double ly = hit.getLocation().y - pos.getY();
        double lz = hit.getLocation().z - pos.getZ();

        int slot = quadSlotFromHit(face, lx, ly, lz);
        byte colorId = (byte) dye.getDyeColor().getId();
        be.setColor(slot, colorId);

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
