package com.maximpolyakov.quicklink.neoforge.blockentity;

import com.maximpolyakov.quicklink.neoforge.config.QuickLinkConfig;
import com.maximpolyakov.quicklink.neoforge.UpgradeTier;
import com.maximpolyakov.quicklink.QuickLinkColors;
import com.maximpolyakov.quicklink.QuickLinkNbt;
import com.maximpolyakov.quicklink.neoforge.QuickLinkNeoForge;
import com.maximpolyakov.quicklink.neoforge.compat.ftbchunks.FTBChunksCompat;
import com.maximpolyakov.quicklink.neoforge.compat.ftbteams.FTBTeamsCompat;
import com.maximpolyakov.quicklink.neoforge.network.QuickLinkFluidNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

public class FluidPlugBlockEntity extends BlockEntity {

    // ==== transfer tuning ====
    static int period = QuickLinkConfig.FLUID_TICK_PERIOD.get();
    private static final boolean DBG_TRANSFER = false;
    // =========================

    // ---- upgrade ----
    private int upgradeTier = 0;

    // ---- per-side roles ----
    // masks by Direction.get3DDataValue():
    // DOWN=0, UP=1, NORTH=2, SOUTH=3, WEST=4, EAST=5
    private int plugMask = 0;
    private int pointMask = 0;
    private int disabledMask = 0;
    private int infiniteWaterMask = 0;

    // round-robin index per POINT side
    private final int[] rrIndexBySide = new int[6];
    private final long[] waterAccumBySide = new long[6];

    // network key
    private final QuickLinkColors[] sideColors = new QuickLinkColors[6];

    // master enable
    private boolean enabled = true;
    private UUID ownerUUID = null;

    // cached registration state
    private java.util.Set<Integer> lastRegPlugKeys = new java.util.HashSet<>();
    private java.util.Set<Integer> lastRegPointKeys = new java.util.HashSet<>();
    @SuppressWarnings("unchecked")
    private final BlockCapabilityCache<IFluidHandler, Direction>[] neighborCaches = new BlockCapabilityCache[6];
    private final IFluidHandler[] sideCapabilities = new IFluidHandler[6];

    public FluidPlugBlockEntity(BlockPos pos, BlockState state) {
        super(QuickLinkNeoForge.FLUID_PLUG_BE.get(), pos, state);
        for (Direction side : Direction.values()) {
            sideCapabilities[dirIndex(side)] = new SideFluidHandler(this, side);
            sideColors[dirIndex(side)] = QuickLinkColors.unset();
        }
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

    // ---------------- upgrade tier ----------------

    public int getUpgradeTier() { return upgradeTier; }

    public void setUpgradeTier(int tier) {
        upgradeTier = Math.max(0, Math.min(UpgradeTier.MAX_TIER, tier));
        setChangedAndSync();
    }

    public int effectiveAmountMb() {
        return QuickLinkConfig.FLUID_TRANSFER_MB.get() * UpgradeTier.multiplier(upgradeTier);
    }

    private int lastSentMb = 0;
    int pendingReceivedMb = 0;
    private int lastReceivedMb = 0;

    public int getLastSentMb() { return lastSentMb; }
    public int getLastReceivedMb() { return lastReceivedMb; }
    public int getTickPeriod() { return period; }

    public long effectiveInfiniteMbPerTick() {
        return (long) QuickLinkConfig.FLUID_INFINITE_MB_PER_TICK.get() * UpgradeTier.multiplier(upgradeTier);
    }

    public int effectiveInfiniteMaxPush() {
        return QuickLinkConfig.FLUID_INFINITE_MAX_PUSH_PER_TICK.get() * UpgradeTier.multiplier(upgradeTier);
    }

    // ---------------- public API (block/use) ----------------

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

    public int getNetworkKey(Direction side) {
        int colorKey = sideColors[dirIndex(side)].networkKey();
        int claimHash = QuickLinkNeoForge.FTBCHUNKS_LOADED ? FTBChunksCompat.claimTeamComponent(level, worldPosition) : FTBChunksCompat.NOT_CLAIMED;
        // See EnergyPlugBlockEntity.getNetworkKey: the claim is what makes a plug survive its
        // owner leaving the team, and a claim key equals the same team's membership key.
        int teamKey = (claimHash != FTBChunksCompat.NOT_CLAIMED)
                ? claimHash
                : (QuickLinkNeoForge.FTBTEAMS_LOADED ? FTBTeamsCompat.teamComponent(ownerUUID) : 0);
        return colorKey | (teamKey << 16);
    }

    public UUID getOwnerUUID() { return ownerUUID; }
    public void setOwnerUUID(UUID uuid) {
        ownerUUID = uuid;
        setChangedAndSync(); syncRegistration();
    }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        setChangedAndSync();
    }

