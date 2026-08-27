package com.maximpolyakov.quicklink.forge.blockentity;

import com.maximpolyakov.quicklink.QuickLinkColors;
import com.maximpolyakov.quicklink.QuickLinkNbt;
import com.maximpolyakov.quicklink.forge.QuickLinkForge;
import com.maximpolyakov.quicklink.forge.UpgradeTier;
import com.maximpolyakov.quicklink.forge.compat.ftbchunks.FTBChunksCompat;
import com.maximpolyakov.quicklink.forge.compat.ftbteams.FTBTeamsCompat;
import com.maximpolyakov.quicklink.forge.config.QuickLinkConfig;
import com.maximpolyakov.quicklink.forge.network.NetworkTransferGuard;
import com.maximpolyakov.quicklink.forge.network.QuickLinkEnergyNetworkManager;
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
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public class EnergyPlugBlockEntity extends BlockEntity {

    static int period = QuickLinkConfig.ENERGY_TICK_PERIOD.get();

    private int upgradeTier  = 0;
    private int plugMask     = 0;
    private int pointMask    = 0;
    private int disabledMask = 0;
    private final int[] rrIndexBySide = new int[6];

    private final QuickLinkColors[] sideColors = new QuickLinkColors[6];
    private boolean enabled = true;
    private UUID ownerUUID = null;

    private java.util.Set<Integer> lastRegPlugKeys  = new java.util.HashSet<>();
    private java.util.Set<Integer> lastRegPointKeys = new java.util.HashSet<>();

    private final IEnergyStorage[] sideHandlers = new IEnergyStorage[6];
    @SuppressWarnings("unchecked")
    private final LazyOptional<IEnergyStorage>[] sideOptionals = new LazyOptional[6];

    public EnergyPlugBlockEntity(BlockPos pos, BlockState state) {
        super(QuickLinkForge.ENERGY_PLUG_BE.get(), pos, state);
        for (Direction side : Direction.values()) {
            int i = dirIndex(side);
            sideColors[i] = QuickLinkColors.unset();
            sideHandlers[i] = new SideEnergyStorage(this, side);
        }
        for (Direction side : Direction.values()) {
            final int i = dirIndex(side);
            sideOptionals[i] = LazyOptional.of(() -> sideHandlers[i]);
        }
    }

    @NotNull @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (side != null && cap == ForgeCapabilities.ENERGY) {
            if (isSideEnabled(side) && getRole(side) != SideRole.NONE)
                return sideOptionals[dirIndex(side)].cast();
        }
        return super.getCapability(cap, side);
    }

    @Override public void invalidateCaps() {
        super.invalidateCaps();
        for (LazyOptional<IEnergyStorage> lo : sideOptionals) lo.invalidate();
    }

    private int lastSentFe     = 0;
    int         pendingSentFe     = 0;
    int         pendingReceivedFe = 0;
    private int lastReceivedFe = 0;

    private static int bit(Direction d) { return 1 << d.get3DDataValue(); }
    private static int clampMask6(int m) { return m & 0b111111; }
    static int dirIndex(Direction d) { return Math.max(0, Math.min(5, d.get3DDataValue())); }

    public int getUpgradeTier() { return upgradeTier; }
    public void setUpgradeTier(int tier) { upgradeTier = Math.max(0, Math.min(UpgradeTier.MAX_TIER, tier)); setChangedAndSync(); }
    public int effectiveTransferFe() { return QuickLinkConfig.ENERGY_TRANSFER_FE.get() * UpgradeTier.multiplier(upgradeTier); }
    public int getLastSentFe()     { return lastSentFe; }
    public int getLastReceivedFe() { return lastReceivedFe; }
    public int getTickPeriod()     { return period; }

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
    public int getNetworkKey(Direction side) {
        int colorKey = sideColors[dirIndex(side)].networkKey();
        int claimHash = QuickLinkForge.FTBCHUNKS_LOADED ? FTBChunksCompat.claimTeamComponent(level, worldPosition) : FTBChunksCompat.NOT_CLAIMED;
        // A claimed chunk takes its team from the claim and ignores ownerUUID, so a plug keeps
        // serving the team even after the player who placed it leaves it. Both sources run the
        // team UUID through FTBTeamsCompat.hashTeamId, so a claim and plain membership of the same
        // team produce the same key and therefore one network.
        int teamKey = (claimHash != FTBChunksCompat.NOT_CLAIMED)
                ? claimHash
                : (QuickLinkForge.FTBTEAMS_LOADED ? FTBTeamsCompat.teamComponent(ownerUUID) : 0);
        return colorKey | (teamKey << 16);
    }

    public UUID getOwnerUUID() { return ownerUUID; }
    public void setOwnerUUID(UUID uuid) {
        ownerUUID = uuid;
        setChangedAndSync(); syncRegistration();
    }

    public enum SideRole { NONE, PLUG, POINT, BOTH }
    public SideRole getRole(Direction side) {
        int b = bit(side); boolean p = (plugMask & b) != 0, t = (pointMask & b) != 0;
        if (p && t) return SideRole.BOTH; if (p) return SideRole.PLUG; if (t) return SideRole.POINT; return SideRole.NONE;
    }
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

    private void setChangedAndSync() {
        setChanged(); if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    // LevelChunk.clearAllBlockEntities() calls onChunkUnloaded() and then setRemoved() on every
    // block entity of an unloading chunk, so setRemoved() alone cannot tell a broken block from an
    // unloaded one. Unregistering on unload drops the plug from the saved network permanently:
    // nothing ever reloads that chunk, because the network only looks up positions it still knows.
    private boolean unloading = false;

    @Override public void onChunkUnloaded() { unloading = true; super.onChunkUnloaded(); }
    @Override public void onLoad() { super.onLoad(); if (level instanceof ServerLevel) syncRegistration(); }
    @Override public void setRemoved() { if (!unloading && level != null && !level.isClientSide) unregisterFromManager(); super.setRemoved(); }

    private void unregisterFromManager() {
        if (!(level instanceof ServerLevel sl)) return;
        QuickLinkEnergyNetworkManager mgr = QuickLinkEnergyNetworkManager.get(sl);
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
        QuickLinkEnergyNetworkManager mgr = QuickLinkEnergyNetworkManager.get(sl);
        for (int key : plugKeys)  mgr.registerPlug(sl, key, worldPosition);
        for (int key : pointKeys) mgr.registerPoint(sl, key, worldPosition);
        lastRegPlugKeys = plugKeys; lastRegPointKeys = pointKeys;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, EnergyPlugBlockEntity be) {
        if (!(level instanceof ServerLevel sl) || !be.enabled) return;
        if ((sl.getGameTime() % period) != 0L) return;
        be.lastReceivedFe = be.pendingReceivedFe;
        be.pendingReceivedFe = 0;
        be.lastSentFe = be.pendingSentFe;
        be.pendingSentFe = 0;
        for (Direction side : Direction.values()) if (be.isPlugEnabled(side)) be.tryTransferOnce(sl, side, be.effectiveTransferFe());
    }

    @Nullable
    public IEnergyStorage getExternalEnergyStorage(@Nullable Direction side) {
        if (side == null || !isSideEnabled(side) || getRole(side) == SideRole.NONE) return null;
        return sideHandlers[dirIndex(side)];
    }

    private int receiveIntoNetwork(Direction inputSide, int amount, boolean simulate) {
        if (amount <= 0 || !isPointEnabled(inputSide) || !(level instanceof ServerLevel sl)) return 0;
        int networkKey = getNetworkKey(inputSide);
        // Already walking this network further up the stack: everything we could hand the energy to
        // is part of the traversal that is asking us to take it. Refuse instead of recursing.
        if (!NetworkTransferGuard.enter(NetworkTransferGuard.Domain.ENERGY, networkKey)) return 0;
        try {
            QuickLinkEnergyNetworkManager mgr = QuickLinkEnergyNetworkManager.get(sl);
            List<QuickLinkEnergyNetworkManager.GlobalPosRef> plugs = mgr.getPlugsSnapshot(networkKey);
            if (plugs.isEmpty()) return 0;
            int moved = 0, left = amount, start = rrIndexBySide[dirIndex(inputSide)];
            for (int i = 0; i < plugs.size() && left > 0; i++) {
                int idx = (start + i) % plugs.size();
                QuickLinkEnergyNetworkManager.GlobalPosRef ref = plugs.get(idx);
                ServerLevel plugLevel = sl.getServer().getLevel(ref.dimension()); if (plugLevel == null) continue;
                BlockEntity other = plugLevel.getBlockEntity(ref.pos());
                if (!(other instanceof EnergyPlugBlockEntity plugBe) || !plugBe.enabled) continue;
                for (Direction plugSide : Direction.values()) {
                    if (!plugBe.isPlugEnabled(plugSide) || plugBe.getNetworkKey(plugSide) != networkKey) continue;
                    // never push straight back out of the side we were fed through
                    if (plugBe == this && plugSide == inputSide) continue;
                    IEnergyStorage dst = plugBe.getAttachedNeighborHandler(plugSide, networkKey);
                    if (dst == null || !dst.canReceive()) continue;
                    int accepted = dst.receiveEnergy(left, simulate); if (accepted <= 0) continue;
                    moved += accepted; left -= accepted;
                    if (!simulate) {
                        rrIndexBySide[dirIndex(inputSide)] = (idx + 1) % plugs.size(); setChanged();
                        pendingReceivedFe += accepted;
                        plugBe.pendingSentFe += accepted;
                    }
                    if (left <= 0) break;
                }
            }
            return moved;
        } finally {
            NetworkTransferGuard.exit(NetworkTransferGuard.Domain.ENERGY, networkKey);
        }
    }

    private int extractFromNetwork(Direction outputSide, int amount, boolean simulate) {
        if (amount <= 0 || !isPlugEnabled(outputSide) || !(level instanceof ServerLevel sl)) return 0;
        int networkKey = getNetworkKey(outputSide);
        if (!NetworkTransferGuard.enter(NetworkTransferGuard.Domain.ENERGY, networkKey)) return 0; // see receiveIntoNetwork
        try {
            QuickLinkEnergyNetworkManager mgr = QuickLinkEnergyNetworkManager.get(sl);
            List<QuickLinkEnergyNetworkManager.GlobalPosRef> points = mgr.getPointsSnapshot(networkKey);
            if (points.isEmpty()) return 0;
            int moved = 0, left = amount, start = rrIndexBySide[dirIndex(outputSide)];
            for (int i = 0; i < points.size() && left > 0; i++) {
                int idx = (start + i) % points.size();
                QuickLinkEnergyNetworkManager.GlobalPosRef ref = points.get(idx);
                ServerLevel pointLevel = sl.getServer().getLevel(ref.dimension()); if (pointLevel == null) continue;
                BlockEntity other = pointLevel.getBlockEntity(ref.pos());
                if (!(other instanceof EnergyPlugBlockEntity pointBe) || !pointBe.enabled) continue;
                for (Direction pointSide : Direction.values()) {
                    if (!pointBe.isPointEnabled(pointSide) || pointBe.getNetworkKey(pointSide) != networkKey) continue;
                    // never source from the very side that is being drained
                    if (pointBe == this && pointSide == outputSide) continue;
                    IEnergyStorage src = pointBe.getAttachedNeighborHandler(pointSide, networkKey);
                    if (src == null || !src.canExtract()) continue;
                    int extracted = src.extractEnergy(left, simulate); if (extracted <= 0) continue;
                    moved += extracted; left -= extracted;
                    if (!simulate) {
                        rrIndexBySide[dirIndex(outputSide)] = (idx + 1) % points.size(); setChanged();
                        pendingSentFe += extracted;
                        pointBe.pendingReceivedFe += extracted;
                    }
                    if (left <= 0) break;
                }
            }
            return moved;
        } finally {
            NetworkTransferGuard.exit(NetworkTransferGuard.Domain.ENERGY, networkKey);
        }
    }

    private void tryTransferOnce(ServerLevel sl, Direction plugSide, int amountFE) {
        int networkKey = getNetworkKey(plugSide);
        // The tick-driven pull traverses this network as well: guarding it stops the neighbour we
        // push into from pulling back through us while this call is still on the stack.
        if (!NetworkTransferGuard.enter(NetworkTransferGuard.Domain.ENERGY, networkKey)) return;
        try {
            IEnergyStorage dst = getAttachedNeighborHandler(plugSide, networkKey); if (dst == null) return;
            QuickLinkEnergyNetworkManager mgr = QuickLinkEnergyNetworkManager.get(sl);
            List<QuickLinkEnergyNetworkManager.GlobalPosRef> points = mgr.getPointsSnapshot(networkKey);
            if (points.isEmpty()) return;
            // Resolve points lazily in round-robin order and stop at the first one that moves energy.
            // getBlockEntity() force-loads the target chunk, so collecting every source up front would
            // load one chunk per network member on every attempt, across every dimension involved.
            int pIdx = dirIndex(plugSide), start = rrIndexBySide[pIdx] % points.size();
            for (int i = 0; i < points.size(); i++) {
                int idx = (start + i) % points.size();
                QuickLinkEnergyNetworkManager.GlobalPosRef ref = points.get(idx);
                ServerLevel pl = sl.getServer().getLevel(ref.dimension()); if (pl == null) continue;
                BlockEntity be = pl.getBlockEntity(ref.pos());
                if (!(be instanceof EnergyPlugBlockEntity pBe) || !pBe.enabled) continue;
                for (Direction d : Direction.values()) {
                    if (!pBe.isPointEnabled(d) || pBe.getNetworkKey(d) != networkKey) continue;
                    // the side we are feeding cannot also be the source for that same push
                    if (pBe == this && d == plugSide) continue;
                    IEnergyStorage src = pBe.getAttachedNeighborHandler(d, networkKey); if (src == null) continue;
                    int moved = moveEnergy(src, dst, amountFE);
                    if (moved > 0) {
                        rrIndexBySide[pIdx] = (idx + 1) % points.size(); setChanged();
                        pBe.pendingReceivedFe += moved;
                        pendingSentFe += moved;
                        return;
                    }
                }
            }
            rrIndexBySide[pIdx] = (rrIndexBySide[pIdx] + 1) % points.size(); setChanged();
        } finally {
            NetworkTransferGuard.exit(NetworkTransferGuard.Domain.ENERGY, networkKey);
        }
    }

    @Nullable
    private IEnergyStorage getAttachedNeighborHandler(Direction side, int excludeNetworkKey) {
        Direction targetFace = side.getOpposite();
        BlockEntity be = level.getBlockEntity(worldPosition.relative(side));
        if (be instanceof EnergyPlugBlockEntity plug) {
            // A plug facing us on the network we are already serving is not an endpoint, it is the
            // same network seen from the other side: routing into it can only come back to us.
            if (plug.isSideEnabled(targetFace) && plug.getRole(targetFace) != SideRole.NONE
                    && plug.getNetworkKey(targetFace) == excludeNetworkKey) return null;
            return plug.getExternalEnergyStorage(targetFace);
        }
        if (be != null) return be.getCapability(ForgeCapabilities.ENERGY, targetFace).orElse(null);
        return null;
    }

    private static int moveEnergy(IEnergyStorage src, IEnergyStorage dst, int amountFE) {
        if (amountFE <= 0 || !src.canExtract() || !dst.canReceive()) return 0;
        // Ask the destination first: some generators (e.g. Thermal Series dynamos) don't honor
        // extractEnergy(amount, simulate=true) correctly, so we avoid relying on the source's simulate.
        int canRx = dst.receiveEnergy(amountFE, true);
        if (canRx <= 0) return 0;
        int extracted = src.extractEnergy(canRx, false);
        if (extracted <= 0) return 0;
        int received = dst.receiveEnergy(extracted, false);
        return received;
    }

    private static final class SideEnergyStorage implements IEnergyStorage {
        private final EnergyPlugBlockEntity owner; private final Direction side;
        SideEnergyStorage(EnergyPlugBlockEntity o, Direction s) { owner = o; side = s; }
        @Override public int receiveEnergy(int max, boolean sim) { return owner.receiveIntoNetwork(side, max, sim); }
        @Override public int extractEnergy(int max, boolean sim) { return owner.extractFromNetwork(side, max, sim); }
        @Override public int getEnergyStored() { return 0; }
        @Override public int getMaxEnergyStored() { return Integer.MAX_VALUE; }
        @Override public boolean canExtract() { return owner.isPlugEnabled(side); }
        @Override public boolean canReceive() { return owner.isPointEnabled(side); }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putIntArray(QuickLinkNbt.SIDE_COLORS, getSideColorsPacked());
        tag.putInt(QuickLinkNbt.COLORS, sideColors[0].pack());
        tag.putBoolean(QuickLinkNbt.ENABLED, enabled);
        tag.putInt("ql_schema", 1);
        tag.putInt("ql_plug_mask", clampMask6(plugMask)); tag.putInt("ql_point_mask", clampMask6(pointMask));
        tag.putInt("ql_disabled_mask", clampMask6(disabledMask)); tag.putIntArray("ql_rr_side", rrIndexBySide);
        tag.putInt(QuickLinkNbt.UPGRADE_TIER, upgradeTier);
        tag.putIntArray(QuickLinkNbt.REG_PLUG_KEYS,  QuickLinkNbt.packKeys(lastRegPlugKeys));
        tag.putIntArray(QuickLinkNbt.REG_POINT_KEYS, QuickLinkNbt.packKeys(lastRegPointKeys));
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
        plugMask = clampMask6(tag.getInt("ql_plug_mask")); pointMask = clampMask6(tag.getInt("ql_point_mask"));
        if (!tag.contains("ql_schema")) { int t = plugMask; plugMask = pointMask; pointMask = t; }
        disabledMask = clampMask6(tag.getInt("ql_disabled_mask"));
        int[] arr = tag.getIntArray("ql_rr_side");
        for (int i = 0; i < 6; i++) rrIndexBySide[i] = (arr.length > i) ? Math.max(0, arr[i]) : 0;
        upgradeTier = Math.max(0, Math.min(UpgradeTier.MAX_TIER, tag.contains(QuickLinkNbt.UPGRADE_TIER, Tag.TAG_INT) ? tag.getInt(QuickLinkNbt.UPGRADE_TIER) : 0));
        lastRegPlugKeys  = QuickLinkNbt.unpackKeys(tag.getIntArray(QuickLinkNbt.REG_PLUG_KEYS));
        lastRegPointKeys = QuickLinkNbt.unpackKeys(tag.getIntArray(QuickLinkNbt.REG_POINT_KEYS));
        ownerUUID = tag.hasUUID(QuickLinkNbt.OWNER_UUID) ? tag.getUUID(QuickLinkNbt.OWNER_UUID) : null;
    }

    @Override public CompoundTag getUpdateTag() { CompoundTag tag = super.getUpdateTag(); saveAdditional(tag); return tag; }
    @Nullable @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
