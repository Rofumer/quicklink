package com.maximpolyakov.quicklink.forge.blockentity;

import com.maximpolyakov.quicklink.forge.config.QuickLinkConfig;
import com.maximpolyakov.quicklink.forge.UpgradeTier;
import com.maximpolyakov.quicklink.QuickLinkColors;
import com.maximpolyakov.quicklink.QuickLinkNbt;
import com.maximpolyakov.quicklink.forge.QuickLinkForge;
import com.maximpolyakov.quicklink.forge.compat.ftbteams.FTBTeamsCompat;
import com.maximpolyakov.quicklink.forge.network.QuickLinkNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ItemPlugBlockEntity extends BlockEntity {

    static int period = QuickLinkConfig.ITEM_TICK_PERIOD.get();

    private int upgradeTier = 0;
    private int plugMask    = 0;
    private int pointMask   = 0;
    private int disabledMask = 0;
    private final int[] rrIndexBySide = new int[6];

    private final QuickLinkColors[] sideColors = new QuickLinkColors[6];
    private boolean enabled = true;
    private UUID ownerUUID = null;

    private java.util.Set<Integer> lastRegPlugKeys  = new java.util.HashSet<>();
    private java.util.Set<Integer> lastRegPointKeys = new java.util.HashSet<>();

    // Forge capability system: one LazyOptional per side
    private final IItemHandler[] sideHandlers = new IItemHandler[6];
    @SuppressWarnings("unchecked")
    private final LazyOptional<IItemHandler>[] sideOptionals = new LazyOptional[6];

    public ItemPlugBlockEntity(BlockPos pos, BlockState state) {
        super(QuickLinkForge.ITEM_PLUG_BE.get(), pos, state);
        for (Direction side : Direction.values()) {
            int i = dirIndex(side);
            sideColors[i] = QuickLinkColors.unset();
            sideHandlers[i] = new SideItemHandler(this, side);
            sideOptionals[i] = LazyOptional.of(() -> sideHandlers[i]);
        }
        // re-create with correct capture per side
        for (Direction side : Direction.values()) {
            final int i = dirIndex(side);
            sideOptionals[i] = LazyOptional.of(() -> sideHandlers[i]);
        }
    }

    // ---- capability exposure ----

    @NotNull
    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (side != null && cap == ForgeCapabilities.ITEM_HANDLER) {
            if (isSideEnabled(side) && getRole(side) != SideRole.NONE) {
                return sideOptionals[dirIndex(side)].cast();
            }
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        for (LazyOptional<IItemHandler> lo : sideOptionals) lo.invalidate();
    }

    // ---- helpers ----

    private static int bit(Direction d) { return 1 << d.get3DDataValue(); }
    private static int clampMask6(int m) { return m & 0b111111; }
    static int dirIndex(Direction d) { return Math.max(0, Math.min(5, d.get3DDataValue())); }

    // ---- upgrade tier ----

    private int lastSentItems     = 0;
    int         pendingReceivedItems = 0;
    private int lastReceivedItems = 0;

    public int getUpgradeTier() { return upgradeTier; }

    public void setUpgradeTier(int tier) {
        upgradeTier = Math.max(0, Math.min(UpgradeTier.MAX_TIER, tier));
        setChangedAndSync();
    }

    public int effectiveMoveBatch() {
        return QuickLinkConfig.ITEM_MOVE_BATCH.get() * UpgradeTier.multiplier(upgradeTier);
    }

    public int getLastSentItems()     { return lastSentItems; }
    public int getLastReceivedItems() { return lastReceivedItems; }
    public int getTickPeriod()        { return period; }

    // ---- colors / network ----

    public QuickLinkColors getColors(Direction side) { return sideColors[dirIndex(side)]; }

    public void setColors(QuickLinkColors colors) {
        QuickLinkColors safe = (colors == null) ? QuickLinkColors.unset() : colors;
        for (int i = 0; i < 6; i++) sideColors[i] = safe;
        setChangedAndSync(); syncRegistration();
    }

    public int[] getSideColorsPacked() {
        int[] out = new int[6];
        for (int i = 0; i < 6; i++) out[i] = sideColors[i].pack();
        return out;
    }

    public void setSideColorsPacked(int[] packed) {
        for (int i = 0; i < 6; i++) {
            int v = (packed != null && packed.length > i) ? packed[i] : QuickLinkColors.unset().pack();
            sideColors[i] = QuickLinkColors.unpack(v);
        }
        setChangedAndSync(); syncRegistration();
    }

    public int getNetworkKey(Direction side) {
        int colorKey = sideColors[dirIndex(side)].networkKey();
        int teamKey = QuickLinkForge.FTBTEAMS_LOADED ? FTBTeamsCompat.teamComponent(ownerUUID) : 0;
        return colorKey | (teamKey << 16);
    }

    public UUID getOwnerUUID() { return ownerUUID; }
    public void setOwnerUUID(UUID uuid) {
        ownerUUID = uuid;
        setChangedAndSync(); syncRegistration();
    }

    public void setColor(Direction side, int slot, byte colorId) {
        int idx = dirIndex(side);
        int oldKey = sideColors[idx].networkKey();
        sideColors[idx] = sideColors[idx].with(slot, colorId);
        setChangedAndSync();
        if (oldKey != sideColors[idx].networkKey()) syncRegistration();
    }

    // ---- roles ----

    public enum SideRole { NONE, PLUG, POINT, BOTH }

    public SideRole getRole(Direction side) {
        int b = bit(side);
        boolean p = (plugMask & b) != 0, t = (pointMask & b) != 0;
        if (p && t) return SideRole.BOTH;
        if (p) return SideRole.PLUG;
        if (t) return SideRole.POINT;
        return SideRole.NONE;
    }

    public boolean isSideEnabled(Direction side) { return (disabledMask & bit(side)) == 0; }
    public boolean isPlugEnabled(Direction side) { SideRole r = getRole(side); return (r == SideRole.PLUG || r == SideRole.BOTH) && isSideEnabled(side); }
    public boolean isPointEnabled(Direction side) { SideRole r = getRole(side); return (r == SideRole.POINT || r == SideRole.BOTH) && isSideEnabled(side); }

    public SideRole cycleRole(Direction side) {
        SideRole cur = getRole(side);
        SideRole next = switch (cur) {
            case NONE -> SideRole.PLUG; case PLUG -> SideRole.POINT;
            case POINT -> SideRole.BOTH; case BOTH -> SideRole.NONE;
        };
        int b = bit(side);
        plugMask &= ~b; pointMask &= ~b;
        if (next == SideRole.PLUG)  plugMask  |= b;
        if (next == SideRole.POINT) pointMask |= b;
        if (next == SideRole.BOTH)  { plugMask |= b; pointMask |= b; }
        if (next == SideRole.NONE)  disabledMask &= ~b;
        plugMask = clampMask6(plugMask); pointMask = clampMask6(pointMask); disabledMask = clampMask6(disabledMask);
        setChangedAndSync(); syncRegistration();
        return next;
    }

    public boolean toggleSideEnabled(Direction side) {
        if (getRole(side) == SideRole.NONE) return false;
        disabledMask ^= bit(side); disabledMask = clampMask6(disabledMask);
        setChangedAndSync(); syncRegistration();
        return true;
    }

    // ---- lifecycle ----

    private void setChangedAndSync() {
        setChanged();
        if (level != null && !level.isClientSide)
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) syncRegistration();
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) unregisterFromManager();
        super.setRemoved();
    }

    // ---- registration ----

    private void unregisterFromManager() {
        if (!(level instanceof ServerLevel sl)) return;
        QuickLinkNetworkManager mgr = QuickLinkNetworkManager.get(sl);
        for (int key : lastRegPlugKeys)  mgr.unregisterPlug(sl, key, worldPosition);
        for (int key : lastRegPointKeys) mgr.unregisterPoint(sl, key, worldPosition);
        lastRegPlugKeys.clear(); lastRegPointKeys.clear();
    }

    private void syncRegistration() {
        if (!(level instanceof ServerLevel sl)) return;
        unregisterFromManager();
        java.util.Set<Integer> plugKeys = new java.util.HashSet<>(), pointKeys = new java.util.HashSet<>();
        for (Direction side : Direction.values()) {
            if (isPlugEnabled(side))  plugKeys.add(getNetworkKey(side));
            if (isPointEnabled(side)) pointKeys.add(getNetworkKey(side));
        }
        QuickLinkNetworkManager mgr = QuickLinkNetworkManager.get(sl);
        for (int key : plugKeys)  mgr.registerPlug(sl, key, worldPosition);
        for (int key : pointKeys) mgr.registerPoint(sl, key, worldPosition);
        lastRegPlugKeys = plugKeys; lastRegPointKeys = pointKeys;
    }

    // ---- ticking ----

    public static void serverTick(Level level, BlockPos pos, BlockState state, ItemPlugBlockEntity be) {
        if (!(level instanceof ServerLevel sl) || !be.enabled) return;
        if ((sl.getGameTime() % period) != 0L) return;
        be.lastReceivedItems = be.pendingReceivedItems;
        be.pendingReceivedItems = 0;
        int total = 0;
        for (Direction side : Direction.values()) {
            if (be.isPlugEnabled(side)) total += be.tryPushOnce(sl, side);
        }
        be.lastSentItems = total;
    }

    @Nullable
    public IItemHandler getExternalItemHandler(@Nullable Direction side) {
        if (side == null || !isSideEnabled(side) || getRole(side) == SideRole.NONE) return null;
        return sideHandlers[dirIndex(side)];
    }

    private int receiveIntoNetwork(Direction inputSide, ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || !isPointEnabled(inputSide) || !(level instanceof ServerLevel sl)) return 0;
        QuickLinkNetworkManager mgr = QuickLinkNetworkManager.get(sl);
        int networkKey = getNetworkKey(inputSide);
        List<QuickLinkNetworkManager.GlobalPosRef> plugs = mgr.getPlugsSnapshot(networkKey);
        if (plugs.isEmpty()) return 0;

        ItemStack remaining = stack.copy();
        int moved = 0, start = rrIndexBySide[dirIndex(inputSide)];
        for (int i = 0; i < plugs.size() && !remaining.isEmpty(); i++) {
            int idx = (start + i) % plugs.size();
            QuickLinkNetworkManager.GlobalPosRef ref = plugs.get(idx);
            ServerLevel plugLevel = sl.getServer().getLevel(ref.dimension());
            if (plugLevel == null) continue;
            BlockEntity other = plugLevel.getBlockEntity(ref.pos());
            if (!(other instanceof ItemPlugBlockEntity plugBe) || !plugBe.enabled) continue;
            for (Direction plugSide : Direction.values()) {
                if (!plugBe.isPlugEnabled(plugSide) || plugBe.getNetworkKey(plugSide) != networkKey) continue;
                IItemHandler dst = plugBe.getAttachedNeighborHandler(plugSide);
                if (dst == null) continue;
                ItemStack before = remaining.copy();
                remaining = insertStack(dst, remaining, simulate);
                moved += before.getCount() - remaining.getCount();
                if (before.getCount() != remaining.getCount() && !simulate) {
                    rrIndexBySide[dirIndex(inputSide)] = (idx + 1) % plugs.size(); setChanged();
                }
                if (remaining.isEmpty()) break;
            }
        }
        return moved;
    }

    private ItemStack extractFromNetwork(Direction outputSide, int amount, boolean simulate) {
        if (amount <= 0 || !isPlugEnabled(outputSide) || !(level instanceof ServerLevel sl)) return ItemStack.EMPTY;
        QuickLinkNetworkManager mgr = QuickLinkNetworkManager.get(sl);
        int networkKey = getNetworkKey(outputSide);
        List<QuickLinkNetworkManager.GlobalPosRef> points = mgr.getPointsSnapshot(networkKey);
        if (points.isEmpty()) return ItemStack.EMPTY;
        int start = rrIndexBySide[dirIndex(outputSide)];
        for (int i = 0; i < points.size(); i++) {
            int idx = (start + i) % points.size();
            QuickLinkNetworkManager.GlobalPosRef ref = points.get(idx);
            ServerLevel pointLevel = sl.getServer().getLevel(ref.dimension());
            if (pointLevel == null) continue;
            BlockEntity other = pointLevel.getBlockEntity(ref.pos());
            if (!(other instanceof ItemPlugBlockEntity pointBe) || !pointBe.enabled) continue;
            for (Direction pointSide : Direction.values()) {
                if (!pointBe.isPointEnabled(pointSide) || pointBe.getNetworkKey(pointSide) != networkKey) continue;
                IItemHandler src = pointBe.getAttachedNeighborHandler(pointSide);
                if (src == null) continue;
                ItemStack extracted = extractAny(src, amount, simulate);
                if (extracted.isEmpty()) continue;
                if (!simulate) { rrIndexBySide[dirIndex(outputSide)] = (idx + 1) % points.size(); setChanged(); }
                return extracted;
            }
        }
        return ItemStack.EMPTY;
    }

    private int tryPushOnce(ServerLevel sl, Direction plugSide) {
        IItemHandler dst = getAttachedNeighborHandler(plugSide);
        if (dst == null) return 0;
        QuickLinkNetworkManager mgr = QuickLinkNetworkManager.get(sl);
        int networkKey = getNetworkKey(plugSide);
        record Src(ItemPlugBlockEntity be, Direction dir) {}
        List<Src> sources = new ArrayList<>();
        for (QuickLinkNetworkManager.GlobalPosRef ref : mgr.getPointsSnapshot(networkKey)) {
            ServerLevel pl = sl.getServer().getLevel(ref.dimension()); if (pl == null) continue;
            BlockEntity be = pl.getBlockEntity(ref.pos());
            if (!(be instanceof ItemPlugBlockEntity pBe) || !pBe.enabled) continue;
            for (Direction d : Direction.values()) {
                if (!pBe.isPointEnabled(d) || pBe.getNetworkKey(d) != networkKey) continue;
                sources.add(new Src(pBe, d));
            }
        }
        if (sources.isEmpty()) return 0;
        int pIdx = dirIndex(plugSide), start = rrIndexBySide[pIdx] % sources.size();
        for (int i = 0; i < sources.size(); i++) {
            int idx = (start + i) % sources.size(); Src s = sources.get(idx);
            IItemHandler src = s.be().getAttachedNeighborHandler(s.dir()); if (src == null) continue;
            int moved = moveItems(src, dst, effectiveMoveBatch());
            if (moved > 0) { rrIndexBySide[pIdx] = (idx + 1) % sources.size(); setChanged(); s.be().pendingReceivedItems += moved; return moved; }
        }
        rrIndexBySide[pIdx] = (rrIndexBySide[pIdx] + 1) % sources.size(); setChanged();
        return 0;
    }

    @Nullable
    private IItemHandler getAttachedNeighborHandler(Direction side) {
        BlockPos target = worldPosition.relative(side);
        Direction targetFace = side.getOpposite();
        BlockEntity be = level.getBlockEntity(target);
        if (be instanceof ItemPlugBlockEntity plug) return plug.getExternalItemHandler(targetFace);
        if (be != null) {
            IItemHandler h = be.getCapability(ForgeCapabilities.ITEM_HANDLER, targetFace).orElse(null);
            if (h != null) return h;
        }
        Container container = HopperBlockEntity.getContainerAt((ServerLevel) level, target);
        return container == null ? null : new ContainerItemHandler(container);
    }

    // ---- move helpers ----

    private static int moveItems(IItemHandler src, IItemHandler dst, int count) {
        if (count <= 0) return 0; int moved = 0;
        for (int i = 0; i < src.getSlots() && moved < count; i++) {
            ItemStack s = src.getStackInSlot(i); if (s.isEmpty()) continue;
            while (!s.isEmpty() && moved < count) {
                ItemStack sim = src.extractItem(i, 1, true); if (sim.isEmpty()) break;
                ItemStack rem = insertStack(dst, sim, true); if (!rem.isEmpty()) break;
                ItemStack dr = src.extractItem(i, 1, false); if (dr.isEmpty()) break;
                ItemStack lft = insertStack(dst, dr, false); if (!lft.isEmpty()) break;
                moved++; s = src.getStackInSlot(i);
            }
        }
        return moved;
    }

    private static ItemStack insertStack(IItemHandler dst, ItemStack stack, boolean simulate) {
        ItemStack rem = stack;
        for (int i = 0; i < dst.getSlots() && !rem.isEmpty(); i++) rem = dst.insertItem(i, rem, simulate);
        return rem;
    }

    private static ItemStack extractAny(IItemHandler src, int amount, boolean simulate) {
        for (int i = 0; i < src.getSlots(); i++) {
            ItemStack e = src.extractItem(i, amount, simulate); if (!e.isEmpty()) return e;
        }
        return ItemStack.EMPTY;
    }

    // ---- side handler exposed to other blocks ----

    private static final class SideItemHandler implements IItemHandler {
        private final ItemPlugBlockEntity owner; private final Direction side;
        SideItemHandler(ItemPlugBlockEntity o, Direction s) { owner = o; side = s; }

        @Override public int getSlots() { return 1; }
        @Override public @NotNull ItemStack getStackInSlot(int slot) { return ItemStack.EMPTY; }

        @Override public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            if (slot != 0 || stack.isEmpty()) return stack;
            int moved = owner.receiveIntoNetwork(side, stack, simulate);
            if (moved <= 0) return stack;
            ItemStack rem = stack.copy(); rem.shrink(moved); return rem;
        }

        @Override public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot != 0 || amount <= 0) return ItemStack.EMPTY;
            return owner.extractFromNetwork(side, amount, simulate);
        }

        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, @NotNull ItemStack stack) { return slot == 0 && owner.isPointEnabled(side); }
    }

    private static final class ContainerItemHandler implements IItemHandler {
        private final Container container;
        ContainerItemHandler(Container c) { container = c; }

        @Override public int getSlots() { return container.getContainerSize(); }
        @Override public @NotNull ItemStack getStackInSlot(int slot) { return container.getItem(slot); }

        @Override public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            ItemStack current = container.getItem(slot);
            if (!current.isEmpty() && !ItemStack.isSameItemSameTags(current, stack)) return stack;
            int limit = Math.min(stack.getMaxStackSize(), container.getMaxStackSize());
            int canInsert = current.isEmpty() ? limit : (limit - current.getCount());
            if (canInsert <= 0) return stack;
            int toInsert = Math.min(canInsert, stack.getCount());
            if (!simulate) {
                if (current.isEmpty()) { ItemStack ins = stack.copy(); ins.setCount(toInsert); container.setItem(slot, ins); }
                else { current.grow(toInsert); container.setItem(slot, current); }
                container.setChanged();
            }
            if (toInsert == stack.getCount()) return ItemStack.EMPTY;
            ItemStack rem = stack.copy(); rem.shrink(toInsert); return rem;
        }

        @Override public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (amount <= 0) return ItemStack.EMPTY;
            ItemStack current = container.getItem(slot); if (current.isEmpty()) return ItemStack.EMPTY;
            int toExtract = Math.min(amount, current.getCount());
            ItemStack extracted = current.copy(); extracted.setCount(toExtract);
            if (!simulate) { container.removeItem(slot, toExtract); container.setChanged(); }
            return extracted;
        }

        @Override public int getSlotLimit(int slot) { return container.getMaxStackSize(); }
        @Override public boolean isItemValid(int slot, @NotNull ItemStack stack) { return container.canPlaceItem(slot, stack); }
    }

    // ---- NBT ----

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putIntArray(QuickLinkNbt.SIDE_COLORS, getSideColorsPacked());
        tag.putInt(QuickLinkNbt.COLORS, sideColors[0].pack());
        tag.putBoolean(QuickLinkNbt.ENABLED, enabled);
        tag.putInt("ql_schema", 1);
        tag.putInt("ql_plug_mask",     clampMask6(plugMask));
        tag.putInt("ql_point_mask",    clampMask6(pointMask));
        tag.putInt("ql_disabled_mask", clampMask6(disabledMask));
        tag.putIntArray("ql_rr_side",  rrIndexBySide);
        tag.putInt(QuickLinkNbt.UPGRADE_TIER, upgradeTier);
        if (ownerUUID != null) tag.putUUID(QuickLinkNbt.OWNER_UUID, ownerUUID);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains(QuickLinkNbt.SIDE_COLORS, Tag.TAG_INT_ARRAY)) {
            int[] packed = tag.getIntArray(QuickLinkNbt.SIDE_COLORS);
            for (int i = 0; i < 6; i++) sideColors[i] = QuickLinkColors.unpack(packed.length > i ? packed[i] : QuickLinkColors.unset().pack());
        } else {
            QuickLinkColors legacy = QuickLinkColors.unpack(tag.contains(QuickLinkNbt.COLORS, Tag.TAG_INT) ? tag.getInt(QuickLinkNbt.COLORS) : QuickLinkColors.unset().pack());
            for (int i = 0; i < 6; i++) sideColors[i] = legacy;
        }
        enabled = !tag.contains(QuickLinkNbt.ENABLED, Tag.TAG_BYTE) || tag.getBoolean(QuickLinkNbt.ENABLED);
        plugMask     = clampMask6(tag.getInt("ql_plug_mask"));
        pointMask    = clampMask6(tag.getInt("ql_point_mask"));
        if (!tag.contains("ql_schema")) { int t = plugMask; plugMask = pointMask; pointMask = t; }
        disabledMask = clampMask6(tag.getInt("ql_disabled_mask"));
        int[] arr = tag.getIntArray("ql_rr_side");
        for (int i = 0; i < 6; i++) rrIndexBySide[i] = (arr.length > i) ? Math.max(0, arr[i]) : 0;
        upgradeTier = Math.max(0, Math.min(UpgradeTier.MAX_TIER,
                tag.contains(QuickLinkNbt.UPGRADE_TIER, Tag.TAG_INT) ? tag.getInt(QuickLinkNbt.UPGRADE_TIER) : 0));
        ownerUUID = tag.hasUUID(QuickLinkNbt.OWNER_UUID) ? tag.getUUID(QuickLinkNbt.OWNER_UUID) : null;
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag(); saveAdditional(tag); return tag;
    }

    @Nullable @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
