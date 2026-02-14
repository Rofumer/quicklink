package com.maximpolyakov.quicklink.neoforge.block;

import com.maximpolyakov.quicklink.QuickLinkColors;
import com.maximpolyakov.quicklink.QuickLinkNbt;
import com.maximpolyakov.quicklink.neoforge.QuickLinkNeoForge;
import com.maximpolyakov.quicklink.neoforge.blockentity.FluidPlugBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class FluidPlugBlock extends BaseEntityBlock {

    public static final MapCodec<FluidPlugBlock> CODEC = simpleCodec(FluidPlugBlock::new);
    private static final VoxelShape SHAPE = box(2.0, 2.0, 2.0, 14.0, 14.0, 14.0);

    public FluidPlugBlock(Properties props) {
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
        return new FluidPlugBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, QuickLinkNeoForge.FLUID_PLUG_BE.get(), FluidPlugBlockEntity::serverTick);
    }

    @Override
    public ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                           Player player, InteractionHand hand, BlockHitResult hit) {

        if (level.isClientSide) {
            if (stack.getItem() instanceof DyeItem || stack.isEmpty() || stack.is(Items.WATER_BUCKET)) {
                return ItemInteractionResult.SUCCESS;
            }
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        BlockEntity be0 = level.getBlockEntity(pos);
        if (!(be0 instanceof FluidPlugBlockEntity be)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        Direction face = hit.getDirection();

        // Empty hand: roles / enable per clicked face
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
                boolean on = (role != FluidPlugBlockEntity.SideRole.NONE) && be.isSideEnabled(face);
                player.sendSystemMessage(Component.literal("Side " + face + ": " + role + (role == FluidPlugBlockEntity.SideRole.NONE ? "" : (" (" + (on ? "ON" : "OFF") + ")"))));
            }
            return ItemInteractionResult.CONSUME;
        }


        if (stack.is(Items.WATER_BUCKET)) {
            boolean toggled = be.toggleInfiniteWater(face);
            if (!toggled) {
                player.sendSystemMessage(Component.literal("Infinite water requires PLUG role on side " + face));
            } else {
                player.sendSystemMessage(Component.literal(
                        "Infinite water on " + face + ": " + (be.isInfiniteWater(face) ? "ON" : "OFF")
                ));
            }
            return ItemInteractionResult.CONSUME;
        }

        // Dye: set network colors (same as item plug)
        if (!(stack.getItem() instanceof DyeItem dye)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        double lx = hit.getLocation().x - pos.getX();
        double ly = hit.getLocation().y - pos.getY();
        double lz = hit.getLocation().z - pos.getZ();

        int slot = quadSlotFromHit(face, lx, ly, lz);
        byte colorId = (byte) dye.getDyeColor().getId(); // 0..15
        be.setColor(slot, colorId);

        return ItemInteractionResult.CONSUME;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;

        BlockEntity be0 = level.getBlockEntity(pos);
        if (!(be0 instanceof FluidPlugBlockEntity be)) return;

        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return;

        CompoundTag tag = customData.copyTag();
        if (tag.contains(QuickLinkNbt.COLORS)) {
            be.setColors(QuickLinkColors.unpack(tag.getInt(QuickLinkNbt.COLORS)));
        }
    }

    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        ItemStack drop = new ItemStack(asItem());
        BlockEntity be0 = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (be0 instanceof FluidPlugBlockEntity be) {
            CustomData existing = drop.get(DataComponents.CUSTOM_DATA);
            CompoundTag tag = (existing == null) ? new CompoundTag() : existing.copyTag();
            tag.putInt(QuickLinkNbt.COLORS, be.getColors().pack());
            drop.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
        return List.of(drop);
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
