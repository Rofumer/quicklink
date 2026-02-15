package com.maximpolyakov.quicklink.fabric.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

public class QuickLinkNetworkManager extends SavedData {

    private static final String DATA_NAME = "quicklink_network_mgr";

    // key -> plugs/points positions in all dimensions
    private final Map<Integer, Set<GlobalPosRef>> plugsByKey = new HashMap<>();
    private final Map<Integer, Set<GlobalPosRef>> pointsByKey = new HashMap<>();

    public static QuickLinkNetworkManager get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(
                        QuickLinkNetworkManager::new,
                        QuickLinkNetworkManager::load,
                        null
                ),
                DATA_NAME
        );
    }


    // -------- register/unregister --------

    public void registerPlug(ServerLevel level, int key, BlockPos pos) {
        plugsByKey.computeIfAbsent(key, k -> new HashSet<>()).add(GlobalPosRef.of(level, pos));
        setDirty();
    }

    public void unregisterPlug(ServerLevel level, int key, BlockPos pos) {
        Set<GlobalPosRef> set = plugsByKey.get(key);
        if (set == null) return;
        if (set.remove(GlobalPosRef.of(level, pos))) setDirty();
        if (set.isEmpty()) plugsByKey.remove(key);
    }

    public void registerPoint(ServerLevel level, int key, BlockPos pos) {
        pointsByKey.computeIfAbsent(key, k -> new HashSet<>()).add(GlobalPosRef.of(level, pos));
        setDirty();
    }

    public void unregisterPoint(ServerLevel level, int key, BlockPos pos) {
        Set<GlobalPosRef> set = pointsByKey.get(key);
        if (set == null) return;
        if (set.remove(GlobalPosRef.of(level, pos))) setDirty();
        if (set.isEmpty()) pointsByKey.remove(key);
    }

    /**
     * Snapshot PLUG positions by key.
     * Sorted for stable round-robin.
     */
    public List<GlobalPosRef> getPlugsSnapshot(int key) {
        Set<GlobalPosRef> set = plugsByKey.get(key);
        if (set == null || set.isEmpty()) return Collections.emptyList();

        ArrayList<GlobalPosRef> out = new ArrayList<>(set);
        out.sort((a, b) -> {
            int c = a.dimension.location().compareTo(b.dimension.location());
            if (c != 0) return c;
            c = Integer.compare(a.pos.getX(), b.pos.getX());
            if (c != 0) return c;
            c = Integer.compare(a.pos.getY(), b.pos.getY());
            if (c != 0) return c;
            return Integer.compare(a.pos.getZ(), b.pos.getZ());
        });
        return out;
    }

    public List<GlobalPosRef> getPointsSnapshot(int key) {
        Set<GlobalPosRef> set = pointsByKey.get(key);
        if (set == null || set.isEmpty()) return Collections.emptyList();

        ArrayList<GlobalPosRef> out = new ArrayList<>(set);
        out.sort((a, b) -> {
            int c = a.dimension.location().compareTo(b.dimension.location());
            if (c != 0) return c;
            c = Integer.compare(a.pos.getX(), b.pos.getX());
            if (c != 0) return c;
            c = Integer.compare(a.pos.getY(), b.pos.getY());
            if (c != 0) return c;
            return Integer.compare(a.pos.getZ(), b.pos.getZ());
        });
        return out;
    }

    // -------- SavedData persistence --------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("plugs", saveMap(plugsByKey));
        tag.put("points", saveMap(pointsByKey));
        return tag;
    }

    public static QuickLinkNetworkManager load(CompoundTag tag, HolderLookup.Provider registries) {
        QuickLinkNetworkManager mgr = new QuickLinkNetworkManager();
        mgr.plugsByKey.putAll(loadMap(tag.getCompound("plugs")));
        mgr.pointsByKey.putAll(loadMap(tag.getCompound("points")));
        return mgr;
    }

    private static CompoundTag saveMap(Map<Integer, Set<GlobalPosRef>> map) {
        CompoundTag root = new CompoundTag();
        for (Map.Entry<Integer, Set<GlobalPosRef>> e : map.entrySet()) {
            ListTag list = new ListTag();
            for (GlobalPosRef gp : e.getValue()) {
                CompoundTag pt = new CompoundTag();
                pt.putString("dim", gp.dimension.location().toString());
                pt.putInt("x", gp.pos.getX());
                pt.putInt("y", gp.pos.getY());
                pt.putInt("z", gp.pos.getZ());
                list.add(pt);
            }
            root.put(Integer.toString(e.getKey()), list);
        }
        return root;
    }

    private static Map<Integer, Set<GlobalPosRef>> loadMap(CompoundTag root) {
        Map<Integer, Set<GlobalPosRef>> out = new HashMap<>();
        for (String k : root.getAllKeys()) {
            int key;
            try {
                key = Integer.parseInt(k);
            } catch (NumberFormatException ignore) {
                continue;
            }

            ListTag list = root.getList(k, Tag.TAG_COMPOUND);
            HashSet<GlobalPosRef> set = new HashSet<>();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag pt = list.getCompound(i);
                ResourceLocation dimId = ResourceLocation.tryParse(pt.getString("dim"));
                if (dimId == null) dimId = Level.OVERWORLD.location();
                ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, dimId);
                BlockPos pos = new BlockPos(pt.getInt("x"), pt.getInt("y"), pt.getInt("z"));
                set.add(new GlobalPosRef(dim, pos));
            }
            if (!set.isEmpty()) out.put(key, set);
        }
        return out;
    }

    public record GlobalPosRef(ResourceKey<Level> dimension, BlockPos pos) {
        public static GlobalPosRef of(ServerLevel level, BlockPos pos) {
            return new GlobalPosRef(level.dimension(), pos.immutable());
        }
    }
}
