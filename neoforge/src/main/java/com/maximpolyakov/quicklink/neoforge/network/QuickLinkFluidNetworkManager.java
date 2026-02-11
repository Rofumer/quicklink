package com.maximpolyakov.quicklink.neoforge.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

public class QuickLinkFluidNetworkManager extends SavedData {

    private static final String DATA_NAME = "quicklink_fluid_networks";

    // key -> set(pos)
    private final Map<Integer, Set<BlockPos>> plugs = new HashMap<>();
    private final Map<Integer, Set<BlockPos>> points = new HashMap<>();

    public QuickLinkFluidNetworkManager() {}

    public static QuickLinkFluidNetworkManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(
                        QuickLinkFluidNetworkManager::new,
                        QuickLinkFluidNetworkManager::load
                ),
                DATA_NAME
        );
    }

    /**
     * 1.21+ loader signature for SavedData.Factory:
     * (CompoundTag tag, HolderLookup.Provider provider) -> SavedData
     */
    public static QuickLinkFluidNetworkManager load(CompoundTag tag, HolderLookup.Provider provider) {
        QuickLinkFluidNetworkManager mgr = new QuickLinkFluidNetworkManager();

        CompoundTag tPlugs = tag.getCompound("plugs");
        for (String k : tPlugs.getAllKeys()) {
            int key;
            try {
                key = Integer.parseInt(k);
            } catch (NumberFormatException ignored) {
                continue;
            }
            mgr.plugs.put(key, readPosSet(tPlugs.getCompound(k)));
        }

        CompoundTag tPoints = tag.getCompound("points");
        for (String k : tPoints.getAllKeys()) {
            int key;
            try {
                key = Integer.parseInt(k);
            } catch (NumberFormatException ignored) {
                continue;
            }
            mgr.points.put(key, readPosSet(tPoints.getCompound(k)));
        }

        return mgr;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag tPlugs = new CompoundTag();
        for (Map.Entry<Integer, Set<BlockPos>> e : plugs.entrySet()) {
            tPlugs.put(Integer.toString(e.getKey()), writePosSet(e.getValue()));
        }
        tag.put("plugs", tPlugs);

        CompoundTag tPoints = new CompoundTag();
        for (Map.Entry<Integer, Set<BlockPos>> e : points.entrySet()) {
            tPoints.put(Integer.toString(e.getKey()), writePosSet(e.getValue()));
        }
        tag.put("points", tPoints);

        return tag;
    }

    // ---------------- API ----------------

    public void registerPlug(int key, BlockPos pos) {
        plugs.computeIfAbsent(key, kk -> new HashSet<>()).add(pos.immutable());
        setDirty();
    }

    public void unregisterPlug(int key, BlockPos pos) {
        Set<BlockPos> s = plugs.get(key);
        if (s != null) {
            s.remove(pos);
            if (s.isEmpty()) plugs.remove(key);
            setDirty();
        }
    }

    public void registerPoint(int key, BlockPos pos) {
        points.computeIfAbsent(key, kk -> new HashSet<>()).add(pos.immutable());
        setDirty();
    }

    public void unregisterPoint(int key, BlockPos pos) {
        Set<BlockPos> s = points.get(key);
        if (s != null) {
            s.remove(pos);
            if (s.isEmpty()) points.remove(key);
            setDirty();
        }
    }

    public List<BlockPos> getPlugsSnapshot(int key) {
        Set<BlockPos> s = plugs.get(key);
        if (s == null || s.isEmpty()) return Collections.emptyList();
        return new ArrayList<>(s);
    }

    public List<BlockPos> getPointsSnapshot(int key) {
        Set<BlockPos> s = points.get(key);
        if (s == null || s.isEmpty()) return Collections.emptyList();
        return new ArrayList<>(s);
    }

    // ---------------- NBT helpers ----------------

    private static CompoundTag writePosSet(Set<BlockPos> set) {
        CompoundTag t = new CompoundTag();
        int i = 0;
        for (BlockPos p : set) {
            CompoundTag pt = new CompoundTag();
            pt.putInt("x", p.getX());
            pt.putInt("y", p.getY());
            pt.putInt("z", p.getZ());
            t.put("p" + (i++), pt);
        }
        t.putInt("size", set.size());
        return t;
    }

    private static Set<BlockPos> readPosSet(CompoundTag t) {
        Set<BlockPos> out = new HashSet<>();
        int size = t.getInt("size");
        for (int i = 0; i < size; i++) {
            CompoundTag pt = t.getCompound("p" + i);
            out.add(new BlockPos(pt.getInt("x"), pt.getInt("y"), pt.getInt("z")));
        }
        return out;
    }
}
