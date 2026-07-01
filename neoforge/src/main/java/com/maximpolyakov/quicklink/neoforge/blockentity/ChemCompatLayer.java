package com.maximpolyakov.quicklink.neoforge.blockentity;

import com.maximpolyakov.quicklink.neoforge.network.QuickLinkChemicalNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Soft-dependency bridge for Mekanism chemical handlers.
 *
 * All references to Mekanism classes live inside method bodies so the JVM only
 * resolves them when those methods are actually called — guarded by LOADED.
 * Inner class SideChemHandler is a separate class file; JVM loads it lazily.
 */
public final class ChemCompatLayer {

    // Lazily-resolved BlockCapability for mekanism:chemical_handler (sided).
    // BlockCapability.createSided deduplicates by (ResourceLocation, type) so this
    // returns the same instance Mekanism registered — no direct reference to
    // mekanism.common.capabilities.Capabilities needed.
    // Method body is only executed when LOADED is true, so Mekanism classes resolve safely.
    @SuppressWarnings("unchecked")
    static BlockCapability<mekanism.api.chemical.IChemicalHandler, @Nullable Direction> chemicalBlockCap() {
        return (BlockCapability<mekanism.api.chemical.IChemicalHandler, @Nullable Direction>)
            BlockCapability.createSided(
                ResourceLocation.fromNamespaceAndPath("mekanism", "chemical_handler"),
                mekanism.api.chemical.IChemicalHandler.class
            );
    }

    private ChemCompatLayer() {}

    // ---- capability registration ----

    /**
     * Registers the mekanism:chemical_handler capability for ChemicalPlugBlockEntity so that
     * Pressurized Tubes (and other Mekanism pipes) recognize the plug as a valid acceptor,
     * update their AcceptorCache, and show a visual connection arm.
     *
     * The exposed handler has 0 tanks — it signals presence only. Actual chemical
     * movement is handled by the plug's serverTick via tryPushToNeighbor.
     */
    public static void registerPlugCapability(RegisterCapabilitiesEvent event,
                                               BlockEntityType<ChemicalPlugBlockEntity> type) {
        event.registerBlockEntity(chemicalBlockCap(), type, (be, side) -> {
            if (side == null) return null;
            if (be.isPlugEnabled(side) || be.isPointEnabled(side)) return PlugSideHandler.INSTANCE;
            return null;
        });
    }

    // ---- neighbor handler lookup ----

