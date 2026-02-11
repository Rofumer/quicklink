package com.maximpolyakov.quicklink.neoforge.blockentity;

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
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ItemPlugBlockEntity extends BlockEntity {

    // ---- per-side roles ----
    // we store 6-bit masks by Direction.get3DDataValue():
    // DOWN=0, UP=1, NORTH=2, SOUTH=3, WEST=4, EAST=5
    private int plugMask = 0;      // sides that act as PLUG (source)
    private int pointMask = 0;     // sides that act as POINT (sink)
    private int disabledMask = 0;  // sides that are OFF (if role != NONE)

    // round-robin index per POINT side (0..5)
    private final int[] rrIndexBySide = new int[6];

    // network key
    private QuickLinkColors colors = QuickLinkColors.unset();

    // global enable (master switch for the whole BE)
    private boolean enabled = true;

    // cached registration state (for clean unregister)
    private int lastRegKey = Integer.MIN_VALUE;
    private boolean lastRegHadPlug = false;
    private boolean lastRegHadPoint = false;

    public ItemPlugBlockEntity(BlockPos pos, BlockState state) {
        super(QuickLinkNeoForge.ITEM_PLUG_BE.get(), pos, state);
    }

    // ---------------- helpers ----------------

    private static int bit(Direction d) {
        int idx = d.get3DDataValue();
        if (idx < 0) idx = 0;
        if (idx > 5) idx = 5;
        return 1 << idx;
    }

    private static int clampMask6(int m) {
        return m & 0b111111;
    }

    private static int dirIndex(Direction d) {
        int idx = d.get3DDataValue();
        if (idx < 0) idx = 0;
        if (idx > 5) idx = 5;
        return idx;
    }

    // ---------------- public API for block/use() ----------------

    public QuickLinkColors getColors() { return colors; }

    public void setColors(QuickLinkColors colors) {
        this.colors = (colors == null) ? QuickLinkColors.unset() : colors;
        setChangedAndSync();
        syncRegistration();
    }

    public int getNetworkKey() {
        return colors.networkKey();
    }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        setChangedAndSync();
        // registration does not depend on enabled (master switch)
    }

    public void setColor(int slot, byte colorId) {
        int oldKey = getNetworkKey();
        colors = colors.with(slot, colorId);
        setChangedAndSync();

        if (oldKey != getNetworkKey()) {
            syncRegistration();
        }

        if (level != null && !level.isClientSide) {
            System.out.println("[QuickLink][BE] setColor pos=" + worldPosition
                    + " slot=" + slot + " colorId=" + (colorId & 0xFF) + " packed=" + colors.pack());
        }
    }

    public enum SideRole { NONE, PLUG, POINT }

    public SideRole getRole(Direction side) {
        int b = bit(side);
        boolean p = (plugMask & b) != 0;
        boolean t = (pointMask & b) != 0;

        // invariant: we never allow both at same time for same side
        if (p) return SideRole.PLUG;
        if (t) return SideRole.POINT;
        return SideRole.NONE;
    }

    public boolean isSideEnabled(Direction side) {
        int b = bit(side);
        return (disabledMask & b) == 0;
    }

    public boolean isPlugEnabled(Direction side) {
        return getRole(side) == SideRole.PLUG && isSideEnabled(side);
    }

    public boolean isPointEnabled(Direction side) {
        return getRole(side) == SideRole.POINT && isSideEnabled(side);
    }

    /**
     * Cycle role for the clicked side:
     * NONE -> PLUG -> POINT -> NONE
     */
    public SideRole cycleRole(Direction side) {
        SideRole cur = getRole(side);
        SideRole next = switch (cur) {
            case NONE -> SideRole.PLUG;
            case PLUG -> SideRole.POINT;
            case POINT -> SideRole.NONE;
        };

        int b = bit(side);

        // clear both bits first
        plugMask &= ~b;
        pointMask &= ~b;

        if (next == SideRole.PLUG) {
            plugMask |= b;
        } else if (next == SideRole.POINT) {
            pointMask |= b;
        } else {
            // NONE: also clear disabled flag (cos nothing to disable)
            disabledMask &= ~b;
        }

        plugMask = clampMask6(plugMask);
        pointMask = clampMask6(pointMask);
        disabledMask = clampMask6(disabledMask);

        setChangedAndSync();
        syncRegistration();
        return next;
    }

    /**
     * Toggle ON/OFF only for that side. If side role is NONE -> do nothing (return false).
     * Returns true if toggled.
     */
    public boolean toggleSideEnabled(Direction side) {
        if (getRole(side) == SideRole.NONE) {
            return false;
        }
        int b = bit(side);
        disabledMask ^= b;
        disabledMask = clampMask6(disabledMask);

        setChangedAndSync();
        syncRegistration(); // because effective plug/point presence can become 0
        return true;
    }

    /** For debug/UI/render later */
    public int getPlugMask() { return plugMask; }
    public int getPointMask() { return pointMask; }
    public int getDisabledMask() { return disabledMask; }

    // ---------------- lifecycle / syncing ----------------

    private void setChangedAndSync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            syncRegistration();
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            unregisterFromManager();
        }
        super.setRemoved();
    }

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

        if (lastRegHadPlug) mgr.unregisterPlug(lastRegKey, worldPosition);
        if (lastRegHadPoint) mgr.unregisterPoint(lastRegKey, worldPosition);

        lastRegKey = Integer.MIN_VALUE;
        lastRegHadPlug = false;
        lastRegHadPoint = false;
    }

    private void syncRegistration() {
        if (!(level instanceof ServerLevel sl)) return;

        int key = getNetworkKey();
        boolean nowPlug = hasAnyEffectivePlug();
        boolean nowPoint = hasAnyEffectivePoint();

        boolean hadSomething = (lastRegKey != Integer.MIN_VALUE) && (lastRegHadPlug || lastRegHadPoint);
        boolean keyChanged = hadSomething && (lastRegKey != key);
        boolean plugChanged = hadSomething && (lastRegHadPlug != nowPlug);
        boolean pointChanged = hadSomething && (lastRegHadPoint != nowPoint);

        if (hadSomething && (keyChanged || plugChanged || pointChanged)) {
            unregisterFromManager();
        }

        QuickLinkNetworkManager mgr = QuickLinkNetworkManager.get(sl);
        if (nowPlug) mgr.registerPlug(key, worldPosition);
        if (nowPoint) mgr.registerPoint(key, worldPosition);

        lastRegKey = key;
        lastRegHadPlug = nowPlug;
        lastRegHadPoint = nowPoint;
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

    // ---------------- ticking / transfer ----------------

    public static void serverTick(Level level, BlockPos pos, BlockState state, ItemPlugBlockEntity be) {
        if (!(level instanceof ServerLevel sl)) return;
        if (!be.enabled) return;

        long gt = sl.getGameTime();
        if ((gt % 10L) != 0L) return;

        for (Direction pointSide : Direction.values()) {
            if (be.isPointEnabled(pointSide)) {
                be.tryPullOnce(sl, pointSide);
            }
        }
    }

    private void tryPullOnce(ServerLevel sl, Direction pointSide) {
        int key = getNetworkKey();

        Container dst = getAttachedContainer(sl, worldPosition, pointSide);
        if (dst == null) return;

        QuickLinkNetworkManager mgr = QuickLinkNetworkManager.get(sl);
        List<BlockPos> plugs = mgr.getPlugsSnapshot(key);
        if (plugs.isEmpty()) return;

        int pIdx = dirIndex(pointSide);
        int start = rrIndexBySide[pIdx];

        for (int i = 0; i < plugs.size(); i++) {
            int idx = (start + i) % plugs.size();
            BlockPos plugPos = plugs.get(idx);

            BlockEntity other = sl.getBlockEntity(plugPos);
            if (!(other instanceof ItemPlugBlockEntity plugBe)) continue;
            if (!plugBe.enabled) continue;

            boolean moved = false;
            for (Direction plugSide : Direction.values()) {
                if (!plugBe.isPlugEnabled(plugSide)) continue;

                Container src = getAttachedContainer(sl, plugPos, plugSide);
                if (src == null) continue;

                if (moveOneItem(src, dst)) {
                    moved = true;
                    break;
                }
            }

            if (moved) {
                rrIndexBySide[pIdx] = (idx + 1) % plugs.size();
                setChanged();
                return;
            }
        }

        rrIndexBySide[pIdx] = (rrIndexBySide[pIdx] + 1) % plugs.size();
        setChanged();
    }

    /**
     * Container attached to side.
     *
     * IMPORTANT: We consider the container is IN FRONT of that face:
     * target = selfPos.relative(side).
     *
     * (Old opposite()-rule was confusing and made sides feel “reversed”.)
     */
    private static Container getAttachedContainer(ServerLevel level, BlockPos selfPos, Direction side) {
        BlockPos target = selfPos.relative(side);
        return HopperBlockEntity.getContainerAt(level, target);
    }

    private static boolean moveOneItem(Container src, Container dst) {
        for (int i = 0; i < src.getContainerSize(); i++) {
            ItemStack s = src.getItem(i);
            if (s.isEmpty()) continue;

            ItemStack one = s.copy();
            one.setCount(1);

            if (tryInsertOne(dst, one)) {
                src.removeItem(i, 1);
                src.setChanged();
                dst.setChanged();
                return true;
            }
        }
        return false;
    }

    private static boolean tryInsertOne(Container dst, ItemStack one) {
        for (int j = 0; j < dst.getContainerSize(); j++) {
            ItemStack d = dst.getItem(j);

            if (d.isEmpty()) {
                dst.setItem(j, one);
                return true;
            }

            if (ItemStack.isSameItemSameComponents(d, one) && d.getCount() < d.getMaxStackSize()) {
                d.grow(1);
                dst.setItem(j, d);
                return true;
            }
        }
        return false;
    }

    // ---------------- NBT ----------------

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

        int defaultPackedColors = QuickLinkColors.unset().pack();
        int packed = tag.contains(QuickLinkNbt.COLORS, Tag.TAG_INT)
                ? tag.getInt(QuickLinkNbt.COLORS)
                : defaultPackedColors;
        colors = QuickLinkColors.unpack(packed);

        enabled = !tag.contains(QuickLinkNbt.ENABLED, Tag.TAG_BYTE) || tag.getBoolean(QuickLinkNbt.ENABLED);

        plugMask = clampMask6(tag.contains("ql_plug_mask", Tag.TAG_INT) ? tag.getInt("ql_plug_mask") : 0);
        pointMask = clampMask6(tag.contains("ql_point_mask", Tag.TAG_INT) ? tag.getInt("ql_point_mask") : 0);
        disabledMask = clampMask6(tag.contains("ql_disabled_mask", Tag.TAG_INT) ? tag.getInt("ql_disabled_mask") : 0);

        if (tag.contains("ql_rr_side", Tag.TAG_INT_ARRAY)) {
            int[] arr = tag.getIntArray("ql_rr_side");
            for (int i = 0; i < 6; i++) {
                rrIndexBySide[i] = (arr != null && arr.length > i) ? Math.max(0, arr[i]) : 0;
            }
        } else {
            for (int i = 0; i < 6; i++) rrIndexBySide[i] = 0;
        }

        int both = plugMask & pointMask;
        if (both != 0) {
            plugMask &= ~both; // prefer POINT if old data had both
        }
    }
}
