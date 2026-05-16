package com.maximpolyakov.quicklink.neoforge.blockentity;

import com.maximpolyakov.quicklink.QuickLinkColors;
import com.maximpolyakov.quicklink.QuickLinkNbt;
import com.maximpolyakov.quicklink.neoforge.QuickLinkNeoForge;
import com.maximpolyakov.quicklink.neoforge.UpgradeTier;
import com.maximpolyakov.quicklink.neoforge.config.QuickLinkConfig;
import com.maximpolyakov.quicklink.neoforge.network.QuickLinkEnergyNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class EnergyPlugBlockEntity extends BlockEntity {

    static int period = QuickLinkConfig.ENERGY_TICK_PERIOD.get();

    private int upgradeTier = 0;

    private int plugMask = 0;
    private int pointMask = 0;
    private int disabledMask = 0;
    private final int[] rrIndexBySide = new int[6];

    private final QuickLinkColors[] sideColors = new QuickLinkColors[6];
    private boolean enabled = true;

    private java.util.Set<Integer> lastRegPlugKeys = new java.util.HashSet<>();
    private java.util.Set<Integer> lastRegPointKeys = new java.util.HashSet<>();
    @SuppressWarnings("unchecked")
    private final BlockCapabilityCache<EnergyHandler, Direction>[] neighborCaches = new BlockCapabilityCache[6];
    private final EnergyHandler[] sideCapabilities = new EnergyHandler[6];

    public EnergyPlugBlockEntity(BlockPos pos, BlockState state) {
        super(QuickLinkNeoForge.ENERGY_PLUG_BE.get(), pos, state);
        for (Direction side : Direction.values()) {
            int idx = dirIndex(side);
            sideCapabilities[idx] = new SideEnergyHandler(this, side);
            sideColors[idx] = QuickLinkColors.unset();
        }
    }

    private static int bit(Direction d) { return 1 << d.get3DDataValue(); }
    private static int clampMask6(int m) { return m & 0b111111; }
    private static int dirIndex(Direction d) { return Math.max(0, Math.min(5, d.get3DDataValue())); }

    public int getUpgradeTier() { return upgradeTier; }

    public void setUpgradeTier(int tier) {
        upgradeTier = Math.max(0, Math.min(UpgradeTier.MAX_TIER, tier));
        setChangedAndSync();
    }

    public int effectiveTransferFe() {
        return QuickLinkConfig.ENERGY_TRANSFER_FE.get() * UpgradeTier.multiplier(upgradeTier);
    }

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
        if (plug) return SideRole.PLUG;
        if (point) return SideRole.POINT;
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
        if (next == SideRole.BOTH) { plugMask |= b; pointMask |= b; }
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
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel sl) {
            for (Direction side : Direction.values()) {
                neighborCaches[dirIndex(side)] = BlockCapabilityCache.create(
                    Capabilities.Energy.BLOCK, sl,
                    worldPosition.relative(side), side.getOpposite(),
                    () -> !isRemoved(), () -> {}
                );
            }
            syncRegistration();
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide()) unregisterFromManager();
        super.setRemoved();
    }

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
            if (be.isPlugEnabled(side)) {
                be.tryTransferOnce(sl, side, be.effectiveTransferFe());
            }
        }
    }

    @Nullable
    public EnergyHandler getExternalEnergyStorage(@Nullable Direction side) {
        if (side == null) return null;
        if (!isSideEnabled(side)) return null;
        if (getRole(side) == SideRole.NONE) return null;
        return sideCapabilities[dirIndex(side)];
    }

    int receiveIntoNetwork(Direction inputSide, int amount, TransactionContext ctx) {
        if (amount <= 0 || !isPointEnabled(inputSide) || !(level instanceof ServerLevel sl)) return 0;

        QuickLinkEnergyNetworkManager mgr = QuickLinkEnergyNetworkManager.get(sl);
        int networkKey = getNetworkKey(inputSide);
        List<QuickLinkEnergyNetworkManager.GlobalPosRef> plugs = mgr.getPlugsSnapshot(networkKey);
        if (plugs.isEmpty()) return 0;

        int moved = 0;
        int left = amount;
        int start = rrIndexBySide[dirIndex(inputSide)];

        for (int i = 0; i < plugs.size() && left > 0; i++) {
            int idx = (start + i) % plugs.size();
            QuickLinkEnergyNetworkManager.GlobalPosRef ref = plugs.get(idx);
            ServerLevel plugLevel = sl.getServer().getLevel(ref.dimension());
            if (plugLevel == null) continue;

            BlockEntity other = plugLevel.getBlockEntity(ref.pos());
            if (!(other instanceof EnergyPlugBlockEntity plugBe) || !plugBe.enabled) continue;

            for (Direction plugSide : Direction.values()) {
                if (!plugBe.isPlugEnabled(plugSide) || plugBe.getNetworkKey(plugSide) != networkKey) continue;
                EnergyHandler dst = plugBe.getAttachedNeighborHandler(plugSide);
                if (dst == null) continue;

                int accepted = dst.insert(left, ctx);
                if (accepted <= 0) continue;

                moved += accepted;
                left -= accepted;

                rrIndexBySide[dirIndex(inputSide)] = (idx + 1) % plugs.size();
                setChanged();

                if (left <= 0) break;
            }
        }

        return moved;
    }

    int extractFromNetwork(Direction outputSide, int amount, TransactionContext ctx) {
        if (amount <= 0 || !isPlugEnabled(outputSide) || !(level instanceof ServerLevel sl)) return 0;

        QuickLinkEnergyNetworkManager mgr = QuickLinkEnergyNetworkManager.get(sl);
        int networkKey = getNetworkKey(outputSide);
        List<QuickLinkEnergyNetworkManager.GlobalPosRef> points = mgr.getPointsSnapshot(networkKey);
        if (points.isEmpty()) return 0;

        int moved = 0;
        int left = amount;
        int start = rrIndexBySide[dirIndex(outputSide)];

        for (int i = 0; i < points.size() && left > 0; i++) {
            int idx = (start + i) % points.size();
            QuickLinkEnergyNetworkManager.GlobalPosRef ref = points.get(idx);
            ServerLevel pointLevel = sl.getServer().getLevel(ref.dimension());
            if (pointLevel == null) continue;

            BlockEntity other = pointLevel.getBlockEntity(ref.pos());
            if (!(other instanceof EnergyPlugBlockEntity pointBe) || !pointBe.enabled) continue;

            for (Direction pointSide : Direction.values()) {
                if (!pointBe.isPointEnabled(pointSide) || pointBe.getNetworkKey(pointSide) != networkKey) continue;

                EnergyHandler src = pointBe.getAttachedNeighborHandler(pointSide);
                if (src == null) continue;

                int extracted = src.extract(left, ctx);
                if (extracted <= 0) continue;

                moved += extracted;
                left -= extracted;

                rrIndexBySide[dirIndex(outputSide)] = (idx + 1) % points.size();
                setChanged();

                if (left <= 0) break;
            }
        }

        return moved;
    }

    private void tryTransferOnce(ServerLevel sl, Direction plugSide, int amountFE) {
        EnergyHandler dst = getAttachedNeighborHandler(plugSide);
        if (dst == null) return;

        QuickLinkEnergyNetworkManager mgr = QuickLinkEnergyNetworkManager.get(sl);
        int networkKey = getNetworkKey(plugSide);

        record Src(EnergyPlugBlockEntity be, Direction dir) {}
        List<Src> sources = new ArrayList<>();
        for (QuickLinkEnergyNetworkManager.GlobalPosRef ref : mgr.getPointsSnapshot(networkKey)) {
            ServerLevel pl = sl.getServer().getLevel(ref.dimension());
            if (pl == null) continue;
            BlockEntity be = pl.getBlockEntity(ref.pos());
            if (!(be instanceof EnergyPlugBlockEntity pBe) || !pBe.enabled) continue;
            for (Direction d : Direction.values()) {
                if (!pBe.isPointEnabled(d) || pBe.getNetworkKey(d) != networkKey) continue;
                sources.add(new Src(pBe, d));
            }
        }
        if (sources.isEmpty()) return;

        int pIdx = dirIndex(plugSide);
        int start = rrIndexBySide[pIdx] % sources.size();

        for (int i = 0; i < sources.size(); i++) {
            int idx = (start + i) % sources.size();
            Src s = sources.get(idx);
            EnergyHandler src = s.be().getAttachedNeighborHandler(s.dir());
            if (src == null) continue;

            if (moveEnergy(src, dst, amountFE)) {
                rrIndexBySide[pIdx] = (idx + 1) % sources.size();
                setChanged();
                return;
            }
        }

        rrIndexBySide[pIdx] = (rrIndexBySide[pIdx] + 1) % sources.size();
        setChanged();
    }

    @Nullable
    private EnergyHandler getAttachedNeighborHandler(Direction side) {
        BlockCapabilityCache<EnergyHandler, Direction> cache = neighborCaches[dirIndex(side)];
        return cache != null
            ? cache.getCapability()
            : level.getCapability(Capabilities.Energy.BLOCK, worldPosition.relative(side), side.getOpposite());
    }

    private static boolean moveEnergy(EnergyHandler src, EnergyHandler dst, int amountFE) {
        if (amountFE <= 0) return false;
        try (var tx = Transaction.openRoot()) {
            int canReceive = dst.insert(amountFE, tx);
            if (canReceive <= 0) return false;
            int extracted = src.extract(canReceive, tx);
            if (extracted <= 0) return false;
            tx.commit();
            return true;
        }
    }

    private static final class SideEnergyHandler implements EnergyHandler {
        private final EnergyPlugBlockEntity owner;
        private final Direction side;

        private SideEnergyHandler(EnergyPlugBlockEntity owner, Direction side) {
            this.owner = owner;
            this.side = side;
        }

        @Override
        public int insert(int maxAmount, TransactionContext ctx) {
            if (maxAmount <= 0 || !owner.isPointEnabled(side)) return 0;
            return owner.receiveIntoNetwork(side, maxAmount, ctx);
        }

        @Override
        public int extract(int maxAmount, TransactionContext ctx) {
            if (maxAmount <= 0 || !owner.isPlugEnabled(side)) return 0;
            return owner.extractFromNetwork(side, maxAmount, ctx);
        }

        @Override
        public long getAmountAsLong() {
            return 0L;
        }

        @Override
        public long getCapacityAsLong() {
            return Integer.MAX_VALUE;
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putIntArray(QuickLinkNbt.SIDE_COLORS, getSideColorsPacked());
        output.putInt(QuickLinkNbt.COLORS, sideColors[0].pack());
        output.putBoolean(QuickLinkNbt.ENABLED, enabled);
        output.putInt("ql_schema", 1);
        output.putInt("ql_plug_mask", clampMask6(plugMask));
        output.putInt("ql_point_mask", clampMask6(pointMask));
        output.putInt("ql_disabled_mask", clampMask6(disabledMask));
        output.putIntArray("ql_rr_side", rrIndexBySide);
        output.putInt(QuickLinkNbt.UPGRADE_TIER, upgradeTier);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);

        int[] sideColorsPacked = input.getIntArray(QuickLinkNbt.SIDE_COLORS).orElse(null);
        if (sideColorsPacked != null) {
            for (int i = 0; i < 6; i++) {
                int v = (sideColorsPacked.length > i) ? sideColorsPacked[i] : QuickLinkColors.unset().pack();
                sideColors[i] = QuickLinkColors.unpack(v);
            }
        } else {
            QuickLinkColors legacy = QuickLinkColors.unpack(input.getIntOr(QuickLinkNbt.COLORS, QuickLinkColors.unset().pack()));
            for (int i = 0; i < 6; i++) sideColors[i] = legacy;
        }

        enabled = input.getBooleanOr(QuickLinkNbt.ENABLED, true);

        plugMask = clampMask6(input.getIntOr("ql_plug_mask", 0));
        pointMask = clampMask6(input.getIntOr("ql_point_mask", 0));
        if (input.getIntOr("ql_schema", -1) < 0) {
            int tmp = plugMask; plugMask = pointMask; pointMask = tmp;
        }
        disabledMask = clampMask6(input.getIntOr("ql_disabled_mask", 0));

        int[] arr = input.getIntArray("ql_rr_side").orElse(new int[0]);
        for (int i = 0; i < 6; i++) {
            rrIndexBySide[i] = (arr.length > i) ? Math.max(0, arr[i]) : 0;
        }

        upgradeTier = Math.max(0, Math.min(UpgradeTier.MAX_TIER, input.getIntOr(QuickLinkNbt.UPGRADE_TIER, 0)));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveCustomOnly(registries);
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