    public void setColor(Direction side, int slot, byte colorId) {
        int idx = dirIndex(side);
        int oldKey = sideColors[idx].networkKey();
        sideColors[idx] = sideColors[idx].with(slot, colorId);
        setChangedAndSync();

        if (oldKey != sideColors[idx].networkKey()) {
            syncRegistration();
        }
    }

    public enum SideRole { NONE, PLUG, POINT, BOTH }

    public SideRole getRole(Direction side) {
        int b = bit(side);
        boolean p = (plugMask & b) != 0;
        boolean t = (pointMask & b) != 0;

        if (p && t) return SideRole.BOTH;
        if (p) return SideRole.PLUG;
        if (t) return SideRole.POINT;
        return SideRole.NONE;
    }

    public boolean isSideEnabled(Direction side) {
        int b = bit(side);
        return (disabledMask & b) == 0;
    }

    public boolean isPlugEnabled(Direction side) {
        SideRole role = getRole(side);
        return (role == SideRole.PLUG || role == SideRole.BOTH) && isSideEnabled(side);
    }

    public boolean isPointEnabled(Direction side) {
        SideRole role = getRole(side);
        return (role == SideRole.POINT || role == SideRole.BOTH) && isSideEnabled(side);
    }

    /**
     * NONE -> PLUG -> POINT -> BOTH -> NONE
     */
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

        if (next == SideRole.PLUG) {
            plugMask |= b;
        } else if (next == SideRole.POINT) {
            pointMask |= b;
        } else if (next == SideRole.BOTH) {
            plugMask |= b;
            pointMask |= b;
        } else {
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
     * Shift-RMB: toggle side ON/OFF (only if role != NONE)
     */
    public boolean toggleSideEnabled(Direction side) {
        if (getRole(side) == SideRole.NONE) return false;

        int b = bit(side);
        disabledMask ^= b;
        disabledMask = clampMask6(disabledMask);

        setChangedAndSync();
        syncRegistration();
        return true;
    }

    public int getPlugMask() { return plugMask; }
    public int getPointMask() { return pointMask; }
    public int getDisabledMask() { return disabledMask; }

    public boolean isInfiniteWater(Direction side) {
        return (infiniteWaterMask & bit(side)) != 0;
    }

    public boolean toggleInfiniteWater(Direction side) {
        SideRole role = getRole(side);
        if (role != SideRole.POINT && role != SideRole.BOTH) return false;

        int idx = dirIndex(side);
        int b = bit(side);
        infiniteWaterMask ^= b;
        if ((infiniteWaterMask & b) == 0) {
            waterAccumBySide[idx] = 0L;
        }

        infiniteWaterMask = clampMask6(infiniteWaterMask);
        setChangedAndSync();
        return true;
    }

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
        if (level instanceof ServerLevel sl) {
            for (Direction side : Direction.values()) {
                neighborCaches[dirIndex(side)] = BlockCapabilityCache.create(
                    Capabilities.FluidHandler.BLOCK, sl,
                    worldPosition.relative(side), side.getOpposite(),
                    () -> !isRemoved(), () -> {}
                );
            }
            syncRegistration();
        }
    }

    // See EnergyPlugBlockEntity: setRemoved() also fires on chunk unload, and unregistering there
    // would drop the plug from the saved network for good.
    private boolean unloading = false;

