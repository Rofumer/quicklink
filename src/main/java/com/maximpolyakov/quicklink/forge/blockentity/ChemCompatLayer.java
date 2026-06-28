package com.maximpolyakov.quicklink.forge.blockentity;

import com.maximpolyakov.quicklink.forge.network.QuickLinkChemicalNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Soft-dependency bridge for Mekanism gas handlers (Forge 1.20.1).
 * Only references Mekanism types inside method bodies — JVM resolves them lazily.
 * Supports Gas only (Mekanism 10.4.x for 1.20.1 has separate chemical types).
 */
public final class ChemCompatLayer {

    private ChemCompatLayer() {}

    // ---- capability accessor (lazy, called only when Mekanism is loaded) ----

    @SuppressWarnings("unchecked")
    static Capability<mekanism.api.chemical.gas.IGasHandler> gasCap() {
        return mekanism.api.MekanismAPI.GAS_HANDLER_CAPABILITY;
    }

    static boolean isGasCap(Capability<?> cap) {
        return cap == gasCap();
    }

    // ---- neighbor handler lookup ----

    @Nullable
    public static Object getNeighborHandler(Level level, BlockPos pos, Direction face) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return null;
        net.minecraftforge.common.util.LazyOptional<mekanism.api.chemical.gas.IGasHandler> lo =
                be.getCapability(gasCap(), face);
        if (lo.isPresent()) return lo.orElse(null);
        if (be instanceof mekanism.api.chemical.gas.ISidedGasHandler h) return new SidedWrapper(h, face);
        return null;
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
            Src s = sources.get(idx);
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
        mekanism.api.chemical.gas.IGasHandler src = (mekanism.api.chemical.gas.IGasHandler) srcObj;
        mekanism.api.chemical.gas.IGasHandler dst = (mekanism.api.chemical.gas.IGasHandler) dstObj;

        mekanism.api.chemical.gas.GasStack canDrain = src.extractChemical(amount, mekanism.api.Action.SIMULATE);
        if (canDrain.isEmpty()) return false;

        mekanism.api.chemical.gas.GasStack leftover = dst.insertChemical(canDrain, mekanism.api.Action.SIMULATE);
        long insertable = canDrain.getAmount() - leftover.getAmount();
        if (insertable <= 0) return false;

        mekanism.api.chemical.gas.GasStack drained = src.extractChemical(insertable, mekanism.api.Action.EXECUTE);
        if (drained.isEmpty()) return false;

        mekanism.api.chemical.gas.GasStack remaining = dst.insertChemical(drained, mekanism.api.Action.EXECUTE);
        return (drained.getAmount() - remaining.getAmount()) > 0;
    }

    // ---- Inner classes — loaded lazily by JVM ----

    /** Zero-tank handler exposed to Mekanism pipes so they see the plug as a valid neighbour. */
    static final class PlugSideHandler implements mekanism.api.chemical.gas.IGasHandler {
        static final PlugSideHandler INSTANCE = new PlugSideHandler();
        private PlugSideHandler() {}

        @Override public int getTanks() { return 0; }
        @Override public mekanism.api.chemical.gas.GasStack getChemicalInTank(int t) { return mekanism.api.chemical.gas.GasStack.EMPTY; }
        @Override public void setChemicalInTank(int t, mekanism.api.chemical.gas.GasStack s) {}
        @Override public long getChemicalTankCapacity(int t) { return 0; }
        @Override public boolean isValid(int t, mekanism.api.chemical.gas.GasStack s) { return false; }
        @Override public mekanism.api.chemical.gas.GasStack insertChemical(int t, mekanism.api.chemical.gas.GasStack s, mekanism.api.Action a) { return s; }
        @Override public mekanism.api.chemical.gas.GasStack extractChemical(int t, long amount, mekanism.api.Action a) { return mekanism.api.chemical.gas.GasStack.EMPTY; }
    }

    static final class SidedWrapper implements mekanism.api.chemical.gas.IGasHandler {
        private final mekanism.api.chemical.gas.ISidedGasHandler delegate;
        private final Direction face;

        SidedWrapper(mekanism.api.chemical.gas.ISidedGasHandler delegate, Direction face) {
            this.delegate = delegate; this.face = face;
        }

        @Override public int getTanks() { return delegate.getTanks(face); }
        @Override public mekanism.api.chemical.gas.GasStack getChemicalInTank(int t) { return delegate.getChemicalInTank(t, face); }
        @Override public void setChemicalInTank(int t, mekanism.api.chemical.gas.GasStack s) { delegate.setChemicalInTank(t, s, face); }
        @Override public long getChemicalTankCapacity(int t) { return delegate.getChemicalTankCapacity(t, face); }
        @Override public boolean isValid(int t, mekanism.api.chemical.gas.GasStack s) { return delegate.isValid(t, s, face); }
        @Override public mekanism.api.chemical.gas.GasStack insertChemical(int t, mekanism.api.chemical.gas.GasStack s, mekanism.api.Action a) { return delegate.insertChemical(t, s, face, a); }
        @Override public mekanism.api.chemical.gas.GasStack extractChemical(int t, long amount, mekanism.api.Action a) { return delegate.extractChemical(t, amount, face, a); }
    }
}
