package com.maximpolyakov.quicklink.neoforge.blockentity;

import com.maximpolyakov.quicklink.QuickLinkColors;
import com.maximpolyakov.quicklink.QuickLinkNbt;
import com.maximpolyakov.quicklink.neoforge.QuickLinkNeoForge;
import com.maximpolyakov.quicklink.neoforge.config.QuickLinkConfig;
import com.maximpolyakov.quicklink.neoforge.network.QuickLinkEnergyNetworkManager;
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
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class EnergyPlugBlockEntity extends BlockEntity {

    static int transferFE = QuickLinkConfig.ENERGY_TRANSFER_FE.get();
    static int period = QuickLinkConfig.ENERGY_TICK_PERIOD.get();

    private int plugMask = 0;
    private int pointMask = 0;
    private int disabledMask = 0;
    private final int[] rrIndexBySide = new int[6];

    private final QuickLinkColors[] sideColors = new QuickLinkColors[6];
    private boolean enabled = true;

    private java.util.Set<Integer> lastRegPlugKeys = new java.util.HashSet<>();
    private java.util.Set<Integer> lastRegPointKeys = new java.util.HashSet<>();
    private final IEnergyStorage[] sideCapabilities = new IEnergyStorage[6];

    public EnergyPlugBlockEntity(BlockPos pos, BlockState state) {
        super(QuickLinkNeoForge.ENERGY_PLUG_BE.get(), pos, state);
        for (Direction side : Direction.values()) {
            int idx = dirIndex(side);
            sideCapabilities[idx] = new SideEnergyStorage(this, side);
            sideColors[idx] = QuickLinkColors.unset();
        }
    }

    private static int bit(Direction d) { return 1 << d.get3DDataValue(); }
    private static int clampMask6(int m) { return m & 0b111111; }
    private static int dirIndex(Direction d) { return Math.max(0, Math.min(5, d.get3DDataValue())); }

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

    public void setColor(Direction side, int slot, byte colorId) {
        int idx = dirIndex(side);
        int oldKey = sideColors[idx].networkKey();
        sideColors[idx] = sideColors[idx].with(slot, colorId);
        setChangedAndSync();
        if (oldKey != sideColors[idx].networkKey()) syncRegistration();
    }

    public int getNetworkKey(Direction side) { return sideColors[dirIndex(side)].networkKey(); }

    public enum SideRole { NONE, PLUG, POINT, BOTH }

    public SideRole getRole(Direction side) {
        int b = bit(side);
        boolean plug = (plugMask & b) != 0;
        boolean point = (pointMask & b) != 0;
        if (plug && point) return SideRole.BOTH;
        if ((plugMask & b) != 0) return SideRole.PLUG;
        if ((pointMask & b) != 0) return SideRole.POINT;
        return SideRole.NONE;
    }

    public boolean isSideEnabled(Direction side) { return (disabledMask & bit(side)) == 0; }
    public boolean isPlugEnabled(Direction side) {
        SideRole role = getRole(side);
        return (role == SideRole.PLUG || role == SideRole.BOTH) && isSideEnabled(side);
    }

    public boolean isPointEnabled(Direction side) {
        SideRole role = getRole(side);
        return (role == SideRole.POINT || role == SideRole.BOTH) && isSideEnabled(side);
    }

    public SideRole cycleRole(Direction side) {
        SideRole cur = getRole(side);
        SideRole next = switch (cur) {
            case NONE -> SideRole.PLUG;
            case PLUG -> SideRole.POINT;
            case POINT -> SideRole.BOTH;
            case BOTH -> SideRole.NONE;
        };

        int b = bit(side);
        plugMask &= ~b;
        pointMask &= ~b;

        if (next == SideRole.PLUG) plugMask |= b;
        if (next == SideRole.POINT) pointMask |= b;
        if (next == SideRole.BOTH) {
            plugMask |= b;
            pointMask |= b;
        }
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

    private boolean hasAnyEffectivePlug() { return (plugMask & ~disabledMask) != 0; }
    private boolean hasAnyEffectivePoint() { return (pointMask & ~disabledMask) != 0; }

    private void unregisterFromManager() {
        if (!(level instanceof ServerLevel sl)) return;
        QuickLinkEnergyNetworkManager mgr = QuickLinkEnergyNetworkManager.get(sl);

        for (int key : lastRegPlugKeys) mgr.unregisterPlug(sl, key, worldPosition);
        for (int key : lastRegPointKeys) mgr.unregisterPoint(sl, key, worldPosition);

        lastRegPlugKeys.clear();
        lastRegPointKeys.clear();
    }

    private void syncRegistration() {
        if (!(level instanceof ServerLevel sl)) return;

        unregisterFromManager();

        java.util.Set<Integer> plugKeys = new java.util.HashSet<>();
        java.util.Set<Integer> pointKeys = new java.util.HashSet<>();
        for (Direction side : Direction.values()) {
            if (isPlugEnabled(side)) plugKeys.add(getNetworkKey(side));
            if (isPointEnabled(side)) pointKeys.add(getNetworkKey(side));
        }

        QuickLinkEnergyNetworkManager mgr = QuickLinkEnergyNetworkManager.get(sl);
        for (int key : plugKeys) mgr.registerPlug(sl, key, worldPosition);
        for (int key : pointKeys) mgr.registerPoint(sl, key, worldPosition);

        lastRegPlugKeys = plugKeys;
        lastRegPointKeys = pointKeys;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, EnergyPlugBlockEntity be) {
        if (!(level instanceof ServerLevel sl)) return;
        if (!be.enabled) return;

        long gt = sl.getGameTime();
        if ((gt % period) != 0L) return;

        for (Direction side : Direction.values()) {
            if (be.isPointEnabled(side)) {
                be.tryTransferOnce(sl, side, transferFE);
            }
        }
    }

    @Nullable
    public IEnergyStorage getExternalEnergyStorage(@Nullable Direction side) {
        if (side == null) return null;
        if (!isSideEnabled(side)) return null;
        if (getRole(side) == SideRole.NONE) return null;
        return sideCapabilities[dirIndex(side)];
    }

    private int receiveIntoNetwork(Direction inputSide, int amount, boolean simulate) {
        if (amount <= 0 || !isPlugEnabled(inputSide) || !(level instanceof ServerLevel sl)) return 0;

        QuickLinkEnergyNetworkManager mgr = QuickLinkEnergyNetworkManager.get(sl);
        int networkKey = getNetworkKey(inputSide);
        List<QuickLinkEnergyNetworkManager.GlobalPosRef> points = mgr.getPointsSnapshot(networkKey);
        if (points.isEmpty()) return 0;

        int moved = 0;
        int left = amount;
        int start = rrIndexBySide[dirIndex(inputSide)];

        for (int i = 0; i < points.size() && left > 0; i++) {
            int idx = (start + i) % points.size();
            QuickLinkEnergyNetworkManager.GlobalPosRef ref = points.get(idx);
            ServerLevel pointLevel = sl.getServer().getLevel(ref.dimension());
            if (pointLevel == null) continue;

            BlockEntity other = pointLevel.getBlockEntity(ref.pos());
            if (!(other instanceof EnergyPlugBlockEntity pointBe) || !pointBe.enabled) continue;

            for (Direction pointSide : Direction.values()) {
                if (!pointBe.isPointEnabled(pointSide) || pointBe.getNetworkKey(pointSide) != networkKey) continue;
                IEnergyStorage dst = getAttachedEnergyStorage(pointLevel, ref.pos(), pointSide);
                if (dst == null || !dst.canReceive()) continue;

                int accepted = dst.receiveEnergy(left, simulate);
                if (accepted <= 0) continue;

                moved += accepted;
                left -= accepted;

                if (!simulate) {
                    rrIndexBySide[dirIndex(inputSide)] = (idx + 1) % points.size();
                    setChanged();
                }

                if (left <= 0) break;
            }
        }

        return moved;
    }

    private int extractFromNetwork(Direction outputSide, int amount, boolean simulate) {
        if (amount <= 0 || !isPointEnabled(outputSide) || !(level instanceof ServerLevel sl)) return 0;

        QuickLinkEnergyNetworkManager mgr = QuickLinkEnergyNetworkManager.get(sl);
        int networkKey = getNetworkKey(outputSide);
        List<QuickLinkEnergyNetworkManager.GlobalPosRef> plugs = mgr.getPlugsSnapshot(networkKey);
        if (plugs.isEmpty()) return 0;

        int moved = 0;
        int left = amount;
        int start = rrIndexBySide[dirIndex(outputSide)];

        for (int i = 0; i < plugs.size() && left > 0; i++) {
            int idx = (start + i) % plugs.size();
            QuickLinkEnergyNetworkManager.GlobalPosRef ref = plugs.get(idx);
            ServerLevel plugLevel = sl.getServer().getLevel(ref.dimension());
            if (plugLevel == null) continue;

            BlockEntity other = plugLevel.getBlockEntity(ref.pos());
            if (!(other instanceof EnergyPlugBlockEntity plugBe) || !plugBe.enabled) continue;

            for (Direction plugSide : Direction.values()) {
                if (!plugBe.isPlugEnabled(plugSide) || plugBe.getNetworkKey(plugSide) != networkKey) continue;

                IEnergyStorage src = getAttachedEnergyStorage(plugLevel, ref.pos(), plugSide);
                if (src == null || !src.canExtract()) continue;

                int extracted = src.extractEnergy(left, simulate);
                if (extracted <= 0) continue;

                moved += extracted;
                left -= extracted;

                if (!simulate) {
                    rrIndexBySide[dirIndex(outputSide)] = (idx + 1) % plugs.size();
                    setChanged();
                }

                if (left <= 0) break;
            }
        }

        return moved;
    }

    private void tryTransferOnce(ServerLevel sl, Direction pointSide, int amountFE) {
        IEnergyStorage dst = getAttachedEnergyStorage(sl, worldPosition, pointSide);
        if (dst == null) return;

        QuickLinkEnergyNetworkManager mgr = QuickLinkEnergyNetworkManager.get(sl);
        int networkKey = getNetworkKey(pointSide);
        List<QuickLinkEnergyNetworkManager.GlobalPosRef> plugs = mgr.getPlugsSnapshot(networkKey);
        if (plugs.isEmpty()) return;

        int pIdx = dirIndex(pointSide);
        int start = rrIndexBySide[pIdx];

        for (int i = 0; i < plugs.size(); i++) {
            int idx = (start + i) % plugs.size();
            QuickLinkEnergyNetworkManager.GlobalPosRef ref = plugs.get(idx);
            ServerLevel plugLevel = sl.getServer().getLevel(ref.dimension());
            if (plugLevel == null) continue;

            BlockPos plugPos = ref.pos();
            BlockEntity other = plugLevel.getBlockEntity(plugPos);
            if (!(other instanceof EnergyPlugBlockEntity plugBe)) continue;
            if (!plugBe.enabled) continue;

            for (Direction plugSide : Direction.values()) {
                if (!plugBe.isPlugEnabled(plugSide) || plugBe.getNetworkKey(plugSide) != networkKey) continue;

                IEnergyStorage src = getAttachedEnergyStorage(plugLevel, plugPos, plugSide);
                if (src == null) continue;

                if (moveEnergy(src, dst, amountFE)) {
                    rrIndexBySide[pIdx] = (idx + 1) % plugs.size();
                    setChanged();
                    return;
                }
            }
        }

        rrIndexBySide[pIdx] = (rrIndexBySide[pIdx] + 1) % plugs.size();
        setChanged();
    }

    @Nullable
    private static IEnergyStorage getAttachedEnergyStorage(ServerLevel level, BlockPos selfPos, Direction side) {
        BlockPos target = selfPos.relative(side);
        Direction targetFaceTowardUs = side.getOpposite();
        return level.getCapability(Capabilities.EnergyStorage.BLOCK, target, targetFaceTowardUs);
    }

    private static boolean moveEnergy(IEnergyStorage src, IEnergyStorage dst, int amountFE) {
        if (amountFE <= 0 || !src.canExtract() || !dst.canReceive()) return false;

        int canExtract = src.extractEnergy(amountFE, true);
        if (canExtract <= 0) return false;

        int canReceive = dst.receiveEnergy(canExtract, true);
        if (canReceive <= 0) return false;

        int toMove = Math.min(canExtract, canReceive);
        if (toMove <= 0) return false;

        int extracted = src.extractEnergy(toMove, false);
        if (extracted <= 0) return false;

        int received = dst.receiveEnergy(extracted, false);
        return received > 0;
    }

    private static final class SideEnergyStorage implements IEnergyStorage {
        private final EnergyPlugBlockEntity owner;
        private final Direction side;

        private SideEnergyStorage(EnergyPlugBlockEntity owner, Direction side) {
            this.owner = owner;
            this.side = side;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            return owner.receiveIntoNetwork(side, maxReceive, simulate);
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return owner.extractFromNetwork(side, maxExtract, simulate);
        }

        @Override
        public int getEnergyStored() {
            return 0;
        }

        @Override
        public int getMaxEnergyStored() {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean canExtract() {
            return owner.isPointEnabled(side);
        }

        @Override
        public boolean canReceive() {
            return owner.isPlugEnabled(side);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putIntArray(QuickLinkNbt.SIDE_COLORS, getSideColorsPacked());
        tag.putInt(QuickLinkNbt.COLORS, sideColors[0].pack());
        tag.putBoolean(QuickLinkNbt.ENABLED, enabled);
        tag.putInt("ql_plug_mask", clampMask6(plugMask));
        tag.putInt("ql_point_mask", clampMask6(pointMask));
        tag.putInt("ql_disabled_mask", clampMask6(disabledMask));
        tag.putIntArray("ql_rr_side", rrIndexBySide);
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
                    ? tag.getInt(QuickLinkNbt.COLORS)
                    : QuickLinkColors.unset().pack();
            QuickLinkColors legacy = QuickLinkColors.unpack(packed);
            for (int i = 0; i < 6; i++) sideColors[i] = legacy;
        }
        enabled = !tag.contains(QuickLinkNbt.ENABLED, Tag.TAG_BYTE) || tag.getBoolean(QuickLinkNbt.ENABLED);

        plugMask = clampMask6(tag.getInt("ql_plug_mask"));
        pointMask = clampMask6(tag.getInt("ql_point_mask"));
        disabledMask = clampMask6(tag.getInt("ql_disabled_mask"));

        int[] arr = tag.getIntArray("ql_rr_side");
        for (int i = 0; i < 6; i++) {
            rrIndexBySide[i] = (arr.length > i) ? Math.max(0, arr[i]) : 0;
        }

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
