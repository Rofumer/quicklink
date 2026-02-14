package com.maximpolyakov.quicklink.neoforge.blockentity;

import com.maximpolyakov.quicklink.neoforge.config.QuickLinkConfig;
import com.maximpolyakov.quicklink.QuickLinkColors;
import com.maximpolyakov.quicklink.QuickLinkNbt;
import com.maximpolyakov.quicklink.neoforge.QuickLinkNeoForge;
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

import net.neoforged.neoforge.capabilities.Capabilities;
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
    private QuickLinkColors colors = QuickLinkColors.unset();

    // master enable
    private boolean enabled = true;

    // cached registration state
    private int lastRegKey = Integer.MIN_VALUE;
    private boolean lastRegHadPlug = false;
    private boolean lastRegHadPoint = false;
    private final IFluidHandler[] sideCapabilities = new IFluidHandler[6];

    public FluidPlugBlockEntity(BlockPos pos, BlockState state) {
        super(QuickLinkNeoForge.FLUID_PLUG_BE.get(), pos, state);
        for (Direction side : Direction.values()) {
            sideCapabilities[dirIndex(side)] = new SideFluidHandler(this, side);
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

    public QuickLinkColors getColors() { return colors; }

    public void setColors(QuickLinkColors colors) {
        this.colors = (colors == null) ? QuickLinkColors.unset() : colors;
        setChangedAndSync();
        syncRegistration();
    }

    public int getNetworkKey() {
        return colors.networkKey();
    }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        setChangedAndSync();
    }

    public void setColor(int slot, byte colorId) {
        int oldKey = getNetworkKey();
        colors = colors.with(slot, colorId);
        setChangedAndSync();

        if (oldKey != getNetworkKey()) {
            syncRegistration();
        }
    }

    public enum SideRole { NONE, PLUG, POINT }

    public SideRole getRole(Direction side) {
        int b = bit(side);
        boolean p = (plugMask & b) != 0;
        boolean t = (pointMask & b) != 0;

        if (p) return SideRole.PLUG;
        if (t) return SideRole.POINT;
        return SideRole.NONE;
    }

    public boolean isSideEnabled(Direction side) {
        int b = bit(side);
        return (disabledMask & b) == 0;
    }

    public boolean isPlugEnabled(Direction side) {
        return getRole(side) == SideRole.PLUG && isSideEnabled(side);
    }

    public boolean isPointEnabled(Direction side) {
        return getRole(side) == SideRole.POINT && isSideEnabled(side);
    }

    /**
     * NONE -> PLUG -> POINT -> NONE
     */
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

        if (next == SideRole.PLUG) {
            plugMask |= b;
        } else if (next == SideRole.POINT) {
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
        if (getRole(side) != SideRole.PLUG) return false;

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
        if (lastRegKey == Integer.MIN_VALUE) return;

        QuickLinkFluidNetworkManager mgr = QuickLinkFluidNetworkManager.get(sl);
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

        boolean hadSomething = (lastRegKey != Integer.MIN_VALUE) && (lastRegHadPlug || lastRegHadPoint);
        boolean keyChanged = hadSomething && (lastRegKey != key);
        boolean plugChanged = hadSomething && (lastRegHadPlug != nowPlug);
        boolean pointChanged = hadSomething && (lastRegHadPoint != nowPoint);

        if (hadSomething && (keyChanged || plugChanged || pointChanged)) {
            unregisterFromManager();
        }

        QuickLinkFluidNetworkManager mgr = QuickLinkFluidNetworkManager.get(sl);
        if (nowPlug) mgr.registerPlug(sl, key, worldPosition);
        if (nowPoint) mgr.registerPoint(sl, key, worldPosition);

        lastRegKey = key;
        lastRegHadPlug = nowPlug;
        lastRegHadPoint = nowPoint;
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

        // try for each enabled POINT side
        for (Direction pointSide : Direction.values()) {
            if (be.isPointEnabled(pointSide)) {
                //be.tryTransferOnce(sl, pointSide, TRANSFER_MB);
                be.tryTransferOnce(sl, pointSide, amountMB);
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
        if (resource.isEmpty() || !isPlugEnabled(inputSide) || !(level instanceof ServerLevel sl)) return 0;

        QuickLinkFluidNetworkManager mgr = QuickLinkFluidNetworkManager.get(sl);
        List<QuickLinkFluidNetworkManager.GlobalPosRef> points = mgr.getPointsSnapshot(getNetworkKey());
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
                if (!pointBe.isPointEnabled(pointSide)) continue;
                IFluidHandler dst = getAttachedFluidHandler(pointLevel, ref.pos(), pointSide);
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
        if (amount <= 0 || !isPointEnabled(outputSide) || !(level instanceof ServerLevel sl)) return FluidStack.EMPTY;

        QuickLinkFluidNetworkManager mgr = QuickLinkFluidNetworkManager.get(sl);
        List<QuickLinkFluidNetworkManager.GlobalPosRef> plugs = mgr.getPlugsSnapshot(getNetworkKey());
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
                if (!plugBe.isPlugEnabled(plugSide) || plugBe.isInfiniteWater(plugSide)) continue;

                IFluidHandler src = getAttachedFluidHandler(plugLevel, ref.pos(), plugSide);
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
    private void tryTransferOnce(ServerLevel sl, Direction pointSide, int amountMB) {
        int key = getNetworkKey();

        IFluidHandler dst = getAttachedFluidHandler(sl, worldPosition, pointSide);
        if (DBG_TRANSFER) {
            System.out.println("[QLF][DBG] point@" + worldPosition + " side=" + pointSide
                    + " key=" + key + " enabled=" + enabled
                    + " plugMask=" + Integer.toBinaryString(plugMask)
                    + " pointMask=" + Integer.toBinaryString(pointMask)
                    + " disabled=" + Integer.toBinaryString(disabledMask));
            System.out.println("[QLF][DBG] dst=" + (dst == null ? "null" : ("tanks=" + dst.getTanks())));
        }
        if (dst == null) return;

        QuickLinkFluidNetworkManager mgr = QuickLinkFluidNetworkManager.get(sl);
        List<QuickLinkFluidNetworkManager.GlobalPosRef> plugs = mgr.getPlugsSnapshot(key);
        if (DBG_TRANSFER) System.out.println("[QLF][DBG] plugs=" + plugs.size());
        if (plugs.isEmpty()) return;

        int pIdx = dirIndex(pointSide);
        int start = rrIndexBySide[pIdx];

        for (int i = 0; i < plugs.size(); i++) {
            int idx = (start + i) % plugs.size();
            QuickLinkFluidNetworkManager.GlobalPosRef ref = plugs.get(idx);
            ServerLevel plugLevel = sl.getServer().getLevel(ref.dimension());
            if (plugLevel == null) continue;

            BlockPos plugPos = ref.pos();
            BlockEntity other = plugLevel.getBlockEntity(plugPos);
            if (!(other instanceof FluidPlugBlockEntity plugBe)) continue;
            if (!plugBe.enabled) continue;

            if (DBG_TRANSFER) {
                boolean anyPlugSide = (plugBe.plugMask & ~plugBe.disabledMask) != 0;
                System.out.println("[QLF][DBG] plug@" + plugPos + " plugEnabledAny=" + anyPlugSide + " enabled=" + plugBe.enabled);
            }

            boolean moved = false;

            // iterate all enabled PLUG sides of that BE
            for (Direction plugSide : Direction.values()) {
                if (!plugBe.isPlugEnabled(plugSide)) continue;

                if (plugBe.isInfiniteWater(plugSide)) {
                    if (pushInfiniteWater(dst, plugBe, plugSide)) {
                        moved = true;
                        break;
                    }
                    continue;
                }

                IFluidHandler src = getAttachedFluidHandler(plugLevel, plugPos, plugSide);
                if (DBG_TRANSFER) System.out.println("[QLF][DBG] src=" + (src == null ? "null" : ("tanks=" + src.getTanks()))
                        + " at plug@" + plugPos + " side=" + plugSide);
                if (src == null) continue;

                if (moveFluid(src, dst, amountMB)) {
                    moved = true;
                    break;
                }
            }

            if (moved) {
                rrIndexBySide[pIdx] = (idx + 1) % plugs.size();
                setChanged(); // persist RR
                return;
            }
        }

        rrIndexBySide[pIdx] = (rrIndexBySide[pIdx] + 1) % plugs.size();
        setChanged();
    }

    private static boolean pushInfiniteWater(IFluidHandler dst, FluidPlugBlockEntity plugBe, Direction plugSide) {
        int idx = dirIndex(plugSide);

        long rateMb = QuickLinkConfig.FLUID_INFINITE_MB_PER_TICK.get();
        int maxChunk = QuickLinkConfig.FLUID_INFINITE_MAX_PUSH_PER_TICK.get();

        plugBe.waterAccumBySide[idx] += rateMb;

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
    private static IFluidHandler getAttachedFluidHandler(ServerLevel level, BlockPos selfPos, Direction side) {
        // Ищем соседний блок НА ЭТОЙ стороне
        BlockPos target = selfPos.relative(side);
        // А capability у соседа спрашиваем со стороны, которая СМОТРИТ НА НАС
        Direction targetFaceTowardUs = side.getOpposite();
        return level.getCapability(Capabilities.FluidHandler.BLOCK, target, targetFaceTowardUs);
    }

    /**
     * Move up to amountMB from src -> dst.
     * Returns true if anything actually moved.
     */
    private static boolean moveFluid(IFluidHandler src, IFluidHandler dst, int amountMB) {
        if (amountMB <= 0) return false;

        FluidStack canDrain = src.drain(amountMB, IFluidHandler.FluidAction.SIMULATE);
        if (DBG_TRANSFER) System.out.println("[QLF][DBG] drainSim=" + (canDrain.isEmpty() ? "EMPTY" : (canDrain.getAmount() + " " + canDrain.getFluid())));
        if (canDrain.isEmpty() || canDrain.getAmount() <= 0) return false;

        int canFill = dst.fill(canDrain, IFluidHandler.FluidAction.SIMULATE);
        if (DBG_TRANSFER) System.out.println("[QLF][DBG] fillSim=" + canFill);
        if (canFill <= 0) return false;

        int toMove = Math.min(canDrain.getAmount(), canFill);
        if (toMove <= 0) return false;

        FluidStack drained = src.drain(toMove, IFluidHandler.FluidAction.EXECUTE);
        if (drained.isEmpty() || drained.getAmount() <= 0) return false;

        int filled = dst.fill(drained, IFluidHandler.FluidAction.EXECUTE);
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
        public FluidStack getFluidInTank(int tank) { return FluidStack.EMPTY; }

        @Override
        public int getTankCapacity(int tank) { return Integer.MAX_VALUE; }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return owner.isPlugEnabled(side);
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

    // ---------------- NBT ----------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        tag.putInt(QuickLinkNbt.COLORS, colors.pack());
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

        int defaultPackedColors = QuickLinkColors.unset().pack();
        int packed = tag.contains(QuickLinkNbt.COLORS, Tag.TAG_INT)
                ? tag.getInt(QuickLinkNbt.COLORS)
                : defaultPackedColors;
        colors = QuickLinkColors.unpack(packed);

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

        // invariant: prefer POINT if both set (old/bad data)
        int both = plugMask & pointMask;
        if (both != 0) plugMask &= ~both;

        // keep infinite-water only on PLUG sides
        infiniteWaterMask &= plugMask;
    }
}
