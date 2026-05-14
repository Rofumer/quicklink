package com.maximpolyakov.quicklink.neoforge.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.*;

public class QuickLinkEnergyNetworkManager extends SavedData {

    private static final Codec<GlobalPosRef> GLOBAL_POS_CODEC = RecordCodecBuilder.create(inst -> inst.group(
            ResourceKey.codec(Registries.DIMENSION).fieldOf("dim").forGetter(GlobalPosRef::dimension),
            BlockPos.CODEC.fieldOf("pos").forGetter(GlobalPosRef::pos)
    ).apply(inst, GlobalPosRef::new));

    private static final Codec<Map<Integer, Set<GlobalPosRef>>> NET_MAP_CODEC = Codec.unboundedMap(
            Codec.STRING.xmap(Integer::parseInt, i -> Integer.toString(i)),
            Codec.list(GLOBAL_POS_CODEC).xmap(HashSet::new, ArrayList::new)
    );

    static final Codec<QuickLinkEnergyNetworkManager> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            NET_MAP_CODEC.fieldOf("plugs").forGetter(m -> m.plugsByKey),
            NET_MAP_CODEC.fieldOf("points").forGetter(m -> m.pointsByKey)
    ).apply(inst, (plugs, points) -> {
        QuickLinkEnergyNetworkManager mgr = new QuickLinkEnergyNetworkManager();
        mgr.plugsByKey.putAll(plugs);
        mgr.pointsByKey.putAll(points);
        return mgr;
    }));

    private static final SavedDataType<QuickLinkEnergyNetworkManager> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("quicklink", "energy_network_mgr"),
            QuickLinkEnergyNetworkManager::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final Map<Integer, Set<GlobalPosRef>> plugsByKey = new HashMap<>();
    private final Map<Integer, Set<GlobalPosRef>> pointsByKey = new HashMap<>();

    public static QuickLinkEnergyNetworkManager get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

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

    public List<GlobalPosRef> getPlugsSnapshot(int key) {
        Set<GlobalPosRef> set = plugsByKey.get(key);
        if (set == null || set.isEmpty()) return Collections.emptyList();
        ArrayList<GlobalPosRef> out = new ArrayList<>(set);
        out.sort(QuickLinkEnergyNetworkManager::compareRefs);
        return out;
    }

    public List<GlobalPosRef> getPointsSnapshot(int key) {
        Set<GlobalPosRef> set = pointsByKey.get(key);
        if (set == null || set.isEmpty()) return Collections.emptyList();
        ArrayList<GlobalPosRef> out = new ArrayList<>(set);
        out.sort(QuickLinkEnergyNetworkManager::compareRefs);
        return out;
    }

    private static int compareRefs(GlobalPosRef a, GlobalPosRef b) {
        int c = a.dimension.identifier().compareTo(b.dimension.identifier());
        if (c != 0) return c;
        c = Integer.compare(a.pos.getX(), b.pos.getX());
        if (c != 0) return c;
        c = Integer.compare(a.pos.getY(), b.pos.getY());
        if (c != 0) return c;
        return Integer.compare(a.pos.getZ(), b.pos.getZ());
    }

    public record GlobalPosRef(ResourceKey<Level> dimension, BlockPos pos) {
        public static GlobalPosRef of(ServerLevel level, BlockPos pos) {
            return new GlobalPosRef(level.dimension(), pos.immutable());
        }
    }
}
