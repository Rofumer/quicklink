package com.maximpolyakov.quicklink.forge.blockentity;

import com.maximpolyakov.quicklink.QuickLinkColors;
import com.maximpolyakov.quicklink.QuickLinkNbt;
import com.maximpolyakov.quicklink.forge.QuickLinkForge;
import com.maximpolyakov.quicklink.forge.UpgradeTier;
import com.maximpolyakov.quicklink.forge.config.QuickLinkConfig;
import com.maximpolyakov.quicklink.forge.network.QuickLinkChemicalNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ChemicalPlugBlockEntity extends BlockEntity {

    static int period = QuickLinkConfig.CHEMICAL_TICK_PERIOD.get();

    private int upgradeTier  = 0;
    private int plugMask     = 0;
    private int pointMask    = 0;
    private int disabledMask = 0;
    final int[] rrIndexBySide = new int[6];

    private final QuickLinkColors[] sideColors = new QuickLinkColors[6];
    private boolean enabled = true;

    private java.util.Set<Integer> lastRegPlugKeys  = new java.util.HashSet<>();
    private java.util.Set<Integer> lastRegPointKeys = new java.util.HashSet<>();

    // Raw type avoids IGasHandler appearing in Signature attribute at class-load time
    @SuppressWarnings({"unchecked", "rawtypes"})
    private final LazyOptional[] gasOptionals = new LazyOptional[6];

    public ChemicalPlugBlockEntity(BlockPos pos, BlockState state) {
        super(QuickLinkForge.CHEMICAL_PLUG_BE.get(), pos, state);
        for (Direction side : Direction.values()) {
            sideColors[dirIndex(side)] = QuickLinkColors.unset();
        }
        for (Direction side : Direction.values()) {
            gasOptionals[dirIndex(side)] = LazyOptional.of(() -> ChemCompatLayer.PlugSideHandler.INSTANCE);
        }
    }

    @NotNull @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (QuickLinkForge.MEKANISM_LOADED && side != null && ChemCompatLayer.isGasCap(cap)) {
            if (isSideEnabled(side) && getRole(side) != SideRole.NONE)
                return gasOptionals[dirIndex(side)].cast();
        }
        return super.getCapability(cap, side);
    }

    @Override public void invalidateCaps() {
        super.invalidateCaps();
        for (LazyOptional<?> lo : gasOptionals) lo.invalidate();
    }

    // ---- helpers ----

    private static int bit(Direction d) { return 1 << d.get3DDataValue(); }
    private static int clampMask6(int m) { return m & 0b111111; }
    public static int dirIndex(Direction d) { return Math.max(0, Math.min(5, d.get3DDataValue())); }

    public int[] getRrIndexBySide() { return rrIndexBySide; }

    // ---- upgrade ----

    private long lastTransferredMb = 0;

    public int getUpgradeTier() { return upgradeTier; }
    public void setUpgradeTier(int tier) { upgradeTier = Math.max(0, Math.min(UpgradeTier.MAX_TIER, tier)); setChangedAndSync(); }
    public long effectiveAmountMb() { return (long) QuickLinkConfig.CHEMICAL_TRANSFER_MB.get() * UpgradeTier.multiplier(upgradeTier); }
    public long getLastTransferredMb() { return lastTransferredMb; }
    public int getTickPeriod() { return period; }

    // ---- colors / network ----

    public QuickLinkColors getColors(Direction side) { return sideColors[dirIndex(side)]; }
    public void setColors(QuickLinkColors colors) {
        QuickLinkColors safe = colors == null ? QuickLinkColors.unset() : colors;
        for (int i = 0; i < 6; i++) sideColors[i] = safe;
        setChangedAndSync(); syncRegistration();
    }
    public int[] getSideColorsPacked() { int[] out = new int[6]; for (int i = 0; i < 6; i++) out[i] = sideColors[i].pack(); return out; }
    public void setSideColorsPacked(int[] packed) {
        for (int i = 0; i < 6; i++) sideColors[i] = QuickLinkColors.unpack((packed != null && packed.length > i) ? packed[i] : QuickLinkColors.unset().pack());
        setChangedAndSync(); syncRegistration();
    }
    public void setColor(Direction side, int slot, byte colorId) {
        int idx = dirIndex(side); int oldKey = sideColors[idx].networkKey();
        sideColors[idx] = sideColors[idx].with(slot, colorId); setChangedAndSync();
        if (oldKey != sideColors[idx].networkKey()) syncRegistration();
    }
    public int getNetworkKey(Direction side) { return sideColors[dirIndex(side)].networkKey(); }

    // ---- roles ----

    public enum SideRole { NONE, PLUG, POINT, BOTH }
    public SideRole getRole(Direction side) {
        int b = bit(side); boolean p = (plugMask & b) != 0, t = (pointMask & b) != 0;
        if (p && t) return SideRole.BOTH; if (p) return SideRole.PLUG; if (t) return SideRole.POINT; return SideRole.NONE;
    }
    public boolean isEnabled() { return enabled; }
    public boolean isSideEnabled(Direction side) { return (disabledMask & bit(side)) == 0; }
    public boolean isPlugEnabled(Direction side) { SideRole r = getRole(side); return (r == SideRole.PLUG || r == SideRole.BOTH) && isSideEnabled(side); }
    public boolean isPointEnabled(Direction side) { SideRole r = getRole(side); return (r == SideRole.POINT || r == SideRole.BOTH) && isSideEnabled(side); }

    public SideRole cycleRole(Direction side) {
        SideRole cur = getRole(side);
        SideRole next = switch (cur) { case NONE -> SideRole.PLUG; case PLUG -> SideRole.POINT; case POINT -> SideRole.BOTH; case BOTH -> SideRole.NONE; };
        int b = bit(side); plugMask &= ~b; pointMask &= ~b;
        if (next == SideRole.PLUG) plugMask |= b; else if (next == SideRole.POINT) pointMask |= b;
        else if (next == SideRole.BOTH) { plugMask |= b; pointMask |= b; } else disabledMask &= ~b;
        plugMask = clampMask6(plugMask); pointMask = clampMask6(pointMask); disabledMask = clampMask6(disabledMask);
        setChangedAndSync(); syncRegistration(); return next;
    }
    public boolean toggleSideEnabled(Direction side) {
        if (getRole(side) == SideRole.NONE) return false;
        disabledMask ^= bit(side); disabledMask = clampMask6(disabledMask); setChangedAndSync(); syncRegistration(); return true;
    }

    // ---- lifecycle ----

    private void setChangedAndSync() {
        setChanged(); if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    @Override public void onLoad() { super.onLoad(); if (level instanceof ServerLevel) syncRegistration(); }
    @Override public void setRemoved() { if (level != null && !level.isClientSide) unregisterFromManager(); super.setRemoved(); }

    private void unregisterFromManager() {
        if (!(level instanceof ServerLevel sl)) return;
        QuickLinkChemicalNetworkManager mgr = QuickLinkChemicalNetworkManager.get(sl);
        for (int key : lastRegPlugKeys)  mgr.unregisterPlug(sl, key, worldPosition);
        for (int key : lastRegPointKeys) mgr.unregisterPoint(sl, key, worldPosition);
        lastRegPlugKeys.clear(); lastRegPointKeys.clear();
    }
    private void syncRegistration() {
        if (!(level instanceof ServerLevel sl)) return; unregisterFromManager();
        java.util.Set<Integer> pk = new java.util.HashSet<>(), ptk = new java.util.HashSet<>();
        for (Direction side : Direction.values()) { if (isPlugEnabled(side)) pk.add(getNetworkKey(side)); if (isPointEnabled(side)) ptk.add(getNetworkKey(side)); }
        QuickLinkChemicalNetworkManager mgr = QuickLinkChemicalNetworkManager.get(sl);
        for (int key : pk) mgr.registerPlug(sl, key, worldPosition); for (int key : ptk) mgr.registerPoint(sl, key, worldPosition);
        lastRegPlugKeys = pk; lastRegPointKeys = ptk;
    }

    // ---- tick ----

    public static void serverTick(Level level, BlockPos pos, BlockState state, ChemicalPlugBlockEntity be) {
        if (!QuickLinkForge.MEKANISM_LOADED) return;
        if (!(level instanceof ServerLevel sl) || !be.enabled) return;
        if ((sl.getGameTime() % period) != 0L) return;
        long total = 0;
        for (Direction side : Direction.values()) {
            if (be.isPlugEnabled(side)) total += ChemCompatLayer.tryPushToNeighbor(be, sl, side, be.effectiveAmountMb());
        }
        be.lastTransferredMb = total;
    }

    // ---- NBT ----

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putIntArray(QuickLinkNbt.SIDE_COLORS, getSideColorsPacked()); tag.putInt(QuickLinkNbt.COLORS, sideColors[0].pack());
        tag.putBoolean(QuickLinkNbt.ENABLED, enabled);
        tag.putInt("ql_plug_mask", clampMask6(plugMask)); tag.putInt("ql_point_mask", clampMask6(pointMask));
        tag.putInt("ql_disabled_mask", clampMask6(disabledMask)); tag.putIntArray("ql_rr_side", rrIndexBySide);
        tag.putInt(QuickLinkNbt.UPGRADE_TIER, upgradeTier);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains(QuickLinkNbt.SIDE_COLORS, Tag.TAG_INT_ARRAY)) {
            int[] packed = tag.getIntArray(QuickLinkNbt.SIDE_COLORS);
            for (int i = 0; i < 6; i++) sideColors[i] = QuickLinkColors.unpack(packed.length > i ? packed[i] : QuickLinkColors.unset().pack());
        } else {
            QuickLinkColors leg = QuickLinkColors.unpack(tag.contains(QuickLinkNbt.COLORS, Tag.TAG_INT) ? tag.getInt(QuickLinkNbt.COLORS) : QuickLinkColors.unset().pack());
            for (int i = 0; i < 6; i++) sideColors[i] = leg;
        }
        enabled = !tag.contains(QuickLinkNbt.ENABLED, Tag.TAG_BYTE) || tag.getBoolean(QuickLinkNbt.ENABLED);
        plugMask     = clampMask6(tag.contains("ql_plug_mask",     Tag.TAG_INT) ? tag.getInt("ql_plug_mask")     : 0);
        pointMask    = clampMask6(tag.contains("ql_point_mask",    Tag.TAG_INT) ? tag.getInt("ql_point_mask")    : 0);
        disabledMask = clampMask6(tag.contains("ql_disabled_mask", Tag.TAG_INT) ? tag.getInt("ql_disabled_mask") : 0);
        if (tag.contains("ql_rr_side", Tag.TAG_INT_ARRAY)) {
            int[] arr = tag.getIntArray("ql_rr_side"); for (int i = 0; i < 6; i++) rrIndexBySide[i] = (arr != null && arr.length > i) ? Math.max(0, arr[i]) : 0;
        }
        upgradeTier = Math.max(0, Math.min(UpgradeTier.MAX_TIER, tag.contains(QuickLinkNbt.UPGRADE_TIER, Tag.TAG_INT) ? tag.getInt(QuickLinkNbt.UPGRADE_TIER) : 0));
    }

    @Override public CompoundTag getUpdateTag() { CompoundTag tag = super.getUpdateTag(); saveAdditional(tag); return tag; }
    @Nullable @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
