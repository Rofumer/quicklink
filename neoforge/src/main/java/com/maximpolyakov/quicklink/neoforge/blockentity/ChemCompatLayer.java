package com.maximpolyakov.quicklink.neoforge.blockentity;

import com.maximpolyakov.quicklink.neoforge.network.QuickLinkChemicalNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;
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

    public static final boolean LOADED = ModList.get().isLoaded("mekanism");

    private ChemCompatLayer() {}

    // ---- neighbor handler lookup ----

    /**
     * Returns an Object that is actually an IChemicalHandler wrapping the
     * ISidedChemicalHandler at pos, or null if no such handler exists.
     * face = the face of the target block that faces toward our block.
     */
    @Nullable
    public static Object getNeighborHandler(Level level, BlockPos pos, Direction face) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof mekanism.api.chemical.ISidedChemicalHandler h)) return null;
        return new SidedWrapper(h, face);
    }

    // ---- active push (serverTick) ----

    public static boolean tryPushToNeighbor(ChemicalPlugBlockEntity be, ServerLevel sl,
                                             Direction plugSide, long amount) {
        int networkKey = be.getNetworkKey(plugSide);

        BlockPos dstPos  = be.getBlockPos().relative(plugSide);
        Direction dstFace = plugSide.getOpposite();
        BlockEntity dstBe = sl.getBlockEntity(dstPos);
        if (dstBe instanceof ChemicalPlugBlockEntity) return false;
        Object dst = getNeighborHandler(sl, dstPos, dstFace);
        if (dst == null) return false;

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
        if (sources.isEmpty()) return false;

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
            if (moveChemicals(src, dst, amount)) {
                rr[pIdx] = (idx + 1) % sources.size();
                be.setChanged();
                return true;
            }
        }

        rr[pIdx] = (rr[pIdx] + 1) % sources.size();
        be.setChanged();
        return false;
    }

    // ---- core move primitive ----

    static boolean moveChemicals(Object srcObj, Object dstObj, long amount) {
        mekanism.api.chemical.IChemicalHandler src = (mekanism.api.chemical.IChemicalHandler) srcObj;
        mekanism.api.chemical.IChemicalHandler dst = (mekanism.api.chemical.IChemicalHandler) dstObj;

        mekanism.api.chemical.ChemicalStack canDrain =
            src.extractChemical(amount, mekanism.api.Action.SIMULATE);
        if (canDrain.isEmpty()) return false;

        mekanism.api.chemical.ChemicalStack leftover =
            dst.insertChemical(canDrain, mekanism.api.Action.SIMULATE);
        long insertable = canDrain.getAmount() - leftover.getAmount();
        if (insertable <= 0) return false;

        mekanism.api.chemical.ChemicalStack drained =
            src.extractChemical(insertable, mekanism.api.Action.EXECUTE);
        if (drained.isEmpty()) return false;

        mekanism.api.chemical.ChemicalStack remaining =
            dst.insertChemical(drained, mekanism.api.Action.EXECUTE);
        return (drained.getAmount() - remaining.getAmount()) > 0;
    }

    // ---- SidedWrapper inner class (loaded lazily by JVM) ----

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
