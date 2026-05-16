package com.maximpolyakov.quicklink.neoforge.blockentity;

import com.maximpolyakov.quicklink.neoforge.config.QuickLinkConfig;
import com.maximpolyakov.quicklink.neoforge.UpgradeTier;
import com.maximpolyakov.quicklink.QuickLinkColors;
import com.maximpolyakov.quicklink.QuickLinkNbt;
import com.maximpolyakov.quicklink.neoforge.QuickLinkNeoForge;
import com.maximpolyakov.quicklink.neoforge.network.QuickLinkFluidNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class FluidPlugBlockEntity extends BlockEntity {

    static int period = QuickLinkConfig.FLUID_TICK_PERIOD.get();
    private static final boolean DBG_TRANSFER = false;

    private int upgradeTier = 0;

    private int plugMask = 0;
    private int pointMask = 0;
    private int disabledMask = 0;
    private int infiniteWaterMask = 0;

    private final int[] rrIndexBySide = new int[6];
    private final long[] waterAccumBySide = new long[6];

    private final QuickLinkColors[] sideColors = new QuickLinkColors[6];
    private boolean enabled = true;

    private java.util.Set<Integer> lastRegPlugKeys = new java.util.HashSet<>();
    private java.util.Set<Integer> lastRegPointKeys = new java.util.HashSet<>();
    @SuppressWarnings("unchecked")
    private final BlockCapabilityCache<ResourceHandler<FluidResource>, Direction>[] neighborCaches =
        new BlockCapabilityCache[6];
    private final ResourceHandler<FluidResource>[] sideCapabilities;

    @SuppressWarnings("unchecked")
    public FluidPlugBlockEntity(BlockPos pos, BlockState state) {
        super(QuickLinkNeoForge.FLUID_PLUG_BE.get(), pos, state);
        sideCapabilities = new ResourceHandler[6];
        for (Direction side : Direction.values()) {
            sideCapabilities[dirIndex(side)] = new SideFluidHandler(this, side);
            sideColors[dirIndex(side)] = QuickLinkColors.unset();
        }
    }

    private static int bit(Direction d) {
        int idx = d.get3DDataValue();
        if (idx < 0) idx = 0;
        if (idx > 5) idx = 5;
        return 1 << idx;
    }

    private static int clampMask6(int m) { return m & 0b111111; }

    private static int dirIndex(Direction d) {
        int idx = d.get3DDataValue();
        if (idx < 0) idx = 0;
        if (idx > 5) idx = 5;
        return idx;
    }

    public int getUpgradeTier() { return upgradeTier; }

    public void setUpgradeTier(int tier) {
        upgradeTier = Math.max(0, Math.min(UpgradeTier.MAX_TIER, tier));
        setChangedAndSync();
    }

    public int effectiveAmountMb() {
        return QuickLinkConfig.FLUID_TRANSFER_MB.get() * UpgradeTier.multiplier(upgradeTier);
    }

    public long effectiveInfiniteMbPerTick() {
        return (long) QuickLinkConfig.FLUID_INFINITE_MB_PER_TICK.get() * UpgradeTier.multiplier(upgradeTier);
    }

    public int effectiveInfiniteMaxPush() {
        return QuickLinkConfig.FLUID_INFINITE_MAX_PUSH_PER_TICK.get() * UpgradeTier.multiplier(upgradeTier);
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

    public int getNetworkKey(Direction side) { return sideColors[dirIndex(side)].networkKey(); }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; setChangedAndSync(); }

    public void setColor(Direction side, int slot, byte colorId) {
        int idx = dirIndex(side);
        int oldKey = sideColors[idx].networkKey();
        sideColors[idx] = sideColors[idx].with(slot, colorId);
        setChangedAndSync();
        if (oldKey != sideColors[idx].networkKey()) syncRegistration();
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
        else if (next == SideRole.POINT) pointMask |= b;
        else if (next == SideRole.BOTH) { plugMask |= b; pointMask |= b; }
        else disabledMask &= ~b;
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

    public int getPlugMask() { return plugMask; }
    public int getPointMask() { return pointMask; }
    public int getDisabledMask() { return disabledMask; }

    public boolean isInfiniteWater(Direction side) { return (infiniteWaterMask & bit(side)) != 0; }

    public boolean toggleInfiniteWater(Direction side) {
        SideRole role = getRole(side);
        if (role != SideRole.POINT && role != SideRole.BOTH) return false;
        int idx = dirIndex(side);
        int b = bit(side);
        infiniteWaterMask ^= b;
        if ((infiniteWaterMask & b) == 0) waterAccumBySide[idx] = 0L;
        infiniteWaterMask = clampMask6(infiniteWaterMask);
        setChangedAndSync();
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
                    Capabilities.Fluid.BLOCK, sl,
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

    @Override
    public CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
        return this.saveCustomOnly(registries);
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, FluidPlugBlockEntity be) {
        if (!(level instanceof ServerLevel sl)) return;
        if (!be.enabled) return;
        long gt = sl.getGameTime();
        if ((gt % period) != 0L) return;
        for (Direction plugSide : Direction.values()) {
            if (be.isPlugEnabled(plugSide)) be.tryTransferOnce(sl, plugSide, be.effectiveAmountMb());
        }
    }

    @Nullable
    public ResourceHandler<FluidResource> getExternalFluidHandler(@Nullable Direction side) {
        if (side == null) return null;
        if (!isSideEnabled(side)) return null;
        if (getRole(side) == SideRole.NONE) return null;
        return sideCapabilities[dirIndex(side)];
    }

    int fillIntoNetwork(Direction inputSide, FluidStack resource, IFluidHandler.FluidAction action) {
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

    FluidStack drainFromNetwork(Direction outputSide, int amount, @Nullable FluidStack match, IFluidHandler.FluidAction action) {
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

    private void tryTransferOnce(ServerLevel sl, Direction plugSide, int amountMB) {
        int networkKey = getNetworkKey(plugSide);
        IFluidHandler dst = getCachedNeighborFluidHandler(plugSide);
        if (dst == null) return;

        QuickLinkFluidNetworkManager mgr = QuickLinkFluidNetworkManager.get(sl);

        record Src(FluidPlugBlockEntity be, Direction dir) {}
        List<Src> sources = new ArrayList<>();
        for (QuickLinkFluidNetworkManager.GlobalPosRef ref : mgr.getPointsSnapshot(networkKey)) {
            ServerLevel pl = sl.getServer().getLevel(ref.dimension());
            if (pl == null) continue;
            BlockEntity be = pl.getBlockEntity(ref.pos());
            if (!(be instanceof FluidPlugBlockEntity pBe) || !pBe.enabled) continue;
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
            boolean moved;
            if (s.be().isInfiniteWater(s.dir())) {
                moved = pushInfiniteWater(dst, s.be(), s.dir());
            } else {
                IFluidHandler src = s.be().getCachedNeighborFluidHandler(s.dir());
                if (src == null) continue;
                moved = moveFluidAny(src, dst, amountMB);
            }
            if (moved) {
                rrIndexBySide[pIdx] = (idx + 1) % sources.size();
                setChanged();
                return;
            }
        }

        rrIndexBySide[pIdx] = (rrIndexBySide[pIdx] + 1) % sources.size();
        setChanged();
    }

    private static boolean pushInfiniteWater(@Nullable IFluidHandler dst, FluidPlugBlockEntity plugBe, Direction pointSide) {
        int idx = dirIndex(pointSide);
        long rateMb = plugBe.effectiveInfiniteMbPerTick();
        int maxChunk = plugBe.effectiveInfiniteMaxPush();
        plugBe.waterAccumBySide[idx] += rateMb;

        if (dst == null) return false;
        FluidStack probe = new FluidStack(Fluids.WATER, 1);
        if (dst.fill(probe, IFluidHandler.FluidAction.SIMULATE) <= 0) return false;

        boolean movedAny = false;
        for (int i = 0; i < 8; i++) {
            int toMove = (int) Math.min(plugBe.waterAccumBySide[idx], maxChunk);
            if (toMove <= 0) break;
            FluidStack water = new FluidStack(Fluids.WATER, toMove);
            int filled = dst.fill(water, IFluidHandler.FluidAction.EXECUTE);
            if (filled <= 0) break;
            plugBe.waterAccumBySide[idx] -= filled;
            movedAny = true;
        }
        if (movedAny) plugBe.setChanged();
        return movedAny;
    }

    @Nullable
    private IFluidHandler getCachedNeighborFluidHandler(Direction side) {
        BlockPos target = worldPosition.relative(side);
        Direction targetFace = side.getOpposite();
        BlockEntity be = level.getBlockEntity(target);
        if (be instanceof FluidPlugBlockEntity plug) {
            ResourceHandler<FluidResource> rh = plug.getExternalFluidHandler(targetFace);
            return rh != null ? IFluidHandler.of(rh) : null;
        }
        BlockCapabilityCache<ResourceHandler<FluidResource>, Direction> cache = neighborCaches[dirIndex(side)];
        ResourceHandler<FluidResource> rh = cache != null
            ? cache.getCapability()
            : level.getCapability(Capabilities.Fluid.BLOCK, target, targetFace);
        return rh != null ? IFluidHandler.of(rh) : null;
    }

    private static boolean moveFluidAny(IFluidHandler src, @Nullable IFluidHandler dst, int amountMB) {
        if (amountMB <= 0 || dst == null) return false;

        FluidStack canDrain = src.drain(amountMB, IFluidHandler.FluidAction.SIMULATE);
        if (canDrain.isEmpty() || canDrain.getAmount() <= 0) return false;

        int canFill = dst.fill(canDrain, IFluidHandler.FluidAction.SIMULATE);
        if (canFill <= 0) return false;

        int toMove = Math.min(canDrain.getAmount(), canFill);
        if (toMove <= 0) return false;

        FluidStack drained = src.drain(toMove, IFluidHandler.FluidAction.EXECUTE);
        if (drained.isEmpty() || drained.getAmount() <= 0) return false;

        return dst.fill(drained, IFluidHandler.FluidAction.EXECUTE) > 0;
    }

    private FluidStack peekNetworkFluid(Direction outputSide) {
        if (!isPlugEnabled(outputSide) || !(level instanceof ServerLevel sl)) return FluidStack.EMPTY;

        QuickLinkFluidNetworkManager mgr = QuickLinkFluidNetworkManager.get(sl);
        int networkKey = getNetworkKey(outputSide);
        List<QuickLinkFluidNetworkManager.GlobalPosRef> plugs = mgr.getPlugsSnapshot(networkKey);

        for (QuickLinkFluidNetworkManager.GlobalPosRef ref : plugs) {
            ServerLevel plugLevel = sl.getServer().getLevel(ref.dimension());
            if (plugLevel == null) continue;
            BlockEntity other = plugLevel.getBlockEntity(ref.pos());
            if (!(other instanceof FluidPlugBlockEntity plugBe) || !plugBe.enabled) continue;
            for (Direction plugSide : Direction.values()) {
                if (!plugBe.isPointEnabled(plugSide) || plugBe.getNetworkKey(plugSide) != networkKey) continue;
                if (plugBe.isInfiniteWater(plugSide)) return new FluidStack(Fluids.WATER, 1);
                IFluidHandler src = plugBe.getCachedNeighborFluidHandler(plugSide);
                if (src == null) continue;
                FluidStack simulated = src.drain(1, IFluidHandler.FluidAction.SIMULATE);
                if (!simulated.isEmpty()) return simulated;
            }
        }
        return FluidStack.EMPTY;
    }

    private static final class SideFluidHandler implements ResourceHandler<FluidResource> {
        private final FluidPlugBlockEntity owner;
        private final Direction side;

        private SideFluidHandler(FluidPlugBlockEntity owner, Direction side) {
            this.owner = owner;
            this.side = side;
        }

        @Override
        public int size() { return 1; }

        @Override
        public FluidResource getResource(int slot) {
            if (slot != 0) return FluidResource.EMPTY;
            FluidStack peek = owner.peekNetworkFluid(side);
            return peek.isEmpty() ? FluidResource.EMPTY : FluidResource.of(peek);
        }

        @Override
        public long getAmountAsLong(int slot) {
            if (slot != 0) return 0L;
            FluidStack peek = owner.peekNetworkFluid(side);
            return peek.isEmpty() ? 0L : Integer.MAX_VALUE;
        }

        @Override
        public long getCapacityAsLong(int slot, FluidResource resource) { return Integer.MAX_VALUE; }

        @Override
        public boolean isValid(int slot, FluidResource resource) {
            return slot == 0 && !resource.isEmpty() && owner.isPointEnabled(side);
        }

        @Override
        public int insert(int slot, FluidResource resource, int maxAmount, TransactionContext ctx) {
            if (slot != 0 || resource.isEmpty() || maxAmount <= 0) return 0;
            FluidStack stack = resource.toStack(maxAmount);
            return owner.fillIntoNetwork(side, stack, IFluidHandler.FluidAction.EXECUTE);
        }

        @Override
        public int extract(int slot, FluidResource resource, int maxAmount, TransactionContext ctx) {
            if (slot != 0 || maxAmount <= 0) return 0;
            FluidStack match = resource.isEmpty() ? null : resource.toStack(maxAmount);
            FluidStack drained = owner.drainFromNetwork(side, maxAmount, match, IFluidHandler.FluidAction.EXECUTE);
            return drained.getAmount();
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putIntArray(QuickLinkNbt.SIDE_COLORS, getSideColorsPacked());
        output.putInt(QuickLinkNbt.COLORS, sideColors[0].pack());
        output.putBoolean(QuickLinkNbt.ENABLED, enabled);
        output.putInt("ql_plug_mask", clampMask6(plugMask));
        output.putInt("ql_point_mask", clampMask6(pointMask));
        output.putInt("ql_disabled_mask", clampMask6(disabledMask));
        output.putInt("ql_inf_water_mask", clampMask6(infiniteWaterMask));
        output.putIntArray("ql_rr_side", rrIndexBySide);
        for (int i = 0; i < 6; i++) output.putLong("ql_waccum_" + i, waterAccumBySide[i]);
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
            int packed = input.getIntOr(QuickLinkNbt.COLORS, QuickLinkColors.unset().pack());
            QuickLinkColors legacy = QuickLinkColors.unpack(packed);
            for (int i = 0; i < 6; i++) sideColors[i] = legacy;
        }

        enabled = input.getBooleanOr(QuickLinkNbt.ENABLED, true);
        plugMask = clampMask6(input.getIntOr("ql_plug_mask", 0));
        pointMask = clampMask6(input.getIntOr("ql_point_mask", 0));
        disabledMask = clampMask6(input.getIntOr("ql_disabled_mask", 0));
        infiniteWaterMask = clampMask6(input.getIntOr("ql_inf_water_mask", 0));

        int[] rrArr = input.getIntArray("ql_rr_side").orElse(new int[0]);
        for (int i = 0; i < 6; i++) rrIndexBySide[i] = (rrArr.length > i) ? Math.max(0, rrArr[i]) : 0;

        for (int i = 0; i < 6; i++) waterAccumBySide[i] = Math.max(0L, input.getLongOr("ql_waccum_" + i, 0L));

        infiniteWaterMask &= pointMask;
        upgradeTier = Math.max(0, Math.min(UpgradeTier.MAX_TIER, input.getIntOr(QuickLinkNbt.UPGRADE_TIER, 0)));
    }
}