    @Override
    public void onChunkUnloaded() {
        unloading = true;
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        if (!unloading && level != null && !level.isClientSide) {
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
        QuickLinkFluidNetworkManager mgr = QuickLinkFluidNetworkManager.get(sl);

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

        QuickLinkFluidNetworkManager mgr = QuickLinkFluidNetworkManager.get(sl);
        for (int key : plugKeys) mgr.registerPlug(sl, key, worldPosition);
        for (int key : pointKeys) mgr.registerPoint(sl, key, worldPosition);

        lastRegPlugKeys = plugKeys;
        lastRegPointKeys = pointKeys;
    }

    // client sync
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

    public static void serverTick(Level level, BlockPos pos, BlockState state, FluidPlugBlockEntity be) {
        if (!(level instanceof ServerLevel sl)) return;
        if (!be.enabled) return;

        long gt = sl.getGameTime();
        //if ((gt % TICK_PERIOD) != 0L) return;
        if ((gt % period) != 0L) return;

        be.lastReceivedMb = be.pendingReceivedMb;
        be.pendingReceivedMb = 0;

        // try for each enabled PLUG side
        int total = 0;
        for (Direction plugSide : Direction.values()) {
            if (be.isPlugEnabled(plugSide)) {
                total += be.tryTransferOnce(sl, plugSide, be.effectiveAmountMb());
            }
        }
        be.lastSentMb = total;
    }

    @Nullable
    public IFluidHandler getExternalFluidHandler(@Nullable Direction side) {
        if (side == null) return null;
        if (!isSideEnabled(side)) return null;
        if (getRole(side) == SideRole.NONE) return null;
        return sideCapabilities[dirIndex(side)];
    }

    private int fillIntoNetwork(Direction inputSide, FluidStack resource, IFluidHandler.FluidAction action) {
        if (resource.isEmpty() || !isPointEnabled(inputSide) || !(level instanceof ServerLevel sl)) return 0;

        QuickLinkFluidNetworkManager mgr = QuickLinkFluidNetworkManager.get(sl);
        int networkKey = getNetworkKey(inputSide);
        List<QuickLinkFluidNetworkManager.GlobalPosRef> points = mgr.getPointsSnapshot(networkKey);
        if (points.isEmpty()) return 0;

        int moved = 0;
        int left = resource.getAmount();
        int start = rrIndexBySide[dirIndex(inputSide)];

        for (int i = 0; i < points.size() && left > 0; i++) {
            int idx = (start + i) % points.size();
            QuickLinkFluidNetworkManager.GlobalPosRef ref = points.get(idx);
            ServerLevel pointLevel = sl.getServer().getLevel(ref.dimension());
            if (pointLevel == null) continue;

            BlockEntity other = pointLevel.getBlockEntity(ref.pos());
            if (!(other instanceof FluidPlugBlockEntity pointBe) || !pointBe.enabled) continue;

            for (Direction pointSide : Direction.values()) {
                if (!pointBe.isPlugEnabled(pointSide) || pointBe.getNetworkKey(pointSide) != networkKey) continue;
                IFluidHandler dst = pointBe.getCachedNeighborFluidHandler(pointSide);
                if (dst == null) continue;

                FluidStack toFill = resource.copy();
                toFill.setAmount(left);
                int accepted = dst.fill(toFill, action);
                if (accepted <= 0) continue;

                moved += accepted;
                left -= accepted;

                if (action.execute()) {
                    rrIndexBySide[dirIndex(inputSide)] = (idx + 1) % points.size();
                    setChanged();
                }

                if (left <= 0) break;
            }
        }

        return moved;
    }

    private FluidStack drainFromNetwork(Direction outputSide, int amount, @Nullable FluidStack match, IFluidHandler.FluidAction action) {
        if (amount <= 0 || !isPlugEnabled(outputSide) || !(level instanceof ServerLevel sl)) return FluidStack.EMPTY;

        QuickLinkFluidNetworkManager mgr = QuickLinkFluidNetworkManager.get(sl);
        int networkKey = getNetworkKey(outputSide);
        List<QuickLinkFluidNetworkManager.GlobalPosRef> plugs = mgr.getPlugsSnapshot(networkKey);
        if (plugs.isEmpty()) return FluidStack.EMPTY;

        int start = rrIndexBySide[dirIndex(outputSide)];

        for (int i = 0; i < plugs.size(); i++) {
            int idx = (start + i) % plugs.size();
            QuickLinkFluidNetworkManager.GlobalPosRef ref = plugs.get(idx);
            ServerLevel plugLevel = sl.getServer().getLevel(ref.dimension());
            if (plugLevel == null) continue;

            BlockEntity other = plugLevel.getBlockEntity(ref.pos());
            if (!(other instanceof FluidPlugBlockEntity plugBe) || !plugBe.enabled) continue;

            for (Direction plugSide : Direction.values()) {
                if (!plugBe.isPointEnabled(plugSide) || plugBe.getNetworkKey(plugSide) != networkKey) continue;

                if (plugBe.isInfiniteWater(plugSide)) {
                    if (match != null && !match.isEmpty() && !match.is(Fluids.WATER)) continue;

                    FluidStack provided = new FluidStack(Fluids.WATER, amount);
                    if (action.execute()) {
                        rrIndexBySide[dirIndex(outputSide)] = (idx + 1) % plugs.size();
                        setChanged();
                    }
                    return provided;
                }

                IFluidHandler src = plugBe.getCachedNeighborFluidHandler(plugSide);
                if (src == null) continue;

                FluidStack drained = (match == null)
                        ? src.drain(amount, action)
                        : src.drain(match.copyWithAmount(amount), action);
                if (drained.isEmpty()) continue;

                if (action.execute()) {
                    rrIndexBySide[dirIndex(outputSide)] = (idx + 1) % plugs.size();
                    setChanged();
                }
                return drained;
            }
        }

        return FluidStack.EMPTY;
    }

    /**
     * For one POINT side: pull up to amountMB from any PLUG side of any plug-block in same network
     * into destination handler attached to this pointSide.
     */
    private int tryTransferOnce(ServerLevel sl, Direction plugSide, int amountMB) {
        int networkKey = getNetworkKey(plugSide);

        IFluidHandler dst = getCachedNeighborFluidHandler(plugSide);
        if (dst == null) return 0;

        QuickLinkFluidNetworkManager mgr = QuickLinkFluidNetworkManager.get(sl);

        List<QuickLinkFluidNetworkManager.GlobalPosRef> points = mgr.getPointsSnapshot(networkKey);
        if (points.isEmpty()) return 0;

        int pIdx = dirIndex(plugSide);
        int start = rrIndexBySide[pIdx] % points.size();

        // Resolve points lazily in round-robin order and stop at the first one that moves fluid.
        // getBlockEntity() force-loads the target chunk, so collecting every source up front would
        // load one chunk per network member on every attempt, across every dimension involved.
        for (int i = 0; i < points.size(); i++) {
            int idx = (start + i) % points.size();
            QuickLinkFluidNetworkManager.GlobalPosRef ref = points.get(idx);
            ServerLevel pl = sl.getServer().getLevel(ref.dimension());
            if (pl == null) continue;
            BlockEntity be = pl.getBlockEntity(ref.pos());
            if (!(be instanceof FluidPlugBlockEntity pBe) || !pBe.enabled) continue;
            for (Direction d : Direction.values()) {
                if (!pBe.isPointEnabled(d) || pBe.getNetworkKey(d) != networkKey) continue;
                int moved;
                if (pBe.isInfiniteWater(d)) {
                    moved = pushInfiniteWater(dst, pBe, d);
                } else {
                    IFluidHandler src = pBe.getCachedNeighborFluidHandler(d);
                    if (src == null) continue;
                    moved = moveFluidAny(src, dst, amountMB);
                }
                if (moved > 0) {
                    rrIndexBySide[pIdx] = (idx + 1) % points.size();
                    setChanged();
                    pBe.pendingReceivedMb += moved;
                    return moved;
                }
            }
        }

        rrIndexBySide[pIdx] = (rrIndexBySide[pIdx] + 1) % points.size();
        setChanged();
        return 0;
    }

    private static int pushInfiniteWater(@Nullable IFluidHandler dst, FluidPlugBlockEntity plugBe, Direction pointSide) {
        int idx = dirIndex(pointSide);

        long rateMb = plugBe.effectiveInfiniteMbPerTick();
        int maxChunk = plugBe.effectiveInfiniteMaxPush();

        plugBe.waterAccumBySide[idx] += rateMb;

        if (dst == null) return 0;
        FluidStack probe = new FluidStack(Fluids.WATER, 1);
        if (dst.fill(probe, IFluidHandler.FluidAction.SIMULATE) <= 0) return 0;

        int totalMoved = 0;
        for (int i = 0; i < 8; i++) {
            int toMove = (int) Math.min(plugBe.waterAccumBySide[idx], maxChunk);
            if (toMove <= 0) break;

            FluidStack water = new FluidStack(Fluids.WATER, toMove);
            int filled = dst.fill(water, IFluidHandler.FluidAction.EXECUTE);
            if (filled <= 0) break;

            plugBe.waterAccumBySide[idx] -= filled;
            totalMoved += filled;
        }

        if (totalMoved > 0) {
            plugBe.setChanged();
        }

        return totalMoved;
    }

    @Nullable
    private IFluidHandler getCachedNeighborFluidHandler(Direction side) {
        BlockPos target = worldPosition.relative(side);
        Direction targetFace = side.getOpposite();
        BlockEntity be = level.getBlockEntity(target);
        if (be instanceof FluidPlugBlockEntity plug) {
            return plug.getExternalFluidHandler(targetFace);
        }
        BlockCapabilityCache<IFluidHandler, Direction> cache = neighborCaches[dirIndex(side)];
        return cache != null
            ? cache.getCapability()
            : level.getCapability(Capabilities.FluidHandler.BLOCK, target, targetFace);
    }

    private static int moveFluidAny(IFluidHandler src, @Nullable IFluidHandler dst, int amountMB) {
        if (amountMB <= 0 || dst == null) return 0;

        FluidStack canDrain = src.drain(amountMB, IFluidHandler.FluidAction.SIMULATE);
        if (DBG_TRANSFER) System.out.println("[QLF][DBG] drainSim=" + (canDrain.isEmpty() ? "EMPTY" : (canDrain.getAmount() + " " + canDrain.getFluid())));
        if (canDrain.isEmpty() || canDrain.getAmount() <= 0) return 0;

        if (DBG_TRANSFER) System.out.println("[QLF][DBG] dst class=" + dst.getClass().getName()
                + " tank0=" + dst.getFluidInTank(0).getAmount() + " " + (dst.getFluidInTank(0).isEmpty() ? "empty" : dst.getFluidInTank(0).getFluid())
                + " cap=" + dst.getTankCapacity(0));
        int canFill = dst.fill(canDrain, IFluidHandler.FluidAction.SIMULATE);
        if (DBG_TRANSFER) System.out.println("[QLF][DBG] fillSim=" + canFill);
        if (canFill <= 0) return 0;

        int toMove = Math.min(canDrain.getAmount(), canFill);
        if (toMove <= 0) return 0;

        FluidStack drained = src.drain(toMove, IFluidHandler.FluidAction.EXECUTE);
        if (drained.isEmpty() || drained.getAmount() <= 0) return 0;

        int filled = dst.fill(drained, IFluidHandler.FluidAction.EXECUTE);
        if (DBG_TRANSFER) System.out.println("[QLF][DBG] drained=" + drained.getAmount() + " filled=" + filled);
        return filled;
    }

    private static final class SideFluidHandler implements IFluidHandler {
        private final FluidPlugBlockEntity owner;
        private final Direction side;

        private SideFluidHandler(FluidPlugBlockEntity owner, Direction side) {
            this.owner = owner;
            this.side = side;
        }

        @Override
        public int getTanks() { return 1; }

        @Override
        public FluidStack getFluidInTank(int tank) {
            if (tank != 0) return FluidStack.EMPTY;
            return owner.peekNetworkFluid(side);
        }

        @Override
        public int getTankCapacity(int tank) { return Integer.MAX_VALUE; }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return owner.isPointEnabled(side);
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            return owner.fillIntoNetwork(side, resource, action);
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            if (resource.isEmpty()) return FluidStack.EMPTY;
            return owner.drainFromNetwork(side, resource.getAmount(), resource, action);
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            return owner.drainFromNetwork(side, maxDrain, null, action);
        }
    }

