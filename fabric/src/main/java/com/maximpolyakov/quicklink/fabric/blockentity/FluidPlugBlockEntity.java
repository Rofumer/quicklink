package com.maximpolyakov.quicklink.fabric.blockentity;

import com.maximpolyakov.quicklink.fabric.config.QuickLinkConfig;
import com.maximpolyakov.quicklink.QuickLinkColors;
import com.maximpolyakov.quicklink.QuickLinkNbt;
import com.maximpolyakov.quicklink.fabric.QuickLinkFabric;
import com.maximpolyakov.quicklink.fabric.network.QuickLinkFluidNetworkManager;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

public class FluidPlugBlockEntity extends BlockEntity {

    // ==== transfer tuning ====
    // фиксированный объём за попытку (mB)
    //public static final int TRANSFER_MB = 250; // 250 / 500 / 1000
    // попытка раз в N тиков
    //public static final int TICK_PERIOD = 10;
    static int amountMB = QuickLinkConfig.FLUID_TRANSFER_MB.get();
    static int period = QuickLinkConfig.FLUID_TICK_PERIOD.get();
    // отладка трансфера (включи если снова "ничего не происходит")
    private static final boolean DBG_TRANSFER = false;
    // =========================

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

    // cached registration state
    private java.util.Set<Integer> lastRegPlugKeys = new java.util.HashSet<>();
    private java.util.Set<Integer> lastRegPointKeys = new java.util.HashSet<>();
    private final IFluidHandler[] sideCapabilities = new IFluidHandler[6];

