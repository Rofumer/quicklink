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

    public ItemPlugBlockEntity(BlockPos pos, BlockState state) {
        super(QuickLinkNeoForge.ITEM_PLUG_BE.get(), pos, state);
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

    private void tryPullOnce(ServerLevel sl, Direction pointSide) {
        Container dst = getAttachedContainer(sl, worldPosition, pointSide);
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

                Container src = getAttachedContainer(plugLevel, plugPos, plugSide);
                if (src == null) continue;

                //int moved = moveItems(src, dst, MOVE_BATCH);
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

    private static Container getAttachedContainer(ServerLevel level, BlockPos selfPos, Direction side) {
        BlockPos target = selfPos.relative(side); // <<< ВАЖНО
        return HopperBlockEntity.getContainerAt(level, target);
    }

    // ------------------------------------------------
    // move items
    // ------------------------------------------------

    private static int moveItems(Container src, Container dst, int count) {
        if (count <= 0) return 0;
        int moved = 0;

        for (int i = 0; i < src.getContainerSize() && moved < count; i++) {
            ItemStack s = src.getItem(i);
            if (s.isEmpty()) continue;

            while (!s.isEmpty() && moved < count) {
                ItemStack one = s.copy();
                one.setCount(1);

                if (!tryInsertOne(dst, one)) break;

                src.removeItem(i, 1);
                moved++;
                s = src.getItem(i);
            }
        }

        if (moved > 0) {
            src.setChanged();
            dst.setChanged();
        }

        return moved;
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