    private FluidStack peekNetworkFluid(Direction outputSide) {
        if (!isPlugEnabled(outputSide) || !(level instanceof ServerLevel sl)) return FluidStack.EMPTY;

        QuickLinkFluidNetworkManager mgr = QuickLinkFluidNetworkManager.get(sl);
        int networkKey = getNetworkKey(outputSide);
        List<QuickLinkFluidNetworkManager.GlobalPosRef> plugs = mgr.getPlugsSnapshot(networkKey);
        if (plugs.isEmpty()) return FluidStack.EMPTY;

        for (QuickLinkFluidNetworkManager.GlobalPosRef ref : plugs) {
            ServerLevel plugLevel = sl.getServer().getLevel(ref.dimension());
            if (plugLevel == null) continue;

            BlockEntity other = plugLevel.getBlockEntity(ref.pos());
            if (!(other instanceof FluidPlugBlockEntity plugBe) || !plugBe.enabled) continue;

            for (Direction plugSide : Direction.values()) {
                if (!plugBe.isPointEnabled(plugSide) || plugBe.getNetworkKey(plugSide) != networkKey) continue;

                if (plugBe.isInfiniteWater(plugSide)) {
                    return new FluidStack(Fluids.WATER, 1);
                }

                IFluidHandler src = plugBe.getCachedNeighborFluidHandler(plugSide);
                if (src == null) continue;

                FluidStack simulated = src.drain(1, IFluidHandler.FluidAction.SIMULATE);
                if (!simulated.isEmpty()) return simulated;
            }
        }

        return FluidStack.EMPTY;
    }