    public FluidPlugBlockEntity(BlockPos pos, BlockState state) {
        super(QuickLinkFabric.FLUID_PLUG_BE, pos, state);
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
        return sideColors[dirIndex(side)].networkKey();
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
    public void setLevel(Level level) {
        super.setLevel(level);
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

        // try for each enabled PLUG side
        for (Direction plugSide : Direction.values()) {
            if (be.isPlugEnabled(plugSide)) {
                be.tryTransferOnce(sl, plugSide, amountMB);
            }
        }
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
                List<IFluidHandler> dsts = getAllFillHandlers(pointLevel, ref.pos(), pointSide);
                if (dsts.isEmpty()) continue;

                FluidStack toFill = resource.copy();
                toFill.setAmount(left);
                int accepted = 0;
                for (IFluidHandler dst : dsts) {
                    accepted = dst.fill(toFill, action);
                    if (accepted > 0) break;
                }
                if (accepted <= 0) continue;

                moved += accepted;
                left -= accepted;

                if (action == IFluidHandler.FluidAction.EXECUTE) {
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
                    if (match != null && !match.isEmpty() && match.getFluid() != Fluids.WATER) continue;

                    FluidStack provided = new FluidStack(Fluids.WATER, amount);
                    if (action == IFluidHandler.FluidAction.EXECUTE) {
                        rrIndexBySide[dirIndex(outputSide)] = (idx + 1) % plugs.size();
                        setChanged();
                    }
                    return provided;
                }

                IFluidHandler src = getAttachedFluidHandlerForDrain(plugLevel, ref.pos(), plugSide);
                if (src == null) continue;

                FluidStack drained;
                if (match == null) {
                    drained = src.drain(amount, action);
                } else {
                    FluidStack req = match.copy();
                    req.setAmount(amount);
                    drained = src.drain(req, action);
                }
                if (drained.isEmpty()) continue;

                if (action == IFluidHandler.FluidAction.EXECUTE) {
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
    private void tryTransferOnce(ServerLevel sl, Direction plugSide, int amountMB) {
        int networkKey = getNetworkKey(plugSide);

        List<IFluidHandler> dsts = getAllFillHandlers(sl, worldPosition, plugSide);
        if (dsts.isEmpty()) return;

        QuickLinkFluidNetworkManager mgr = QuickLinkFluidNetworkManager.get(sl);

        record Src(ServerLevel lvl, BlockPos pos, Direction dir, FluidPlugBlockEntity be) {}
        List<Src> sources = new ArrayList<>();
        for (QuickLinkFluidNetworkManager.GlobalPosRef ref : mgr.getPointsSnapshot(networkKey)) {
            ServerLevel pl = sl.getServer().getLevel(ref.dimension());
            if (pl == null) continue;
            BlockEntity be = pl.getBlockEntity(ref.pos());
            if (!(be instanceof FluidPlugBlockEntity pBe) || !pBe.enabled) continue;
            for (Direction d : Direction.values()) {
                if (!pBe.isPointEnabled(d) || pBe.getNetworkKey(d) != networkKey) continue;
                sources.add(new Src(pl, ref.pos(), d, pBe));
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
                moved = pushInfiniteWater(dsts, s.be(), s.dir());
            } else {
                IFluidHandler src = getAttachedFluidHandlerForDrain(s.lvl(), s.pos(), s.dir());
                if (src == null) continue;
                moved = moveFluidAny(src, dsts, amountMB);
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

    private static boolean pushInfiniteWater(List<IFluidHandler> dsts, FluidPlugBlockEntity plugBe, Direction pointSide) {
        int idx = dirIndex(pointSide);

        long rateMb = QuickLinkConfig.FLUID_INFINITE_MB_PER_TICK.get();
        int maxChunk = QuickLinkConfig.FLUID_INFINITE_MAX_PUSH_PER_TICK.get();

        plugBe.waterAccumBySide[idx] += rateMb;

        // Find the first handler that can accept water
        FluidStack probe = new FluidStack(Fluids.WATER, 1);
        IFluidHandler dst = null;
        for (IFluidHandler h : dsts) {
            if (h.fill(probe, IFluidHandler.FluidAction.SIMULATE) > 0) {
                dst = h;
                break;
            }
        }
        if (dst == null) return false;

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

        if (movedAny) {
            plugBe.setChanged();
        }

        return movedAny;
    }

    /**
     * IMPORTANT:
     * target is "behind" the face: target = selfPos.relative(side.getOpposite())
     *
     * Capability query side MUST be the TARGET's side that touches us, i.e. 'side' (not opposite).
     * This is the common bug that makes dst/src null with tanks.
     */
    @Nullable
    private static IFluidHandler getAttachedFluidHandlerForDrain(ServerLevel level, BlockPos selfPos, Direction side) {
        BlockPos target = selfPos.relative(side);
        Direction targetFaceTowardUs = side.getOpposite();
        BlockEntity be = level.getBlockEntity(target);
        if (be instanceof FluidPlugBlockEntity plug) {
            return plug.getExternalFluidHandler(targetFaceTowardUs);
        }
        BlockState state = level.getBlockState(target);
        Storage<FluidVariant> storage = FluidStorage.SIDED.find(level, target, state, be, targetFaceTowardUs);
        return storage != null ? new FabricFluidStorageAdapter(storage) : null;
    }

    /**
     * Returns all unique fluid handlers on the adjacent block for use as fill destinations.
     * Tries all 6 faces so that mods blocking fills on specific sides can still be filled
     * via an accepting face.
     */
    private static List<IFluidHandler> getAllFillHandlers(ServerLevel level, BlockPos selfPos, Direction side) {
        BlockPos target = selfPos.relative(side);
        BlockEntity be = level.getBlockEntity(target);

        if (be instanceof FluidPlugBlockEntity plug) {
            IFluidHandler h = plug.getExternalFluidHandler(side.getOpposite());
            return h != null ? List.of(h) : List.of();
        }

        BlockState state = level.getBlockState(target);
        List<IFluidHandler> result = new ArrayList<>();
        Set<Storage<FluidVariant>> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        Storage<FluidVariant> s = FluidStorage.SIDED.find(level, target, state, be, null);
        if (s != null && seen.add(s)) result.add(new FabricFluidStorageAdapter(s));

        for (Direction d : Direction.values()) {
            s = FluidStorage.SIDED.find(level, target, state, be, d);
            if (s != null && seen.add(s)) result.add(new FabricFluidStorageAdapter(s));
        }

        return result;
    }

    private static final class FabricFluidStorageAdapter implements IFluidHandler {
        // Fabric Transfer API uses droplets: 1 bucket = 81000 droplets = 1000 mB → 1 mB = 81 droplets
        private static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000L;

        private final Storage<FluidVariant> storage;

        FabricFluidStorageAdapter(Storage<FluidVariant> storage) {
            this.storage = storage;
        }

        @Override public int getTanks() { return 1; }

        @Override
        public FluidStack getFluidInTank(int tank) {
            for (var view : storage) {
                if (!view.isResourceBlank() && view.getAmount() > 0) {
                    int mB = (int) Math.min(view.getAmount() / DROPLETS_PER_MB, Integer.MAX_VALUE);
                    return new FluidStack(view.getResource().getFluid(), mB);
                }
            }
            return FluidStack.EMPTY;
        }

        @Override
        public int getTankCapacity(int tank) {
            long total = 0;
            for (var view : storage) total += view.getCapacity();
            return (int) Math.min(total / DROPLETS_PER_MB, Integer.MAX_VALUE);
        }

        @Override public boolean isFluidValid(int tank, FluidStack stack) { return true; }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (resource.isEmpty()) return 0;
            FluidVariant variant = FluidVariant.of(resource.getFluid());
            long droplets = (long) resource.getAmount() * DROPLETS_PER_MB;
            try (Transaction tx = Transaction.openOuter()) {
                long inserted = storage.insert(variant, droplets, tx);
                if (action == FluidAction.EXECUTE) tx.commit();
                return (int) (inserted / DROPLETS_PER_MB);
            }
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            if (resource.isEmpty()) return FluidStack.EMPTY;
            FluidVariant variant = FluidVariant.of(resource.getFluid());
            long droplets = (long) resource.getAmount() * DROPLETS_PER_MB;
            try (Transaction tx = Transaction.openOuter()) {
                long extracted = storage.extract(variant, droplets, tx);
                if (action == FluidAction.EXECUTE) tx.commit();
                if (extracted <= 0) return FluidStack.EMPTY;
                return new FluidStack(resource.getFluid(), (int) (extracted / DROPLETS_PER_MB));
            }
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            long droplets = (long) maxDrain * DROPLETS_PER_MB;
            for (var view : storage) {
                if (view.isResourceBlank() || view.getAmount() == 0) continue;
                FluidVariant variant = view.getResource();
                try (Transaction tx = Transaction.openOuter()) {
                    long extracted = storage.extract(variant, droplets, tx);
                    if (action == FluidAction.EXECUTE) tx.commit();
                    if (extracted <= 0) continue;
                    return new FluidStack(variant.getFluid(), (int) (extracted / DROPLETS_PER_MB));
                }
            }
            return FluidStack.EMPTY;
        }
    }

    /**
     * Move up to amountMB from src into the first dst handler that accepts fluid.
     * Returns true if anything actually moved.
     */
    private static boolean moveFluidAny(IFluidHandler src, List<IFluidHandler> dsts, int amountMB) {
        if (amountMB <= 0 || dsts.isEmpty()) return false;

        FluidStack canDrain = src.drain(amountMB, IFluidHandler.FluidAction.SIMULATE);
        if (DBG_TRANSFER) System.out.println("[QLF][DBG] drainSim=" + (canDrain.isEmpty() ? "EMPTY" : (canDrain.getAmount() + " " + canDrain.getFluid())));
        if (canDrain.isEmpty() || canDrain.getAmount() <= 0) return false;

        IFluidHandler chosenDst = null;
        int chosenFill = 0;
        for (IFluidHandler dst : dsts) {
            int canFill = dst.fill(canDrain, IFluidHandler.FluidAction.SIMULATE);
            if (DBG_TRANSFER) System.out.println("[QLF][DBG] fillSim=" + canFill + " dst=" + dst.getClass().getSimpleName());
            if (canFill > 0) {
                chosenDst = dst;
                chosenFill = canFill;
                break;
            }
        }

        if (chosenDst == null) return false;

        int toMove = Math.min(canDrain.getAmount(), chosenFill);
        if (toMove <= 0) return false;

        FluidStack drained = src.drain(toMove, IFluidHandler.FluidAction.EXECUTE);
        if (drained.isEmpty() || drained.getAmount() <= 0) return false;

        int filled = chosenDst.fill(drained, IFluidHandler.FluidAction.EXECUTE);
        if (DBG_TRANSFER) System.out.println("[QLF][DBG] drained=" + drained.getAmount() + " filled=" + filled);
        return filled > 0;
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

                IFluidHandler src = getAttachedFluidHandlerForDrain(plugLevel, ref.pos(), plugSide);
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
    }
}
