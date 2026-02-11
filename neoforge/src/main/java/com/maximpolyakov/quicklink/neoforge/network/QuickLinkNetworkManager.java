package com.maximpolyakov.quicklink.neoforge.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

public class QuickLinkNetworkManager extends SavedData {

    private static final String DATA_NAME = "quicklink_network_mgr";

    // key -> plugs/points positions
    private final Map<Integer, Set<BlockPos>> plugsByKey = new HashMap<>();
    private final Map<Integer, Set<BlockPos>> pointsByKey = new HashMap<>();

    public static QuickLinkNetworkManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(QuickLinkNetworkManager::new, QuickLinkNetworkManager::load),
                DATA_NAME
        );
    }

    // -------- register/unregister --------

    public void registerPlug(int key, BlockPos pos) {
        plugsByKey.computeIfAbsent(key, k -> new HashSet<>()).add(pos.immutable());
        setDirty();
    }

    public void unregisterPlug(int key, BlockPos pos) {
        Set<BlockPos> set = plugsByKey.get(key);
        if (set == null) return;
        if (set.remove(pos)) setDirty();
        if (set.isEmpty()) plugsByKey.remove(key);
    }

    public void registerPoint(int key, BlockPos pos) {
        pointsByKey.computeIfAbsent(key, k -> new HashSet<>()).add(pos.immutable());
        setDirty();
    }

    public void unregisterPoint(int key, BlockPos pos) {
        Set<BlockPos> set = pointsByKey.get(key);
        if (set == null) return;
        if (set.remove(pos)) setDirty();
        if (set.isEmpty()) pointsByKey.remove(key);
    }

    /**
     * Snapshot PLUG positions by key.
     * Sorted for stable round-robin.
     */
    public List<BlockPos> getPlugsSnapshot(int key) {
        Set<BlockPos> set = plugsByKey.get(key);
        if (set == null || set.isEmpty()) return Collections.emptyList();

        ArrayList<BlockPos> out = new ArrayList<>(set);
        out.sort((a, b) -> {
            int c = Integer.compare(a.getX(), b.getX());
            if (c != 0) return c;
            c = Integer.compare(a.getY(), b.getY());
            if (c != 0) return c;
            return Integer.compare(a.getZ(), b.getZ());
        });
        return out;
    }

    // (optional) if you ever need it
    public List<BlockPos> getPointsSnapshot(int key) {
        Set<BlockPos> set = pointsByKey.get(key);
        if (set == null || set.isEmpty()) return Collections.emptyList();

        ArrayList<BlockPos> out = new ArrayList<>(set);
        out.sort((a, b) -> {
            int c = Integer.compare(a.getX(), b.getX());
            if (c != 0) return c;
            c = Integer.compare(a.getY(), b.getY());
            if (c != 0) return c;
            return Integer.compare(a.getZ(), b.getZ());
        });
        return out;
    }

    // -------- SavedData persistence --------

    @Override
    public net.minecraft.nbt.CompoundTag save(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        tag.put("plugs", saveMap(plugsByKey));
        tag.put("points", saveMap(pointsByKey));
        return tag;
    }

    public static QuickLinkNetworkManager load(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        QuickLinkNetworkManager mgr = new QuickLinkNetworkManager();
        mgr.plugsByKey.putAll(loadMap(tag.getCompound("plugs")));
        mgr.pointsByKey.putAll(loadMap(tag.getCompound("points")));
        return mgr;
    }

    private static net.minecraft.nbt.CompoundTag saveMap(Map<Integer, Set<BlockPos>> map) {
        net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
        for (Map.Entry<Integer, Set<BlockPos>> e : map.entrySet()) {
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            for (BlockPos p : e.getValue()) {
                net.minecraft.nbt.CompoundTag pt = new net.minecraft.nbt.CompoundTag();
                pt.putInt("x", p.getX());
                pt.putInt("y", p.getY());
                pt.putInt("z", p.getZ());
                list.add(pt);
            }
            root.put(Integer.toString(e.getKey()), list);
        }
        return root;
    }

    private static Map<Integer, Set<BlockPos>> loadMap(net.minecraft.nbt.CompoundTag root) {
        Map<Integer, Set<BlockPos>> out = new HashMap<>();
        for (String k : root.getAllKeys()) {
            int key;
            try { key = Integer.parseInt(k); }
            catch (NumberFormatException ignore) { continue; }

            net.minecraft.nbt.ListTag list = root.getList(k, net.minecraft.nbt.Tag.TAG_COMPOUND);
            HashSet<BlockPos> set = new HashSet<>();
            for (int i = 0; i < list.size(); i++) {
                net.minecraft.nbt.CompoundTag pt = list.getCompound(i);
                set.add(new BlockPos(pt.getInt("x"), pt.getInt("y"), pt.getInt("z")));
            }
            if (!set.isEmpty()) out.put(key, set);
        }
        return out;
    }
}