    // ---------------- NBT ----------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        tag.putIntArray(QuickLinkNbt.SIDE_COLORS, getSideColorsPacked());
        tag.putInt(QuickLinkNbt.COLORS, sideColors[0].pack());
        tag.putBoolean(QuickLinkNbt.ENABLED, enabled);

        tag.putInt("ql_plug_mask", clampMask6(plugMask));
        tag.putInt("ql_point_mask", clampMask6(pointMask));
        tag.putInt("ql_disabled_mask", clampMask6(disabledMask));
        tag.putInt("ql_inf_water_mask", clampMask6(infiniteWaterMask));

        tag.putIntArray("ql_rr_side", rrIndexBySide);
        tag.putLongArray("ql_inf_water_accum", waterAccumBySide);
        tag.putInt(QuickLinkNbt.UPGRADE_TIER, upgradeTier);
        tag.putIntArray(QuickLinkNbt.REG_PLUG_KEYS, QuickLinkNbt.packKeys(lastRegPlugKeys));
        tag.putIntArray(QuickLinkNbt.REG_POINT_KEYS, QuickLinkNbt.packKeys(lastRegPointKeys));
        if (ownerUUID != null) tag.putUUID(QuickLinkNbt.OWNER_UUID, ownerUUID);
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

        plugMask = clampMask6(tag.contains("ql_plug_mask", Tag.TAG_INT) ? tag.getInt("ql_plug_mask") : 0);
        pointMask = clampMask6(tag.contains("ql_point_mask", Tag.TAG_INT) ? tag.getInt("ql_point_mask") : 0);
        disabledMask = clampMask6(tag.contains("ql_disabled_mask", Tag.TAG_INT) ? tag.getInt("ql_disabled_mask") : 0);
        infiniteWaterMask = clampMask6(tag.contains("ql_inf_water_mask", Tag.TAG_INT) ? tag.getInt("ql_inf_water_mask") : 0);

