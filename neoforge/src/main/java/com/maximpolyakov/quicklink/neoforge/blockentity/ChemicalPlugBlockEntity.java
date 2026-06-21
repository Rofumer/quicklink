package com.maximpolyakov.quicklink.neoforge.blockentity;

import com.maximpolyakov.quicklink.neoforge.config.QuickLinkConfig;
import com.maximpolyakov.quicklink.neoforge.UpgradeTier;
import com.maximpolyakov.quicklink.QuickLinkColors;
import com.maximpolyakov.quicklink.QuickLinkNbt;
import com.maximpolyakov.quicklink.neoforge.QuickLinkNeoForge;
import com.maximpolyakov.quicklink.neoforge.network.QuickLinkChemicalNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class ChemicalPlugBlockEntity extends BlockEntity {

    static int period = QuickLinkConfig.CHEMICAL_TICK_PERIOD.get();

    private int upgradeTier = 0;

    private int plugMask = 0;
    private int pointMask = 0;
    private int disabledMask = 0;
    private final int[] rrIndexBySide = new int[6];

    private final QuickLinkColors[] sideColors = new QuickLinkColors[6];
    private boolean enabled = true;

    private java.util.Set<Integer> lastRegPlugKeys  = new java.util.HashSet<>();
    private java.util.Set<Integer> lastRegPointKeys = new java.util.HashSet<>();

    public ChemicalPlugBlockEntity(BlockPos pos, BlockState state) {
        super(QuickLinkNeoForge.CHEMICAL_PLUG_BE.get(), pos, state);
        for (int i = 0; i < 6; i++) sideColors[i] = QuickLinkColors.unset();
    }

    // ---- helpers ----

    static int dirIndex(Direction d) {
        int idx = d.get3DDataValue();
        if (idx < 0) idx = 0;
        if (idx > 5) idx = 5;
        return idx;
    }

    private static int bit(Direction d) { return 1 << dirIndex(d); }

    private static int clampMask6(int m) { return m & 0b111111; }

    int[] getRrIndexBySide() { return rrIndexBySide; }

    // ---- upgrade tier ----

    public int getUpgradeTier() { return upgradeTier; }

    public void setUpgradeTier(int tier) {
        upgradeTier = Math.max(0, Math.min(UpgradeTier.MAX_TIER, tier));
        setChangedAndSync();
    }

    public long effectiveAmountMb() {
        return (long) QuickLinkConfig.CHEMICAL_TRANSFER_MB.get() * UpgradeTier.multiplier(upgradeTier);
    }

    // ---- public color / network API ----

    public QuickLinkColors getColors(Direction side) { return sideColors[dirIndex(side)]; }

    public void setColors(QuickLinkColors colors) {
        QuickLinkColors safe = (colors == null) ? QuickLinkColors.unset() : colors;
        for (int i = 0; i < 6; i++) sideColors[i] = safe;
        setChangedAndSync();
        syncRegistration();
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
        setChangedAndSync();
        syncRegistration();
    }

    public int getNetworkKey(Direction side) { return sideColors[dirIndex(side)].networkKey(); }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean e) {
        this.enabled = e;
        setChangedAndSync();
    }

    public void setColor(Direction side, int slot, byte colorId) {
        int idx = dirIndex(side);
        int oldKey = sideColors[idx].networkKey();
        sideColors[idx] = sideColors[idx].with(slot, colorId);
        setChangedAndSync();
        if (oldKey != sideColors[idx].networkKey()) syncRegistration();
    }

    // ---- side roles ----

    public enum SideRole { NONE, PLUG, POINT, BOTH }

    public SideRole getRole(Direction side) {
        int b = bit(side);
        boolean p = (plugMask  & b) != 0;
        boolean t = (pointMask & b) != 0;
        if (p && t) return SideRole.BOTH;
        if (p) return SideRole.PLUG;
        if (t) return SideRole.POINT;
        return SideRole.NONE;
    }

    public boolean isSideEnabled(Direction side) { return (disabledMask & bit(side)) == 0; }

    public boolean isPlugEnabled(Direction side) {
        SideRole r = getRole(side);
        return (r == SideRole.PLUG || r == SideRole.BOTH) && isSideEnabled(side);
    }

    public boolean isPointEnabled(Direction side) {
        SideRole r = getRole(side);
        return (r == SideRole.POINT || r == SideRole.BOTH) && isSideEnabled(side);
    }

    public SideRole cycleRole(Direction side) {
        SideRole cur = getRole(side);
        SideRole next = switch (cur) {
            case NONE  -> SideRole.PLUG;
            case PLUG  -> SideRole.POINT;
            case POINT -> SideRole.BOTH;
            case BOTH  -> SideRole.NONE;
        };
        int b = bit(side);
        plugMask  &= ~b;
        pointMask &= ~b;
        if      (next == SideRole.PLUG)  plugMask  |= b;
        else if (next == SideRole.POINT) pointMask |= b;
        else if (next == SideRole.BOTH) { plugMask |= b; pointMask |= b; }
        else disabledMask &= ~b;
        plugMask     = clampMask6(plugMask);
        pointMask    = clampMask6(pointMask);
        disabledMask = clampMask6(disabledMask);
        setChangedAndSync();
        syncRegistration();
        return next;
    }

    public boolean toggleSideEnabled(Direction side) {
        if (getRole(side) == SideRole.NONE) return false;
        int b = bit(side);
        disabledMask ^= b;
        disabledMask  = clampMask6(disabledMask);
        setChangedAndSync();
        syncRegistration();
        return true;
    }

    public int getPlugMask()     { return plugMask; }
    public int getPointMask()    { return pointMask; }
    public int getDisabledMask() { return disabledMask; }

    // ---- lifecycle ----

    private void setChangedAndSync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) {
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

    private void unregisterFromManager() {
        if (!(level instanceof ServerLevel sl)) return;
        QuickLinkChemicalNetworkManager mgr = QuickLinkChemicalNetworkManager.get(sl);
        for (int key : lastRegPlugKeys)  mgr.unregisterPlug(sl, key, worldPosition);
        for (int key : lastRegPointKeys) mgr.unregisterPoint(sl, key, worldPosition);
        lastRegPlugKeys.clear();
        lastRegPointKeys.clear();
    }

    private void syncRegistration() {
        if (!(level instanceof ServerLevel sl)) return;
        unregisterFromManager();

        java.util.Set<Integer> plugKeys  = new java.util.HashSet<>();
        java.util.Set<Integer> pointKeys = new java.util.HashSet<>();
        for (Direction side : Direction.values()) {
            if (isPlugEnabled(side))  plugKeys.add(getNetworkKey(side));
            if (isPointEnabled(side)) pointKeys.add(getNetworkKey(side));
        }

        QuickLinkChemicalNetworkManager mgr = QuickLinkChemicalNetworkManager.get(sl);
        for (int key : plugKeys)  mgr.registerPlug(sl, key, worldPosition);
        for (int key : pointKeys) mgr.registerPoint(sl, key, worldPosition);

        lastRegPlugKeys  = plugKeys;
        lastRegPointKeys = pointKeys;
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

    // ---- server tick ----

    public static void serverTick(Level level, BlockPos pos, BlockState state, ChemicalPlugBlockEntity be) {
        if (!(level instanceof ServerLevel sl)) return;
        if (!be.enabled) return;
        if ((sl.getGameTime() % period) != 0L) return;
        if (!QuickLinkNeoForge.MEKANISM_LOADED) return;

        for (Direction plugSide : Direction.values()) {
            if (be.isPlugEnabled(plugSide)) {
                ChemCompatLayer.tryPushToNeighbor(be, sl, plugSide, be.effectiveAmountMb());
            }
        }
    }

    // ---- NBT ----

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putIntArray(QuickLinkNbt.SIDE_COLORS, getSideColorsPacked());
        tag.putInt(QuickLinkNbt.COLORS, sideColors[0].pack());
        tag.putBoolean(QuickLinkNbt.ENABLED, enabled);
        tag.putInt("ql_plug_mask",     clampMask6(plugMask));
        tag.putInt("ql_point_mask",    clampMask6(pointMask));
        tag.putInt("ql_disabled_mask", clampMask6(disabledMask));
        tag.putIntArray("ql_rr_side",  rrIndexBySide);
        tag.putInt(QuickLinkNbt.UPGRADE_TIER, upgradeTier);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        if (tag.contains(QuickLinkNbt.SIDE_COLORS, Tag.TAG_INT_ARRAY)) {
            int[] packed = tag.getIntArray(QuickLinkNbt.SIDE_COLORS);
            for (int i = 0; i < 6; i++) {
                int v = (packed.length > i) ? packed[i] : QuickLinkColors.unset().pack();
                sideColors[i] = QuickLinkColors.unpack(v);
            }
        } else {
            int packed = tag.contains(QuickLinkNbt.COLORS, Tag.TAG_INT)
                    ? tag.getInt(QuickLinkNbt.COLORS) : QuickLinkColors.unset().pack();
            QuickLinkColors legacy = QuickLinkColors.unpack(packed);
            for (int i = 0; i < 6; i++) sideColors[i] = legacy;
        }

        enabled = !tag.contains(QuickLinkNbt.ENABLED, Tag.TAG_BYTE) || tag.getBoolean(QuickLinkNbt.ENABLED);

        plugMask     = clampMask6(tag.contains("ql_plug_mask",     Tag.TAG_INT) ? tag.getInt("ql_plug_mask")     : 0);
        pointMask    = clampMask6(tag.contains("ql_point_mask",    Tag.TAG_INT) ? tag.getInt("ql_point_mask")    : 0);
        disabledMask = clampMask6(tag.contains("ql_disabled_mask", Tag.TAG_INT) ? tag.getInt("ql_disabled_mask") : 0);

        if (tag.contains("ql_rr_side", Tag.TAG_INT_ARRAY)) {
            int[] arr = tag.getIntArray("ql_rr_side");
            for (int i = 0; i < 6; i++)
                rrIndexBySide[i] = (arr != null && arr.length > i) ? Math.max(0, arr[i]) : 0;
        } else {
            for (int i = 0; i < 6; i++) rrIndexBySide[i] = 0;
        }

        upgradeTier = Math.max(0, Math.min(UpgradeTier.MAX_TIER,
            tag.contains(QuickLinkNbt.UPGRADE_TIER, Tag.TAG_INT) ? tag.getInt(QuickLinkNbt.UPGRADE_TIER) : 0));
    }
}
