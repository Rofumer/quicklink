package com.maximpolyakov.quicklink.forge.block;

import com.maximpolyakov.quicklink.QuickLinkColors;
import com.maximpolyakov.quicklink.QuickLinkNbt;
import com.maximpolyakov.quicklink.forge.QuickLinkForge;
import com.maximpolyakov.quicklink.forge.UpgradeTier;
import com.maximpolyakov.quicklink.forge.blockentity.FluidPlugBlockEntity;
import com.maximpolyakov.quicklink.forge.item.QuickLinkUpgradeItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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

    private static final VoxelShape SHAPE = Block.box(6.0, 6.0, 6.0, 10.0, 10.0, 10.0);

    public FluidPlugBlock(Properties props) { super(props); }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    public VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos, CollisionContext ctx) { return SHAPE; }

    @Override
    public VoxelShape getCollisionShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos, CollisionContext ctx) { return SHAPE; }

    @Nullable @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new FluidPlugBlockEntity(pos, state); }

    @Nullable @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, QuickLinkForge.FLUID_PLUG_BE.get(), FluidPlugBlockEntity::serverTick);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);

        if (level.isClientSide) {
            if (stack.getItem() instanceof DyeItem || stack.isEmpty()
                    || stack.is(Items.WATER_BUCKET) || stack.getItem() instanceof QuickLinkUpgradeItem)
                return InteractionResult.SUCCESS;
            return InteractionResult.PASS;
        }

        BlockEntity be0 = level.getBlockEntity(pos);
        if (!(be0 instanceof FluidPlugBlockEntity be)) return InteractionResult.PASS;

        Direction face = hit.getDirection();

        if (stack.getItem() instanceof QuickLinkUpgradeItem) {
            if (be.getUpgradeTier() >= UpgradeTier.MAX_TIER) {
                player.sendSystemMessage(Component.literal("Already at max tier " + UpgradeTier.MAX_TIER));
                return InteractionResult.CONSUME;
            }
            be.setUpgradeTier(be.getUpgradeTier() + 1);
            if (!player.getAbilities().instabuild) stack.shrink(1);
            level.playSound(null, pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 1.0f, 1.2f);
            player.sendSystemMessage(Component.literal("Upgrade tier: " + be.getUpgradeTier() + "/" + UpgradeTier.MAX_TIER));
            return InteractionResult.CONSUME;
        }

        if (stack.isEmpty()) {
            if (player.isShiftKeyDown()) {
                if (be.getUpgradeTier() > 0) {
                    be.setUpgradeTier(be.getUpgradeTier() - 1);
                    ItemStack give = new ItemStack(QuickLinkForge.UPGRADE_ITEM.get());
                    if (!player.getInventory().add(give)) Block.popResource(level, pos, give);
                    level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5f, 1.4f);
                    player.sendSystemMessage(Component.literal("Upgrade removed. Tier: " + be.getUpgradeTier() + "/" + UpgradeTier.MAX_TIER));
                    return InteractionResult.CONSUME;
                }
                boolean toggled = be.toggleSideEnabled(face);
                if (!toggled) { player.sendSystemMessage(Component.literal("Side " + face + ": NONE")); }
                else {
                    var role = be.getRole(face); boolean on = be.isSideEnabled(face);
                    player.sendSystemMessage(Component.literal("Side " + face + ": " + role + " (" + (on ? "ON" : "OFF") + ")"));
                }
            } else {
                var role = be.cycleRole(face);
                boolean on = (role != FluidPlugBlockEntity.SideRole.NONE) && be.isSideEnabled(face);
                player.sendSystemMessage(Component.literal("Side " + face + ": " + role + (role == FluidPlugBlockEntity.SideRole.NONE ? "" : (" (" + (on ? "ON" : "OFF") + ")"))));
            }
            return InteractionResult.CONSUME;
        }

        if (stack.is(Items.WATER_BUCKET)) {
            boolean toggled = be.toggleInfiniteWater(face);
            if (!toggled) player.sendSystemMessage(Component.literal("Infinite water requires POINT or BOTH role on side " + face));
            else player.sendSystemMessage(Component.literal("Infinite water on " + face + ": " + (be.isInfiniteWater(face) ? "ON" : "OFF")));
            return InteractionResult.CONSUME;
        }

        if (!(stack.getItem() instanceof DyeItem dye)) return InteractionResult.PASS;

        double lx = hit.getLocation().x - pos.getX();
        double ly = hit.getLocation().y - pos.getY();
        double lz = hit.getLocation().z - pos.getZ();
        int slot = quadSlotFromHit(face, lx, ly, lz);
        be.setColor(face, slot, (byte) dye.getDyeColor().getId());
        return InteractionResult.CONSUME;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;
        BlockEntity be0 = level.getBlockEntity(pos);
        if (!(be0 instanceof FluidPlugBlockEntity be)) return;
        if (placer instanceof Player player) be.setOwnerUUID(player.getUUID());
        CompoundTag tag = stack.getTag();
        if (tag == null) return;
        if (tag.contains(QuickLinkNbt.SIDE_COLORS)) be.setSideColorsPacked(tag.getIntArray(QuickLinkNbt.SIDE_COLORS));
        else if (tag.contains(QuickLinkNbt.COLORS)) be.setColors(QuickLinkColors.unpack(tag.getInt(QuickLinkNbt.COLORS)));
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        ItemStack drop = new ItemStack(asItem());
        BlockEntity be0 = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (be0 instanceof FluidPlugBlockEntity be) {
            CompoundTag tag = drop.getOrCreateTag();
            tag.putIntArray(QuickLinkNbt.SIDE_COLORS, be.getSideColorsPacked());
            tag.putInt(QuickLinkNbt.COLORS, be.getColors(Direction.NORTH).pack());
            if (be.getUpgradeTier() > 0) return List.of(drop, new ItemStack(QuickLinkForge.UPGRADE_ITEM.get(), be.getUpgradeTier()));
        }
        return List.of(drop);
    }

    @Override
    public void spawnDestroyParticles(Level level, Player player, BlockPos pos, BlockState state) {
        level.levelEvent(player, 2001, pos, Block.getId(Blocks.BLUE_CONCRETE.defaultBlockState()));
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
