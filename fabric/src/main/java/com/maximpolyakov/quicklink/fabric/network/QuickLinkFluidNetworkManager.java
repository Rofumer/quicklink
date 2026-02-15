package com.maximpolyakov.quicklink.fabric.network;

import net.minecraft.util.datafix.DataFixTypes;
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

public class QuickLinkFluidNetworkManager extends SavedData {

    private static final String DATA_NAME = "quicklink_fluid_networks";

    // key -> set(pos) across all dimensions
    private final Map<Integer, Set<GlobalPosRef>> plugs = new HashMap<>();
    private final Map<Integer, Set<GlobalPosRef>> points = new HashMap<>();

    public QuickLinkFluidNetworkManager() {}

    public static QuickLinkFluidNetworkManager get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(
                        QuickLinkFluidNetworkManager::new,
                        QuickLinkFluidNetworkManager::load,
                        null // или DataFixTypes.LEVEL (см. ниже)
                ),
                DATA_NAME
        );
    }

    public static QuickLinkFluidNetworkManager load(CompoundTag tag, HolderLookup.Provider provider) {
        QuickLinkFluidNetworkManager mgr = new QuickLinkFluidNetworkManager();
        mgr.plugs.putAll(loadMap(tag.getCompound("plugs")));
        mgr.points.putAll(loadMap(tag.getCompound("points")));
        return mgr;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.put("plugs", saveMap(plugs));
        tag.put("points", saveMap(points));
        return tag;
    }

    // ---------------- API ----------------

    public void registerPlug(ServerLevel level, int key, BlockPos pos) {
        plugs.computeIfAbsent(key, kk -> new HashSet<>()).add(GlobalPosRef.of(level, pos));
        setDirty();
    }

    public void unregisterPlug(ServerLevel level, int key, BlockPos pos) {
        Set<GlobalPosRef> s = plugs.get(key);
        if (s != null) {
            s.remove(GlobalPosRef.of(level, pos));
            if (s.isEmpty()) plugs.remove(key);
            setDirty();
        }
    }

    public void registerPoint(ServerLevel level, int key, BlockPos pos) {
        points.computeIfAbsent(key, kk -> new HashSet<>()).add(GlobalPosRef.of(level, pos));
        setDirty();
    }

    public void unregisterPoint(ServerLevel level, int key, BlockPos pos) {
        Set<GlobalPosRef> s = points.get(key);
        if (s != null) {
            s.remove(GlobalPosRef.of(level, pos));
            if (s.isEmpty()) points.remove(key);
            setDirty();
        }
    }

    public List<GlobalPosRef> getPlugsSnapshot(int key) {
        Set<GlobalPosRef> s = plugs.get(key);
        if (s == null || s.isEmpty()) return Collections.emptyList();

        ArrayList<GlobalPosRef> out = new ArrayList<>(s);
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
        Set<GlobalPosRef> s = points.get(key);
        if (s == null || s.isEmpty()) return Collections.emptyList();

        ArrayList<GlobalPosRef> out = new ArrayList<>(s);
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

    // ---------------- NBT helpers ----------------

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
            } catch (NumberFormatException ignored) {
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
