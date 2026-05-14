package com.maximpolyakov.quicklink.neoforge.blockentity;

import com.maximpolyakov.quicklink.neoforge.config.QuickLinkConfig;
import com.maximpolyakov.quicklink.neoforge.UpgradeTier;
import com.maximpolyakov.quicklink.QuickLinkColors;
import com.maximpolyakov.quicklink.QuickLinkNbt;
import com.maximpolyakov.quicklink.neoforge.QuickLinkNeoForge;
import com.maximpolyakov.quicklink.neoforge.network.QuickLinkNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class ItemPlugBlockEntity extends BlockEntity {

    // ===== SPEED =====
    static int period = QuickLinkConfig.ITEM_TICK_PERIOD.get();

    // ---- upgrade ----
    private int upgradeTier = 0;

    // ---- per-side roles ----
    private int plugMask = 0;
    private int pointMask = 0;
    private int disabledMask = 0;

    private final int[] rrIndexBySide = new int[6];

    private final QuickLinkColors[] sideColors = new QuickLinkColors[6];
    private boolean enabled = true;

    private java.util.Set<Integer> lastRegPlugKeys = new java.util.HashSet<>();
    private java.util.Set<Integer> lastRegPointKeys = new java.util.HashSet<>();
    private final ResourceHandler<ItemResource>[] sideCapabilities;

    @SuppressWarnings("unchecked")
    public ItemPlugBlockEntity(BlockPos pos, BlockState state) {
        super(QuickLinkNeoForge.ITEM_PLUG_BE.get(), pos, state);
        sideCapabilities = new ResourceHandler[6];
        for (Direction side : Direction.values()) {
            sideCapabilities[dirIndex(side)] = new SideItemHandler(this, side);
            sideColors[dirIndex(side)] = QuickLinkColors.unset();
        }
    }

    // ------------------------------------------------
    // helpers
    // ------------------------------------------------

    private static int bit(Direction d) {
        return 1 << d.get3DDataValue();
    }

    private static int clampMask6(int m) {
        return m & 0b111111;
    }

    private static int dirIndex(Direction d) {
        return Math.max(0, Math.min(5, d.get3DDataValue()));
    }

    // ------------------------------------------------
    // upgrade tier
    // ------------------------------------------------

    public int getUpgradeTier() { return upgradeTier; }

    public void setUpgradeTier(int tier) {
        upgradeTier = Math.max(0, Math.min(UpgradeTier.MAX_TIER, tier));
        setChangedAndSync();
    }

    public int effectiveMoveBatch() {
        return QuickLinkConfig.ITEM_MOVE_BATCH.get() * UpgradeTier.multiplier(upgradeTier);
    }

    // ------------------------------------------------
    // colors / network
    // ------------------------------------------------

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

    public void setColor(Direction side, int slot, byte colorId) {
        int idx = dirIndex(side);
        int oldKey = sideColors[idx].networkKey();
        sideColors[idx] = sideColors[idx].with(slot, colorId);
        setChangedAndSync();

        if (oldKey != sideColors[idx].networkKey()) {
            syncRegistration();
        }
    }

    // ------------------------------------------------
    // roles
    // ------------------------------------------------

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
        return (disabledMask & bit(side)) == 0;
    }

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

    // ------------------------------------------------
    // lifecycle
    // ------------------------------------------------

    private void setChangedAndSync() {
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) syncRegistration();
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide()) unregisterFromManager();
        super.setRemoved();
    }

    // ------------------------------------------------
    // registration
    // ------------------------------------------------

    private void unregisterFromManager() {
        if (!(level instanceof ServerLevel sl)) return;
        QuickLinkNetworkManager mgr = QuickLinkNetworkManager.get(sl);

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

        QuickLinkNetworkManager mgr = QuickLinkNetworkManager.get(sl);
        for (int key : plugKeys) mgr.registerPlug(sl, key, worldPosition);
        for (int key : pointKeys) mgr.registerPoint(sl, key, worldPosition);

        lastRegPlugKeys = plugKeys;
        lastRegPointKeys = pointKeys;
    }

    // ------------------------------------------------
    // ticking
    // ------------------------------------------------

    public static void serverTick(Level level, BlockPos pos, BlockState state, ItemPlugBlockEntity be) {
        if (!(level instanceof ServerLevel sl)) return;
        if (!be.enabled) return;

        long gt = sl.getGameTime();
        if ((gt % period) != 0L) return;

        for (Direction side : Direction.values()) {
            if (be.isPlugEnabled(side)) {
                be.tryPushOnce(sl, side);
            }
        }
    }

    @Nullable
    public ResourceHandler<ItemResource> getExternalItemHandler(@Nullable Direction side) {
        if (side == null) return null;
        if (!isSideEnabled(side)) return null;
        if (getRole(side) == SideRole.NONE) return null;
        return sideCapabilities[dirIndex(side)];
    }

    private int receiveIntoNetwork(Direction inputSide, ItemResource resource, int amount, TransactionContext ctx) {
        if (resource.isEmpty() || amount <= 0 || !isPointEnabled(inputSide) || !(level instanceof ServerLevel sl)) return 0;

        QuickLinkNetworkManager mgr = QuickLinkNetworkManager.get(sl);
        int networkKey = getNetworkKey(inputSide);
        List<QuickLinkNetworkManager.GlobalPosRef> plugs = mgr.getPlugsSnapshot(networkKey);
        if (plugs.isEmpty()) return 0;

        int moved = 0;
        int start = rrIndexBySide[dirIndex(inputSide)];

        for (int i = 0; i < plugs.size() && moved < amount; i++) {
            int idx = (start + i) % plugs.size();
            QuickLinkNetworkManager.GlobalPosRef ref = plugs.get(idx);
            ServerLevel plugLevel = sl.getServer().getLevel(ref.dimension());
            if (plugLevel == null) continue;

            BlockEntity other = plugLevel.getBlockEntity(ref.pos());
            if (!(other instanceof ItemPlugBlockEntity plugBe) || !plugBe.enabled) continue;

            for (Direction plugSide : Direction.values()) {
                if (!plugBe.isPlugEnabled(plugSide) || plugBe.getNetworkKey(plugSide) != networkKey) continue;
                ResourceHandler<ItemResource> dst = getAttachedHandler(plugLevel, ref.pos(), plugSide);
                if (dst == null) continue;

                int toInsert = amount - moved;
                int inserted = dst.insert(resource, toInsert, ctx);
                if (inserted > 0) {
                    moved += inserted;
                    rrIndexBySide[dirIndex(inputSide)] = (idx + 1) % plugs.size();
                }
                if (moved >= amount) break;
            }
        }

        return moved;
    }

    private int extractFromNetwork(Direction outputSide, ItemResource resource, int amount, TransactionContext ctx) {
        if (amount <= 0 || !isPlugEnabled(outputSide) || !(level instanceof ServerLevel sl)) return 0;

        QuickLinkNetworkManager mgr = QuickLinkNetworkManager.get(sl);
        int networkKey = getNetworkKey(outputSide);
        List<QuickLinkNetworkManager.GlobalPosRef> points = mgr.getPointsSnapshot(networkKey);
        if (points.isEmpty()) return 0;

        int start = rrIndexBySide[dirIndex(outputSide)];

        for (int i = 0; i < points.size(); i++) {
            int idx = (start + i) % points.size();
            QuickLinkNetworkManager.GlobalPosRef ref = points.get(idx);
            ServerLevel pointLevel = sl.getServer().getLevel(ref.dimension());
            if (pointLevel == null) continue;

            BlockEntity other = pointLevel.getBlockEntity(ref.pos());
            if (!(other instanceof ItemPlugBlockEntity pointBe) || !pointBe.enabled) continue;

            for (Direction pointSide : Direction.values()) {
                if (!pointBe.isPointEnabled(pointSide) || pointBe.getNetworkKey(pointSide) != networkKey) continue;

                ResourceHandler<ItemResource> src = getAttachedHandler(pointLevel, ref.pos(), pointSide);
                if (src == null) continue;

                int extracted = 0;
                if (resource.isEmpty()) {
                    for (int slot = 0; slot < src.size(); slot++) {
                        ItemResource res = src.getResource(slot);
                        if (res.isEmpty()) continue;
                        extracted = src.extract(slot, res, amount, ctx);
                        if (extracted > 0) break;
                    }
                } else {
                    extracted = src.extract(resource, amount, ctx);
                }

                if (extracted > 0) {
                    rrIndexBySide[dirIndex(outputSide)] = (idx + 1) % points.size();
                    return extracted;
                }
            }
        }

        return 0;
    }

    private void tryPushOnce(ServerLevel sl, Direction plugSide) {
        ResourceHandler<ItemResource> dst = getAttachedHandler(sl, worldPosition, plugSide);
        if (dst == null) return;

        QuickLinkNetworkManager mgr = QuickLinkNetworkManager.get(sl);
        int networkKey = getNetworkKey(plugSide);

        record Src(ServerLevel lvl, BlockPos pos, Direction dir) {}
        List<Src> sources = new ArrayList<>();
        for (QuickLinkNetworkManager.GlobalPosRef ref : mgr.getPointsSnapshot(networkKey)) {
            ServerLevel pl = sl.getServer().getLevel(ref.dimension());
            if (pl == null) continue;
            BlockEntity be = pl.getBlockEntity(ref.pos());
            if (!(be instanceof ItemPlugBlockEntity pBe) || !pBe.enabled) continue;
            for (Direction d : Direction.values()) {
                if (!pBe.isPointEnabled(d) || pBe.getNetworkKey(d) != networkKey) continue;
                sources.add(new Src(pl, ref.pos(), d));
            }
        }
        if (sources.isEmpty()) return;

        int pIdx = dirIndex(plugSide);
        int start = rrIndexBySide[pIdx] % sources.size();

        for (int i = 0; i < sources.size(); i++) {
            int idx = (start + i) % sources.size();
            Src s = sources.get(idx);
            ResourceHandler<ItemResource> src = getAttachedHandler(s.lvl(), s.pos(), s.dir());
            if (src == null) continue;

            int moved = moveItems(src, dst, effectiveMoveBatch());
            if (moved > 0) {
                rrIndexBySide[pIdx] = (idx + 1) % sources.size();
                setChanged();
                return;
            }
        }

        rrIndexBySide[pIdx] = (rrIndexBySide[pIdx] + 1) % sources.size();
        setChanged();
    }

    // ------------------------------------------------
    // handler attach
    // ------------------------------------------------

    @Nullable
    private static ResourceHandler<ItemResource> getAttachedHandler(ServerLevel level, BlockPos selfPos, Direction side) {
        BlockPos target = selfPos.relative(side);
        Direction targetFaceTowardUs = side.getOpposite();
        ResourceHandler<ItemResource> handler = level.getCapability(Capabilities.Item.BLOCK, target, targetFaceTowardUs);
        if (handler != null) return handler;

        Container container = HopperBlockEntity.getContainerAt(level, target);
        return container == null ? null : VanillaContainerWrapper.of(container);
    }

    // ------------------------------------------------
    // move items
    // ------------------------------------------------

    private static int moveItems(ResourceHandler<ItemResource> src, ResourceHandler<ItemResource> dst, int count) {
        if (count <= 0) return 0;
        int moved = 0;

        try (var tx = Transaction.openRoot()) {
            outer:
            for (int i = 0; i < src.size() && moved < count; i++) {
                ItemResource res = src.getResource(i);
                if (res.isEmpty()) continue;

                int toMove = Math.min(count - moved, src.getAmountAsInt(i));
                int canInsert = dst.insert(res, toMove, tx);
                if (canInsert <= 0) continue;

                int extracted = src.extract(i, res, canInsert, tx);
                moved += extracted;
            }
            if (moved > 0) tx.commit();
        }

        return moved;
    }

    // ------------------------------------------------
    // capability handler (exposed to external automation)
    // ------------------------------------------------

    private static final class SideItemHandler implements ResourceHandler<ItemResource> {
        private final ItemPlugBlockEntity owner;
        private final Direction side;

        private SideItemHandler(ItemPlugBlockEntity owner, Direction side) {
            this.owner = owner;
            this.side = side;
        }

        @Override
        public int size() { return 1; }

        @Override
        public ItemResource getResource(int slot) { return ItemResource.EMPTY; }

        @Override
        public long getAmountAsLong(int slot) { return 0L; }

        @Override
        public long getCapacityAsLong(int slot, ItemResource resource) { return 64L; }

        @Override
        public boolean isValid(int slot, ItemResource resource) {
            return slot == 0 && !resource.isEmpty() && owner.isPointEnabled(side);
        }

        @Override
        public int insert(int slot, ItemResource resource, int maxAmount, TransactionContext ctx) {
            if (slot != 0 || resource.isEmpty() || maxAmount <= 0) return 0;
            return owner.receiveIntoNetwork(side, resource, maxAmount, ctx);
        }

        @Override
        public int extract(int slot, ItemResource resource, int maxAmount, TransactionContext ctx) {
            if (slot != 0 || maxAmount <= 0) return 0;
            return owner.extractFromNetwork(side, resource, maxAmount, ctx);
        }
    }

    // ------------------------------------------------
    // NBT
    // ------------------------------------------------

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
            int packed = input.getIntOr(QuickLinkNbt.COLORS, QuickLinkColors.unset().pack());
            QuickLinkColors legacy = QuickLinkColors.unpack(packed);
            for (int i = 0; i < 6; i++) sideColors[i] = legacy;
        }

        enabled = input.getBooleanOr(QuickLinkNbt.ENABLED, true);

        plugMask = clampMask6(input.getIntOr("ql_plug_mask", 0));
        pointMask = clampMask6(input.getIntOr("ql_point_mask", 0));
        if (input.getInt("ql_schema").isEmpty()) {
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
    public CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
        return this.saveCustomOnly(registries);
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
