package com.maximpolyakov.quicklink.forge.blockentity;

import com.maximpolyakov.quicklink.QuickLinkColors;
import com.maximpolyakov.quicklink.QuickLinkNbt;
import com.maximpolyakov.quicklink.forge.QuickLinkForge;
import com.maximpolyakov.quicklink.forge.UpgradeTier;
import com.maximpolyakov.quicklink.forge.compat.ftbchunks.FTBChunksCompat;
import com.maximpolyakov.quicklink.forge.compat.ftbteams.FTBTeamsCompat;
import com.maximpolyakov.quicklink.forge.config.QuickLinkConfig;
import com.maximpolyakov.quicklink.forge.network.QuickLinkFluidNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public class FluidPlugBlockEntity extends BlockEntity {

    static int period = QuickLinkConfig.FLUID_TICK_PERIOD.get();

    private int upgradeTier      = 0;
    private int plugMask         = 0;
    private int pointMask        = 0;
    private int disabledMask     = 0;
    private int infiniteWaterMask = 0;
    private final int[]  rrIndexBySide   = new int[6];
    private final long[] waterAccumBySide = new long[6];

    private final QuickLinkColors[] sideColors = new QuickLinkColors[6];
    private boolean enabled = true;
    private UUID ownerUUID = null;

    private java.util.Set<Integer> lastRegPlugKeys  = new java.util.HashSet<>();
    private java.util.Set<Integer> lastRegPointKeys = new java.util.HashSet<>();

    private final IFluidHandler[] sideHandlers = new IFluidHandler[6];
    @SuppressWarnings("unchecked")
    private final LazyOptional<IFluidHandler>[] sideOptionals = new LazyOptional[6];

    public FluidPlugBlockEntity(BlockPos pos, BlockState state) {
        super(QuickLinkForge.FLUID_PLUG_BE.get(), pos, state);
        for (Direction side : Direction.values()) {
            int i = dirIndex(side);
            sideColors[i] = QuickLinkColors.unset();
            sideHandlers[i] = new SideFluidHandler(this, side);
        }
        for (Direction side : Direction.values()) {
            final int i = dirIndex(side);
            sideOptionals[i] = LazyOptional.of(() -> sideHandlers[i]);
        }
    }

    @NotNull @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (side != null && cap == ForgeCapabilities.FLUID_HANDLER) {
            if (isSideEnabled(side) && getRole(side) != SideRole.NONE)
                return sideOptionals[dirIndex(side)].cast();
        }
        return super.getCapability(cap, side);
    }

    @Override public void invalidateCaps() { super.invalidateCaps(); for (LazyOptional<IFluidHandler> lo : sideOptionals) lo.invalidate(); }

    private static int bit(Direction d) { int i = d.get3DDataValue(); return 1 << Math.max(0, Math.min(5, i)); }
    private static int clampMask6(int m) { return m & 0b111111; }
    static int dirIndex(Direction d) { return Math.max(0, Math.min(5, d.get3DDataValue())); }

    private int lastSentMb     = 0;
    int         pendingReceivedMb = 0;
    private int lastReceivedMb = 0;

    public int getUpgradeTier() { return upgradeTier; }
    public void setUpgradeTier(int tier) { upgradeTier = Math.max(0, Math.min(UpgradeTier.MAX_TIER, tier)); setChangedAndSync(); }
    public int effectiveAmountMb() { return QuickLinkConfig.FLUID_TRANSFER_MB.get() * UpgradeTier.multiplier(upgradeTier); }
    public int getLastSentMb()     { return lastSentMb; }
    public int getLastReceivedMb() { return lastReceivedMb; }
    public int getTickPeriod()     { return period; }
    public long effectiveInfiniteMbPerTick() { return (long) QuickLinkConfig.FLUID_INFINITE_MB_PER_TICK.get() * UpgradeTier.multiplier(upgradeTier); }
    public int effectiveInfiniteMaxPush() { return QuickLinkConfig.FLUID_INFINITE_MAX_PUSH_PER_TICK.get() * UpgradeTier.multiplier(upgradeTier); }

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
    public int getNetworkKey(Direction side) {
        int colorKey = sideColors[dirIndex(side)].networkKey();
        int claimHash = QuickLinkForge.FTBCHUNKS_LOADED ? FTBChunksCompat.claimTeamComponent(level, worldPosition) : FTBChunksCompat.NOT_CLAIMED;
        // See EnergyPlugBlockEntity.getNetworkKey: the claim is what makes a plug survive its
        // owner leaving the team, and a claim key equals the same team's membership key.
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
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean e) { this.enabled = e; setChangedAndSync(); }
    public void setColor(Direction side, int slot, byte colorId) {
        int idx = dirIndex(side); int oldKey = sideColors[idx].networkKey();
        sideColors[idx] = sideColors[idx].with(slot, colorId); setChangedAndSync();
        if (oldKey != sideColors[idx].networkKey()) syncRegistration();
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
    public int getPlugMask() { return plugMask; }
    public int getPointMask() { return pointMask; }
    public int getDisabledMask() { return disabledMask; }
    public boolean isInfiniteWater(Direction side) { return (infiniteWaterMask & bit(side)) != 0; }
    public boolean toggleInfiniteWater(Direction side) {
        SideRole role = getRole(side);
        if (role != SideRole.POINT && role != SideRole.BOTH) return false;
        int b = bit(side); infiniteWaterMask ^= b;
        if ((infiniteWaterMask & b) == 0) waterAccumBySide[dirIndex(side)] = 0L;
        infiniteWaterMask = clampMask6(infiniteWaterMask); setChangedAndSync(); return true;
    }

    private void setChangedAndSync() {
        setChanged(); if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    // See EnergyPlugBlockEntity: setRemoved() also fires on chunk unload, and unregistering there
    // would drop the plug from the saved network for good.
    private boolean unloading = false;

    @Override public void onChunkUnloaded() { unloading = true; super.onChunkUnloaded(); }
    @Override public void onLoad() { super.onLoad(); if (level instanceof ServerLevel) syncRegistration(); }
    @Override public void setRemoved() { if (!unloading && level != null && !level.isClientSide) unregisterFromManager(); super.setRemoved(); }

    private void unregisterFromManager() {
        if (!(level instanceof ServerLevel sl)) return;
        QuickLinkFluidNetworkManager mgr = QuickLinkFluidNetworkManager.get(sl);
        for (int key : lastRegPlugKeys)  mgr.unregisterPlug(sl, key, worldPosition);
        for (int key : lastRegPointKeys) mgr.unregisterPoint(sl, key, worldPosition);
        lastRegPlugKeys.clear(); lastRegPointKeys.clear();
    }
    private void syncRegistration() {
        if (!(level instanceof ServerLevel sl)) return; unregisterFromManager();
        java.util.Set<Integer> pk = new java.util.HashSet<>(), ptk = new java.util.HashSet<>();
        for (Direction side : Direction.values()) { if (isPlugEnabled(side)) pk.add(getNetworkKey(side)); if (isPointEnabled(side)) ptk.add(getNetworkKey(side)); }
        QuickLinkFluidNetworkManager mgr = QuickLinkFluidNetworkManager.get(sl);
        for (int key : pk) mgr.registerPlug(sl, key, worldPosition); for (int key : ptk) mgr.registerPoint(sl, key, worldPosition);
        lastRegPlugKeys = pk; lastRegPointKeys = ptk;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, FluidPlugBlockEntity be) {
        if (!(level instanceof ServerLevel sl) || !be.enabled) return;
        if ((sl.getGameTime() % period) != 0L) return;
        be.lastReceivedMb = be.pendingReceivedMb;
        be.pendingReceivedMb = 0;
        int total = 0;
        for (Direction plugSide : Direction.values()) if (be.isPlugEnabled(plugSide)) total += be.tryTransferOnce(sl, plugSide, be.effectiveAmountMb());
        be.lastSentMb = total;
    }

    @Nullable
    public IFluidHandler getExternalFluidHandler(@Nullable Direction side) {
        if (side == null || !isSideEnabled(side) || getRole(side) == SideRole.NONE) return null;
        return sideHandlers[dirIndex(side)];
    }

    private int fillIntoNetwork(Direction inputSide, FluidStack resource, IFluidHandler.FluidAction action) {
        if (resource.isEmpty() || !isPointEnabled(inputSide) || !(level instanceof ServerLevel sl)) return 0;
        QuickLinkFluidNetworkManager mgr = QuickLinkFluidNetworkManager.get(sl);
        int networkKey = getNetworkKey(inputSide);
        List<QuickLinkFluidNetworkManager.GlobalPosRef> points = mgr.getPointsSnapshot(networkKey);
        if (points.isEmpty()) return 0;
        int moved = 0, left = resource.getAmount(), start = rrIndexBySide[dirIndex(inputSide)];
        for (int i = 0; i < points.size() && left > 0; i++) {
            int idx = (start + i) % points.size();
            QuickLinkFluidNetworkManager.GlobalPosRef ref = points.get(idx);
            ServerLevel pointLevel = sl.getServer().getLevel(ref.dimension()); if (pointLevel == null) continue;
            BlockEntity other = pointLevel.getBlockEntity(ref.pos());
            if (!(other instanceof FluidPlugBlockEntity pointBe) || !pointBe.enabled) continue;
            for (Direction pointSide : Direction.values()) {
                if (!pointBe.isPlugEnabled(pointSide) || pointBe.getNetworkKey(pointSide) != networkKey) continue;
                IFluidHandler dst = pointBe.getCachedNeighborFluidHandler(pointSide); if (dst == null) continue;
                FluidStack toFill = new FluidStack(resource.getFluid(), left, resource.getTag());
                int accepted = dst.fill(toFill, action); if (accepted <= 0) continue;
                moved += accepted; left -= accepted;
                if (action.execute()) { rrIndexBySide[dirIndex(inputSide)] = (idx + 1) % points.size(); setChanged(); }
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
            ServerLevel plugLevel = sl.getServer().getLevel(ref.dimension()); if (plugLevel == null) continue;
            BlockEntity other = plugLevel.getBlockEntity(ref.pos());
            if (!(other instanceof FluidPlugBlockEntity plugBe) || !plugBe.enabled) continue;
            for (Direction plugSide : Direction.values()) {
                if (!plugBe.isPointEnabled(plugSide) || plugBe.getNetworkKey(plugSide) != networkKey) continue;
                if (plugBe.isInfiniteWater(plugSide)) {
                    if (match != null && !match.isEmpty() && match.getFluid() != Fluids.WATER) continue;
                    FluidStack provided = new FluidStack(Fluids.WATER, amount);
                    if (action.execute()) { rrIndexBySide[dirIndex(outputSide)] = (idx + 1) % plugs.size(); setChanged(); }
                    return provided;
                }
                IFluidHandler src = plugBe.getCachedNeighborFluidHandler(plugSide); if (src == null) continue;
                FluidStack drained = (match == null) ? src.drain(amount, action)
                        : src.drain(new FluidStack(match.getFluid(), amount, match.getTag()), action);
                if (drained.isEmpty()) continue;
                if (action.execute()) { rrIndexBySide[dirIndex(outputSide)] = (idx + 1) % plugs.size(); setChanged(); }
                return drained;
            }
        }
        return FluidStack.EMPTY;
    }

    private int tryTransferOnce(ServerLevel sl, Direction plugSide, int amountMB) {
        int networkKey = getNetworkKey(plugSide);
        IFluidHandler dst = getCachedNeighborFluidHandler(plugSide); if (dst == null) return 0;
        QuickLinkFluidNetworkManager mgr = QuickLinkFluidNetworkManager.get(sl);
        List<QuickLinkFluidNetworkManager.GlobalPosRef> points = mgr.getPointsSnapshot(networkKey);
        if (points.isEmpty()) return 0;
        // Resolve points lazily in round-robin order and stop at the first one that moves fluid.
        // getBlockEntity() force-loads the target chunk, so collecting every source up front would
        // load one chunk per network member on every attempt, across every dimension involved.
        int pIdx = dirIndex(plugSide), start = rrIndexBySide[pIdx] % points.size();
        for (int i = 0; i < points.size(); i++) {
            int idx = (start + i) % points.size();
            QuickLinkFluidNetworkManager.GlobalPosRef ref = points.get(idx);
            ServerLevel pl = sl.getServer().getLevel(ref.dimension()); if (pl == null) continue;
            BlockEntity be = pl.getBlockEntity(ref.pos());
            if (!(be instanceof FluidPlugBlockEntity pBe) || !pBe.enabled) continue;
            for (Direction d : Direction.values()) {
                if (!pBe.isPointEnabled(d) || pBe.getNetworkKey(d) != networkKey) continue;
                int moved;
                if (pBe.isInfiniteWater(d)) {
                    moved = pushInfiniteWater(dst, pBe, d);
                } else {
                    IFluidHandler src = pBe.getCachedNeighborFluidHandler(d); if (src == null) continue;
                    moved = moveFluidAny(src, dst, amountMB);
                }
                if (moved > 0) { rrIndexBySide[pIdx] = (idx + 1) % points.size(); setChanged(); pBe.pendingReceivedMb += moved; return moved; }
            }
        }
        rrIndexBySide[pIdx] = (rrIndexBySide[pIdx] + 1) % points.size(); setChanged();
        return 0;
    }

    private static int pushInfiniteWater(@Nullable IFluidHandler dst, FluidPlugBlockEntity plugBe, Direction pointSide) {
        int idx = dirIndex(pointSide);
        plugBe.waterAccumBySide[idx] += plugBe.effectiveInfiniteMbPerTick();
        if (dst == null) return 0;
        if (dst.fill(new FluidStack(Fluids.WATER, 1), IFluidHandler.FluidAction.SIMULATE) <= 0) return 0;
        int totalMoved = 0;
        for (int i = 0; i < 8; i++) {
            int toMove = (int) Math.min(plugBe.waterAccumBySide[idx], plugBe.effectiveInfiniteMaxPush());
            if (toMove <= 0) break;
            int filled = dst.fill(new FluidStack(Fluids.WATER, toMove), IFluidHandler.FluidAction.EXECUTE);
            if (filled <= 0) break;
            plugBe.waterAccumBySide[idx] -= filled; totalMoved += filled;
        }
        if (totalMoved > 0) plugBe.setChanged();
        return totalMoved;
    }

    @Nullable
    private IFluidHandler getCachedNeighborFluidHandler(Direction side) {
        BlockPos target = worldPosition.relative(side); Direction targetFace = side.getOpposite();
        BlockEntity be = level.getBlockEntity(target);
        if (be instanceof FluidPlugBlockEntity plug) return plug.getExternalFluidHandler(targetFace);
        if (be != null) return be.getCapability(ForgeCapabilities.FLUID_HANDLER, targetFace).orElse(null);
        return null;
    }

    private static int moveFluidAny(IFluidHandler src, @Nullable IFluidHandler dst, int amountMB) {
        if (amountMB <= 0 || dst == null) return 0;
        FluidStack canDrain = src.drain(amountMB, IFluidHandler.FluidAction.SIMULATE);
        if (canDrain.isEmpty() || canDrain.getAmount() <= 0) return 0;
        int canFill = dst.fill(canDrain, IFluidHandler.FluidAction.SIMULATE); if (canFill <= 0) return 0;
        int toMove = Math.min(canDrain.getAmount(), canFill); if (toMove <= 0) return 0;
        FluidStack drained = src.drain(toMove, IFluidHandler.FluidAction.EXECUTE);
        if (drained.isEmpty() || drained.getAmount() <= 0) return 0;
        return dst.fill(drained, IFluidHandler.FluidAction.EXECUTE);
    }

    private static final class SideFluidHandler implements IFluidHandler {
        private final FluidPlugBlockEntity owner; private final Direction side;
        SideFluidHandler(FluidPlugBlockEntity o, Direction s) { owner = o; side = s; }
        @Override public int getTanks() { return 1; }
        @Override public @NotNull FluidStack getFluidInTank(int tank) { return tank != 0 ? FluidStack.EMPTY : owner.peekNetworkFluid(side); }
        @Override public int getTankCapacity(int tank) { return Integer.MAX_VALUE; }
        @Override public boolean isFluidValid(int tank, @NotNull FluidStack stack) { return owner.isPointEnabled(side); }
        @Override public int fill(FluidStack resource, FluidAction action) { return owner.fillIntoNetwork(side, resource, action); }
        @Override public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
            if (resource.isEmpty()) return FluidStack.EMPTY;
            return owner.drainFromNetwork(side, resource.getAmount(), resource, action);
        }
        @Override public @NotNull FluidStack drain(int maxDrain, FluidAction action) { return owner.drainFromNetwork(side, maxDrain, null, action); }
    }

    private FluidStack peekNetworkFluid(Direction outputSide) {
        if (!isPlugEnabled(outputSide) || !(level instanceof ServerLevel sl)) return FluidStack.EMPTY;
        QuickLinkFluidNetworkManager mgr = QuickLinkFluidNetworkManager.get(sl);
        int networkKey = getNetworkKey(outputSide);
        List<QuickLinkFluidNetworkManager.GlobalPosRef> plugs = mgr.getPlugsSnapshot(networkKey);
        for (QuickLinkFluidNetworkManager.GlobalPosRef ref : plugs) {
            ServerLevel plugLevel = sl.getServer().getLevel(ref.dimension()); if (plugLevel == null) continue;
            BlockEntity other = plugLevel.getBlockEntity(ref.pos());
            if (!(other instanceof FluidPlugBlockEntity plugBe) || !plugBe.enabled) continue;
            for (Direction plugSide : Direction.values()) {
                if (!plugBe.isPointEnabled(plugSide) || plugBe.getNetworkKey(plugSide) != networkKey) continue;
                if (plugBe.isInfiniteWater(plugSide)) return new FluidStack(Fluids.WATER, 1);
                IFluidHandler src = plugBe.getCachedNeighborFluidHandler(plugSide); if (src == null) continue;
                FluidStack sim = src.drain(1, IFluidHandler.FluidAction.SIMULATE); if (!sim.isEmpty()) return sim;
            }
        }
        return FluidStack.EMPTY;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putIntArray(QuickLinkNbt.SIDE_COLORS, getSideColorsPacked()); tag.putInt(QuickLinkNbt.COLORS, sideColors[0].pack());
        tag.putBoolean(QuickLinkNbt.ENABLED, enabled);
        tag.putInt("ql_plug_mask", clampMask6(plugMask)); tag.putInt("ql_point_mask", clampMask6(pointMask));
        tag.putInt("ql_disabled_mask", clampMask6(disabledMask)); tag.putInt("ql_inf_water_mask", clampMask6(infiniteWaterMask));
        tag.putIntArray("ql_rr_side", rrIndexBySide); tag.putLongArray("ql_inf_water_accum", waterAccumBySide);
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
            QuickLinkColors leg = QuickLinkColors.unpack(tag.contains(QuickLinkNbt.COLORS, Tag.TAG_INT) ? tag.getInt(QuickLinkNbt.COLORS) : QuickLinkColors.unset().pack());
            for (int i = 0; i < 6; i++) sideColors[i] = leg;
        }
        enabled = !tag.contains(QuickLinkNbt.ENABLED, Tag.TAG_BYTE) || tag.getBoolean(QuickLinkNbt.ENABLED);
        plugMask         = clampMask6(tag.contains("ql_plug_mask",      Tag.TAG_INT) ? tag.getInt("ql_plug_mask")      : 0);
        pointMask        = clampMask6(tag.contains("ql_point_mask",     Tag.TAG_INT) ? tag.getInt("ql_point_mask")     : 0);
        disabledMask     = clampMask6(tag.contains("ql_disabled_mask",  Tag.TAG_INT) ? tag.getInt("ql_disabled_mask")  : 0);
        infiniteWaterMask = clampMask6(tag.contains("ql_inf_water_mask", Tag.TAG_INT) ? tag.getInt("ql_inf_water_mask") : 0);
        if (tag.contains("ql_rr_side", Tag.TAG_INT_ARRAY)) {
            int[] arr = tag.getIntArray("ql_rr_side"); for (int i = 0; i < 6; i++) rrIndexBySide[i] = (arr != null && arr.length > i) ? Math.max(0, arr[i]) : 0;
        }
        if (tag.contains("ql_inf_water_accum", Tag.TAG_LONG_ARRAY)) {
            long[] arr = tag.getLongArray("ql_inf_water_accum"); for (int i = 0; i < 6; i++) waterAccumBySide[i] = (arr != null && arr.length > i) ? Math.max(0L, arr[i]) : 0L;
        }
        infiniteWaterMask &= pointMask;
        upgradeTier = Math.max(0, Math.min(UpgradeTier.MAX_TIER, tag.contains(QuickLinkNbt.UPGRADE_TIER, Tag.TAG_INT) ? tag.getInt(QuickLinkNbt.UPGRADE_TIER) : 0));
        lastRegPlugKeys  = QuickLinkNbt.unpackKeys(tag.getIntArray(QuickLinkNbt.REG_PLUG_KEYS));
        lastRegPointKeys = QuickLinkNbt.unpackKeys(tag.getIntArray(QuickLinkNbt.REG_POINT_KEYS));
        ownerUUID = tag.hasUUID(QuickLinkNbt.OWNER_UUID) ? tag.getUUID(QuickLinkNbt.OWNER_UUID) : null;
    }

    @Override public CompoundTag getUpdateTag() { CompoundTag tag = super.getUpdateTag(); saveAdditional(tag); return tag; }
    @Nullable @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
