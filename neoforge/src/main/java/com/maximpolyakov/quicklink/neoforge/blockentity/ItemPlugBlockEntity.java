package com.maximpolyakov.quicklink.neoforge.blockentity;

import com.maximpolyakov.quicklink.neoforge.config.QuickLinkConfig;
import com.maximpolyakov.quicklink.QuickLinkColors;
import com.maximpolyakov.quicklink.QuickLinkNbt;
import com.maximpolyakov.quicklink.neoforge.QuickLinkNeoForge;
import com.maximpolyakov.quicklink.neoforge.network.QuickLinkNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ItemPlugBlockEntity extends BlockEntity {

    // ===== SPEED =====
    //private static final int MOVE_BATCH = 8; // <<< скорость передачи
    int moveBatch = QuickLinkConfig.ITEM_MOVE_BATCH.get();
    static int period = QuickLinkConfig.ITEM_TICK_PERIOD.get();

    // ---- per-side roles ----
    private int plugMask = 0;
    private int pointMask = 0;
    private int disabledMask = 0;

    private final int[] rrIndexBySide = new int[6];

    private QuickLinkColors colors = QuickLinkColors.unset();
    private boolean enabled = true;

    private int lastRegKey = Integer.MIN_VALUE;
    private boolean lastRegHadPlug = false;
    private boolean lastRegHadPoint = false;
    private final IItemHandler[] sideCapabilities = new IItemHandler[6];

    public ItemPlugBlockEntity(BlockPos pos, BlockState state) {
        super(QuickLinkNeoForge.ITEM_PLUG_BE.get(), pos, state);
        for (Direction side : Direction.values()) {
            sideCapabilities[dirIndex(side)] = new SideItemHandler(this, side);
        }
    }

    // ------------------------------------------------
    // helpers
    // ------------------------------------------------

    private static int bit(Direction d) {
        return 1 << d.get3DDataValue();
    }

    private static int clampMask6(int m) {
        return m & 0b111111;
    }

    private static int dirIndex(Direction d) {
        return Math.max(0, Math.min(5, d.get3DDataValue()));
    }

    // ------------------------------------------------
    // colors / network
    // ------------------------------------------------

    public QuickLinkColors getColors() { return colors; }

    public void setColors(QuickLinkColors colors) {
        this.colors = (colors == null) ? QuickLinkColors.unset() : colors;
        setChangedAndSync();
        syncRegistration();
    }

    public int getNetworkKey() {
        return colors.networkKey();
    }

    public void setColor(int slot, byte colorId) {
        int oldKey = getNetworkKey();
        colors = colors.with(slot, colorId);
        setChangedAndSync();

        if (oldKey != getNetworkKey()) {
            syncRegistration();
        }
    }

    // ------------------------------------------------
    // roles
    // ------------------------------------------------

    public enum SideRole { NONE, PLUG, POINT }

    public SideRole getRole(Direction side) {
        int b = bit(side);
        if ((plugMask & b) != 0) return SideRole.PLUG;
        if ((pointMask & b) != 0) return SideRole.POINT;
        return SideRole.NONE;
    }

    public boolean isSideEnabled(Direction side) {
        return (disabledMask & bit(side)) == 0;
    }

    public boolean isPlugEnabled(Direction side) {
        return getRole(side) == SideRole.PLUG && isSideEnabled(side);
    }

    public boolean isPointEnabled(Direction side) {
        return getRole(side) == SideRole.POINT && isSideEnabled(side);
    }

    public SideRole cycleRole(Direction side) {
        SideRole cur = getRole(side);
        SideRole next = switch (cur) {
            case NONE -> SideRole.PLUG;
            case PLUG -> SideRole.POINT;
            case POINT -> SideRole.NONE;
        };

        int b = bit(side);
        plugMask &= ~b;
        pointMask &= ~b;

        if (next == SideRole.PLUG) plugMask |= b;
        if (next == SideRole.POINT) pointMask |= b;
        if (next == SideRole.NONE) disabledMask &= ~b;

        plugMask = clampMask6(plugMask);
        pointMask = clampMask6(pointMask);
        disabledMask = clampMask6(disabledMask);

        setChangedAndSync();
        syncRegistration();
        return next;
    }

    public boolean toggleSideEnabled(Direction side) {
        if (getRole(side) == SideRole.NONE) return false;
        disabledMask ^= bit(side);
        disabledMask = clampMask6(disabledMask);
        setChangedAndSync();
        syncRegistration();
        return true;
    }

    // ------------------------------------------------
    // lifecycle
    // ------------------------------------------------

    private void setChangedAndSync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) syncRegistration();
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) unregisterFromManager();
        super.setRemoved();
    }

    // ------------------------------------------------
    // registration
    // ------------------------------------------------

    private boolean hasAnyEffectivePlug() {
        return (plugMask & ~disabledMask) != 0;
    }

    private boolean hasAnyEffectivePoint() {
        return (pointMask & ~disabledMask) != 0;
    }

    private void unregisterFromManager() {
        if (!(level instanceof ServerLevel sl)) return;
        if (lastRegKey == Integer.MIN_VALUE) return;

        QuickLinkNetworkManager mgr = QuickLinkNetworkManager.get(sl);
        if (lastRegHadPlug) mgr.unregisterPlug(sl, lastRegKey, worldPosition);
        if (lastRegHadPoint) mgr.unregisterPoint(sl, lastRegKey, worldPosition);

        lastRegKey = Integer.MIN_VALUE;
        lastRegHadPlug = false;
        lastRegHadPoint = false;
    }

    private void syncRegistration() {
        if (!(level instanceof ServerLevel sl)) return;

        int key = getNetworkKey();
        boolean nowPlug = hasAnyEffectivePlug();
        boolean nowPoint = hasAnyEffectivePoint();

        if (lastRegKey != Integer.MIN_VALUE) unregisterFromManager();

        QuickLinkNetworkManager mgr = QuickLinkNetworkManager.get(sl);
        if (nowPlug) mgr.registerPlug(sl, key, worldPosition);
        if (nowPoint) mgr.registerPoint(sl, key, worldPosition);

        lastRegKey = key;
        lastRegHadPlug = nowPlug;
        lastRegHadPoint = nowPoint;
    }

    // ------------------------------------------------
    // ticking
    // ------------------------------------------------

    public static void serverTick(Level level, BlockPos pos, BlockState state, ItemPlugBlockEntity be) {
        if (!(level instanceof ServerLevel sl)) return;
        if (!be.enabled) return;

        long gt = sl.getGameTime();
        //if ((sl.getGameTime() % 10L) != 0L) return;
        if ((gt % period) != 0L) return;

        for (Direction side : Direction.values()) {
            if (be.isPointEnabled(side)) {
                be.tryPullOnce(sl, side);
            }
        }
    }

    @Nullable
    public IItemHandler getExternalItemHandler(@Nullable Direction side) {
        if (side == null) return null;
        if (!isSideEnabled(side)) return null;
        if (getRole(side) == SideRole.NONE) return null;
        return sideCapabilities[dirIndex(side)];
    }

    private int receiveIntoNetwork(Direction inputSide, ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || !isPlugEnabled(inputSide) || !(level instanceof ServerLevel sl)) return 0;

        QuickLinkNetworkManager mgr = QuickLinkNetworkManager.get(sl);
        List<QuickLinkNetworkManager.GlobalPosRef> points = mgr.getPointsSnapshot(getNetworkKey());
        if (points.isEmpty()) return 0;

        ItemStack remaining = stack.copy();
        int moved = 0;
        int start = rrIndexBySide[dirIndex(inputSide)];

        for (int i = 0; i < points.size() && !remaining.isEmpty(); i++) {
            int idx = (start + i) % points.size();
            QuickLinkNetworkManager.GlobalPosRef ref = points.get(idx);
            ServerLevel pointLevel = sl.getServer().getLevel(ref.dimension());
            if (pointLevel == null) continue;

            BlockEntity other = pointLevel.getBlockEntity(ref.pos());
            if (!(other instanceof ItemPlugBlockEntity pointBe) || !pointBe.enabled) continue;

            for (Direction pointSide : Direction.values()) {
                if (!pointBe.isPointEnabled(pointSide)) continue;
                IItemHandler dst = getAttachedItemHandler(pointLevel, ref.pos(), pointSide);
                if (dst == null) continue;

                ItemStack before = remaining.copy();
                remaining = insertStack(dst, remaining, simulate);
                moved += before.getCount() - remaining.getCount();

                if (before.getCount() != remaining.getCount() && !simulate) {
                    rrIndexBySide[dirIndex(inputSide)] = (idx + 1) % points.size();
                    setChanged();
                }

                if (remaining.isEmpty()) break;
            }
        }

        return moved;
    }

    private ItemStack extractFromNetwork(Direction outputSide, int amount, boolean simulate) {
        if (amount <= 0 || !isPointEnabled(outputSide) || !(level instanceof ServerLevel sl)) return ItemStack.EMPTY;

        QuickLinkNetworkManager mgr = QuickLinkNetworkManager.get(sl);
        List<QuickLinkNetworkManager.GlobalPosRef> plugs = mgr.getPlugsSnapshot(getNetworkKey());
        if (plugs.isEmpty()) return ItemStack.EMPTY;

        int start = rrIndexBySide[dirIndex(outputSide)];

        for (int i = 0; i < plugs.size(); i++) {
            int idx = (start + i) % plugs.size();
            QuickLinkNetworkManager.GlobalPosRef ref = plugs.get(idx);
            ServerLevel plugLevel = sl.getServer().getLevel(ref.dimension());
            if (plugLevel == null) continue;

            BlockEntity other = plugLevel.getBlockEntity(ref.pos());
            if (!(other instanceof ItemPlugBlockEntity plugBe) || !plugBe.enabled) continue;

            for (Direction plugSide : Direction.values()) {
                if (!plugBe.isPlugEnabled(plugSide)) continue;

                IItemHandler src = getAttachedItemHandler(plugLevel, ref.pos(), plugSide);
                if (src == null) continue;

                ItemStack extracted = extractAny(src, amount, simulate);
                if (extracted.isEmpty()) continue;

                if (!simulate) {
                    rrIndexBySide[dirIndex(outputSide)] = (idx + 1) % plugs.size();
                    setChanged();
                }
                return extracted;
            }
        }

        return ItemStack.EMPTY;
    }

    private void tryPullOnce(ServerLevel sl, Direction pointSide) {
        IItemHandler dst = getAttachedItemHandler(sl, worldPosition, pointSide);
        if (dst == null) return;

        QuickLinkNetworkManager mgr = QuickLinkNetworkManager.get(sl);
        List<QuickLinkNetworkManager.GlobalPosRef> plugs = mgr.getPlugsSnapshot(getNetworkKey());
        if (plugs.isEmpty()) return;

        int pIdx = dirIndex(pointSide);
        int start = rrIndexBySide[pIdx];

        for (int i = 0; i < plugs.size(); i++) {
            int idx = (start + i) % plugs.size();
            QuickLinkNetworkManager.GlobalPosRef ref = plugs.get(idx);
            ServerLevel plugLevel = sl.getServer().getLevel(ref.dimension());
            if (plugLevel == null) continue;

            BlockPos plugPos = ref.pos();
            BlockEntity other = plugLevel.getBlockEntity(plugPos);
            if (!(other instanceof ItemPlugBlockEntity plugBe)) continue;
            if (!plugBe.enabled) continue;

            for (Direction plugSide : Direction.values()) {
                if (!plugBe.isPlugEnabled(plugSide)) continue;

                IItemHandler src = getAttachedItemHandler(plugLevel, plugPos, plugSide);
                if (src == null) continue;

                int moved = moveItems(src, dst, moveBatch);
                if (moved > 0) {
                    rrIndexBySide[pIdx] = (idx + 1) % plugs.size();
                    setChanged();
                    return;
                }
            }
        }

        rrIndexBySide[pIdx] = (rrIndexBySide[pIdx] + 1) % plugs.size();
        setChanged();
    }

    // ------------------------------------------------
    // container attach (ФИКС)
    // ------------------------------------------------

    private static IItemHandler getAttachedItemHandler(ServerLevel level, BlockPos selfPos, Direction side) {
        BlockPos target = selfPos.relative(side); // <<< ВАЖНО
        Direction targetFaceTowardUs = side.getOpposite();
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, target, targetFaceTowardUs);
        if (handler != null) return handler;

        Container container = HopperBlockEntity.getContainerAt(level, target);
        return container == null ? null : new ContainerItemHandler(container);
    }

    // ------------------------------------------------
    // move items
    // ------------------------------------------------

    private static int moveItems(IItemHandler src, IItemHandler dst, int count) {
        if (count <= 0) return 0;
        int moved = 0;

        for (int i = 0; i < src.getSlots() && moved < count; i++) {
            ItemStack s = src.getStackInSlot(i);
            if (s.isEmpty()) continue;

            while (!s.isEmpty() && moved < count) {
                ItemStack simulated = src.extractItem(i, 1, true);
                if (simulated.isEmpty()) break;

                ItemStack remainder = insertStack(dst, simulated, true);
                if (!remainder.isEmpty()) break;

                ItemStack drained = src.extractItem(i, 1, false);
                if (drained.isEmpty()) break;

                ItemStack leftover = insertStack(dst, drained, false);
                if (!leftover.isEmpty()) break;
                moved++;
                s = src.getStackInSlot(i);
            }
        }

        return moved;
    }

    private static ItemStack insertStack(IItemHandler dst, ItemStack stack, boolean simulate) {
        ItemStack remaining = stack;
        for (int i = 0; i < dst.getSlots() && !remaining.isEmpty(); i++) {
            remaining = dst.insertItem(i, remaining, simulate);
        }
        return remaining;
    }

    private static ItemStack extractAny(IItemHandler src, int amount, boolean simulate) {
        for (int i = 0; i < src.getSlots(); i++) {
            ItemStack extracted = src.extractItem(i, amount, simulate);
            if (!extracted.isEmpty()) {
                return extracted;
            }
        }
        return ItemStack.EMPTY;
    }

    private static final class SideItemHandler implements IItemHandler {
        private final ItemPlugBlockEntity owner;
        private final Direction side;

        private SideItemHandler(ItemPlugBlockEntity owner, Direction side) {
            this.owner = owner;
            this.side = side;
        }

        @Override
        public int getSlots() { return 1; }

        @Override
        public ItemStack getStackInSlot(int slot) { return ItemStack.EMPTY; }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot != 0 || stack.isEmpty()) return stack;
            int moved = owner.receiveIntoNetwork(side, stack, simulate);
            if (moved <= 0) return stack;

            ItemStack remaining = stack.copy();
            remaining.shrink(moved);
            return remaining;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot != 0 || amount <= 0) return ItemStack.EMPTY;
            return owner.extractFromNetwork(side, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) { return 64; }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot == 0 && owner.isPlugEnabled(side);
        }
    }

    private static final class ContainerItemHandler implements IItemHandler {
        private final Container container;

        private ContainerItemHandler(Container container) {
            this.container = container;
        }

        @Override
        public int getSlots() {
            return container.getContainerSize();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return container.getItem(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            ItemStack current = container.getItem(slot);

            if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, stack)) {
                return stack;
            }

            int limit = Math.min(stack.getMaxStackSize(), container.getMaxStackSize());
            int canInsert = current.isEmpty() ? limit : (limit - current.getCount());
            if (canInsert <= 0) return stack;

            int toInsert = Math.min(canInsert, stack.getCount());
            if (!simulate) {
                if (current.isEmpty()) {
                    ItemStack inserted = stack.copy();
                    inserted.setCount(toInsert);
                    container.setItem(slot, inserted);
                } else {
                    current.grow(toInsert);
                    container.setItem(slot, current);
                }
                container.setChanged();
            }

            if (toInsert == stack.getCount()) return ItemStack.EMPTY;
            ItemStack remainder = stack.copy();
            remainder.shrink(toInsert);
            return remainder;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (amount <= 0) return ItemStack.EMPTY;
            ItemStack current = container.getItem(slot);
            if (current.isEmpty()) return ItemStack.EMPTY;

            int toExtract = Math.min(amount, current.getCount());
            ItemStack extracted = current.copy();
            extracted.setCount(toExtract);

            if (!simulate) {
                container.removeItem(slot, toExtract);
                container.setChanged();
            }
            return extracted;
        }

        @Override
        public int getSlotLimit(int slot) {
            return container.getMaxStackSize();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return container.canPlaceItem(slot, stack);
        }
    }

    // ------------------------------------------------
    // NBT
    // ------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        tag.putInt(QuickLinkNbt.COLORS, colors.pack());
        tag.putBoolean(QuickLinkNbt.ENABLED, enabled);
        tag.putInt("ql_plug_mask", clampMask6(plugMask));
        tag.putInt("ql_point_mask", clampMask6(pointMask));
        tag.putInt("ql_disabled_mask", clampMask6(disabledMask));
        tag.putIntArray("ql_rr_side", rrIndexBySide);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        int packed = tag.contains(QuickLinkNbt.COLORS, Tag.TAG_INT)
                ? tag.getInt(QuickLinkNbt.COLORS)
                : QuickLinkColors.unset().pack();

        colors = QuickLinkColors.unpack(packed);
        enabled = !tag.contains(QuickLinkNbt.ENABLED, Tag.TAG_BYTE) || tag.getBoolean(QuickLinkNbt.ENABLED);

        plugMask = clampMask6(tag.getInt("ql_plug_mask"));
        pointMask = clampMask6(tag.getInt("ql_point_mask"));
        disabledMask = clampMask6(tag.getInt("ql_disabled_mask"));

        int[] arr = tag.getIntArray("ql_rr_side");
        for (int i = 0; i < 6; i++) {
            rrIndexBySide[i] = (arr.length > i) ? Math.max(0, arr[i]) : 0;
        }

        int both = plugMask & pointMask;
        if (both != 0) plugMask &= ~both;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