    /**
     * Returns an Object that is actually an IChemicalHandler for the block at pos,
     * or null if no chemical handler is available.
     * face = the face of the target block that faces toward our block.
     *
     * Prefers the NeoForge BlockCapability so Pressurized Tubes (and any other
     * blocks that register the cap without implementing ISidedChemicalHandler
     * directly on the BE) are supported. Falls back to ISidedChemicalHandler
     * instanceof for safety.
     */
    @Nullable
    public static Object getNeighborHandler(Level level, BlockPos pos, Direction face) {
        mekanism.api.chemical.IChemicalHandler cap = level.getCapability(chemicalBlockCap(), pos, face);
        if (cap != null) return cap;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof mekanism.api.chemical.ISidedChemicalHandler h) return new SidedWrapper(h, face);
        return null;
    }

    // ---- active push (serverTick) ----

    public static long tryPushToNeighbor(ChemicalPlugBlockEntity be, ServerLevel sl,
                                          Direction plugSide, long amount) {
        int networkKey = be.getNetworkKey(plugSide);

        BlockPos dstPos  = be.getBlockPos().relative(plugSide);
        Direction dstFace = plugSide.getOpposite();
        BlockEntity dstBe = sl.getBlockEntity(dstPos);
        if (dstBe instanceof ChemicalPlugBlockEntity) return 0;
        Object dst = getNeighborHandler(sl, dstPos, dstFace);
        if (dst == null) return 0;

        QuickLinkChemicalNetworkManager mgr = QuickLinkChemicalNetworkManager.get(sl);

        record Src(ChemicalPlugBlockEntity be, Direction dir) {}
        List<Src> sources = new ArrayList<>();
        for (QuickLinkChemicalNetworkManager.GlobalPosRef ref : mgr.getPointsSnapshot(networkKey)) {
            ServerLevel pl = sl.getServer().getLevel(ref.dimension());
            if (pl == null) continue;
            BlockEntity rawBe = pl.getBlockEntity(ref.pos());
            if (!(rawBe instanceof ChemicalPlugBlockEntity pBe) || !pBe.isEnabled()) continue;
            for (Direction d : Direction.values()) {
                if (!pBe.isPointEnabled(d) || pBe.getNetworkKey(d) != networkKey) continue;
                sources.add(new Src(pBe, d));
            }
        }
        if (sources.isEmpty()) return 0;

        int pIdx = ChemicalPlugBlockEntity.dirIndex(plugSide);
        int[] rr  = be.getRrIndexBySide();
        int start = rr[pIdx] % sources.size();

        for (int i = 0; i < sources.size(); i++) {
            int idx = (start + i) % sources.size();
            Src s   = sources.get(idx);
            BlockPos srcPos  = s.be().getBlockPos().relative(s.dir());
            Direction srcFace = s.dir().getOpposite();
            Level srcLevel = s.be().getLevel();
            if (srcLevel == null) continue;
            BlockEntity srcBe = srcLevel.getBlockEntity(srcPos);
            if (srcBe instanceof ChemicalPlugBlockEntity) continue;
            Object src = getNeighborHandler(srcLevel, srcPos, srcFace);
            if (src == null) continue;
            long moved = moveChemicals(src, dst, amount);
            if (moved > 0) {
                rr[pIdx] = (idx + 1) % sources.size();
                be.setChanged();
                s.be().addPendingReceivedMb(moved);
                return moved;
            }
        }

        rr[pIdx] = (rr[pIdx] + 1) % sources.size();
        be.setChanged();
        return 0;
    }

    // ---- core move primitive ----

    static long moveChemicals(Object srcObj, Object dstObj, long amount) {
        mekanism.api.chemical.IChemicalHandler src = (mekanism.api.chemical.IChemicalHandler) srcObj;
        mekanism.api.chemical.IChemicalHandler dst = (mekanism.api.chemical.IChemicalHandler) dstObj;

        mekanism.api.chemical.ChemicalStack canDrain =
            src.extractChemical(amount, mekanism.api.Action.SIMULATE);
        if (canDrain.isEmpty()) return 0;

        mekanism.api.chemical.ChemicalStack leftover =
            dst.insertChemical(canDrain, mekanism.api.Action.SIMULATE);
        long insertable = canDrain.getAmount() - leftover.getAmount();
        if (insertable <= 0) return 0;

        mekanism.api.chemical.ChemicalStack drained =
            src.extractChemical(insertable, mekanism.api.Action.EXECUTE);
        if (drained.isEmpty()) return 0;

        mekanism.api.chemical.ChemicalStack remaining =
            dst.insertChemical(drained, mekanism.api.Action.EXECUTE);
        return drained.getAmount() - remaining.getAmount();
    }

    // ---- Inner classes — separate class files, loaded lazily by JVM ----

    /**
     * Zero-tank handler exposed by Chemical Plug to Mekanism pipes.
     * Non-null return makes Pressurized Tube's AcceptorCache accept the plug as a
     * valid neighbour and expose the tube's own tanks back toward the plug.
     */
    static final class PlugSideHandler implements mekanism.api.chemical.IChemicalHandler {
        static final PlugSideHandler INSTANCE = new PlugSideHandler();
        private PlugSideHandler() {}

        @Override public int getChemicalTanks() { return 0; }
        @Override public mekanism.api.chemical.ChemicalStack getChemicalInTank(int tank) { return mekanism.api.chemical.ChemicalStack.EMPTY; }
        @Override public void setChemicalInTank(int tank, mekanism.api.chemical.ChemicalStack stack) {}
        @Override public long getChemicalTankCapacity(int tank) { return 0; }
        @Override public boolean isValid(int tank, mekanism.api.chemical.ChemicalStack stack) { return false; }
        @Override public mekanism.api.chemical.ChemicalStack insertChemical(int tank, mekanism.api.chemical.ChemicalStack stack, mekanism.api.Action action) { return stack; }
        @Override public mekanism.api.chemical.ChemicalStack extractChemical(int tank, long amount, mekanism.api.Action action) { return mekanism.api.chemical.ChemicalStack.EMPTY; }
    }

    static final class SidedWrapper implements mekanism.api.chemical.IChemicalHandler {
        private final mekanism.api.chemical.ISidedChemicalHandler delegate;
        private final Direction face;

        SidedWrapper(mekanism.api.chemical.ISidedChemicalHandler delegate, Direction face) {
            this.delegate = delegate;
            this.face = face;
        }

        @Override
        public int getChemicalTanks() {
            return delegate.getCountChemicalTanks(face);
        }

        @Override
        public mekanism.api.chemical.ChemicalStack getChemicalInTank(int tank) {
            return delegate.getChemicalInTank(tank, face);
        }

        @Override
        public void setChemicalInTank(int tank, mekanism.api.chemical.ChemicalStack stack) {
            delegate.setChemicalInTank(tank, stack, face);
        }

        @Override
        public long getChemicalTankCapacity(int tank) {
            return delegate.getChemicalTankCapacity(tank, face);
        }

        @Override
        public boolean isValid(int tank, mekanism.api.chemical.ChemicalStack stack) {
            return delegate.isValid(tank, stack, face);
        }

        @Override
        public mekanism.api.chemical.ChemicalStack insertChemical(int tank,
                mekanism.api.chemical.ChemicalStack stack, mekanism.api.Action action) {
            return delegate.insertChemical(tank, stack, face, action);
        }

        @Override
        public mekanism.api.chemical.ChemicalStack extractChemical(int tank, long amount,
                mekanism.api.Action action) {
            return delegate.extractChemical(tank, amount, face, action);
        }
    }
}
