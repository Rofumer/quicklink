package com.maximpolyakov.quicklink.forge.network;

import net.minecraft.core.BlockPos;
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

public class QuickLinkChemicalNetworkManager extends SavedData {

    private static final String DATA_NAME = "quicklink_chemical_network_mgr";

    private final Map<Integer, Set<GlobalPosRef>> plugs  = new HashMap<>();
    private final Map<Integer, Set<GlobalPosRef>> points = new HashMap<>();

    public static QuickLinkChemicalNetworkManager get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                QuickLinkChemicalNetworkManager::load,
                QuickLinkChemicalNetworkManager::new,
                DATA_NAME
        );
    }

    public void registerPlug(ServerLevel level, int key, BlockPos pos) {
        plugs.computeIfAbsent(key, k -> new HashSet<>()).add(GlobalPosRef.of(level, pos)); setDirty();
    }

    public void unregisterPlug(ServerLevel level, int key, BlockPos pos) {
        Set<GlobalPosRef> s = plugs.get(key); if (s == null) return;
        if (s.remove(GlobalPosRef.of(level, pos))) setDirty();
        if (s.isEmpty()) plugs.remove(key);
    }

    public void registerPoint(ServerLevel level, int key, BlockPos pos) {
        points.computeIfAbsent(key, k -> new HashSet<>()).add(GlobalPosRef.of(level, pos)); setDirty();
    }

    public void unregisterPoint(ServerLevel level, int key, BlockPos pos) {
        Set<GlobalPosRef> s = points.get(key); if (s == null) return;
        if (s.remove(GlobalPosRef.of(level, pos))) setDirty();
        if (s.isEmpty()) points.remove(key);
    }

    public List<GlobalPosRef> getPlugsSnapshot(int key) {
        Set<GlobalPosRef> s = plugs.get(key);
        if (s == null || s.isEmpty()) return Collections.emptyList();
        ArrayList<GlobalPosRef> out = new ArrayList<>(s); out.sort(GlobalPosRef.COMPARATOR); return out;
    }

    public List<GlobalPosRef> getPointsSnapshot(int key) {
        Set<GlobalPosRef> s = points.get(key);
        if (s == null || s.isEmpty()) return Collections.emptyList();
        ArrayList<GlobalPosRef> out = new ArrayList<>(s); out.sort(GlobalPosRef.COMPARATOR); return out;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.put("plugs", saveMap(plugs)); tag.put("points", saveMap(points)); return tag;
    }

    public static QuickLinkChemicalNetworkManager load(CompoundTag tag) {
        QuickLinkChemicalNetworkManager mgr = new QuickLinkChemicalNetworkManager();
        mgr.plugs.putAll(loadMap(tag.getCompound("plugs")));
        mgr.points.putAll(loadMap(tag.getCompound("points")));
        return mgr;
    }

    private static CompoundTag saveMap(Map<Integer, Set<GlobalPosRef>> map) {
        CompoundTag root = new CompoundTag();
        for (Map.Entry<Integer, Set<GlobalPosRef>> e : map.entrySet()) {
            ListTag list = new ListTag();
            for (GlobalPosRef gp : e.getValue()) {
                CompoundTag pt = new CompoundTag();
                pt.putString("dim", gp.dimension.location().toString());
                pt.putInt("x", gp.pos.getX()); pt.putInt("y", gp.pos.getY()); pt.putInt("z", gp.pos.getZ());
                list.add(pt);
            }
            root.put(Integer.toString(e.getKey()), list);
        }
        return root;
    }

    private static Map<Integer, Set<GlobalPosRef>> loadMap(CompoundTag root) {
        Map<Integer, Set<GlobalPosRef>> out = new HashMap<>();
        for (String k : root.getAllKeys()) {
            int key; try { key = Integer.parseInt(k); } catch (NumberFormatException ignore) { continue; }
            ListTag list = root.getList(k, Tag.TAG_COMPOUND);
            HashSet<GlobalPosRef> set = new HashSet<>();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag pt = list.getCompound(i);
                ResourceLocation dimId = ResourceLocation.tryParse(pt.getString("dim"));
                if (dimId == null) dimId = Level.OVERWORLD.location();
                set.add(new GlobalPosRef(ResourceKey.create(Registries.DIMENSION, dimId),
                        new BlockPos(pt.getInt("x"), pt.getInt("y"), pt.getInt("z"))));
            }
            if (!set.isEmpty()) out.put(key, set);
        }
        return out;
    }

    public record GlobalPosRef(ResourceKey<Level> dimension, BlockPos pos) {
        static final Comparator<GlobalPosRef> COMPARATOR = Comparator
                .<GlobalPosRef, String>comparing(r -> r.dimension.location().toString())
                .thenComparingInt(r -> r.pos.getX()).thenComparingInt(r -> r.pos.getY()).thenComparingInt(r -> r.pos.getZ());

        public static GlobalPosRef of(ServerLevel level, BlockPos pos) {
            return new GlobalPosRef(level.dimension(), pos.immutable());
        }
    }
}