        if (tag.contains("ql_rr_side", Tag.TAG_INT_ARRAY)) {
            int[] arr = tag.getIntArray("ql_rr_side");
            for (int i = 0; i < 6; i++) {
                rrIndexBySide[i] = (arr != null && arr.length > i) ? Math.max(0, arr[i]) : 0;
            }
        } else {
            for (int i = 0; i < 6; i++) rrIndexBySide[i] = 0;
        }

        if (tag.contains("ql_inf_water_accum", Tag.TAG_LONG_ARRAY)) {
            long[] arr = tag.getLongArray("ql_inf_water_accum");
            for (int i = 0; i < 6; i++) {
                waterAccumBySide[i] = (arr != null && arr.length > i) ? Math.max(0L, arr[i]) : 0L;
            }
        } else {
            for (int i = 0; i < 6; i++) waterAccumBySide[i] = 0L;
        }

        // keep infinite-water only on POINT sides
        infiniteWaterMask &= pointMask;

        upgradeTier = Math.max(0, Math.min(UpgradeTier.MAX_TIER,
                tag.contains(QuickLinkNbt.UPGRADE_TIER, Tag.TAG_INT) ? tag.getInt(QuickLinkNbt.UPGRADE_TIER) : 0));
        lastRegPlugKeys = QuickLinkNbt.unpackKeys(tag.getIntArray(QuickLinkNbt.REG_PLUG_KEYS));
        lastRegPointKeys = QuickLinkNbt.unpackKeys(tag.getIntArray(QuickLinkNbt.REG_POINT_KEYS));
        ownerUUID = tag.hasUUID(QuickLinkNbt.OWNER_UUID) ? tag.getUUID(QuickLinkNbt.OWNER_UUID) : null;
    }
}
