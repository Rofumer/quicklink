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

public class QuickLinkFluidNetworkManager extends SavedData {

    private static final Codec<GlobalPosRef> GLOBAL_POS_CODEC = RecordCodecBuilder.create(inst -> inst.group(
            ResourceKey.codec(Registries.DIMENSION).fieldOf("dim").forGetter(GlobalPosRef::dimension),
            BlockPos.CODEC.fieldOf("pos").forGetter(GlobalPosRef::pos)
    ).apply(inst, GlobalPosRef::new));

    private static final Codec<Map<Integer, Set<GlobalPosRef>>> NET_MAP_CODEC = Codec.unboundedMap(
            Codec.STRING.xmap(Integer::parseInt, i -> Integer.toString(i)),
            Codec.list(GLOBAL_POS_CODEC).xmap(HashSet::new, ArrayList::new)
    );

    static final Codec<QuickLinkFluidNetworkManager> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            NET_MAP_CODEC.fieldOf("plugs").forGetter(m -> m.plugs),
            NET_MAP_CODEC.fieldOf("points").forGetter(m -> m.points)
    ).apply(inst, (plugs, points) -> {
        QuickLinkFluidNetworkManager mgr = new QuickLinkFluidNetworkManager();
        mgr.plugs.putAll(plugs);
        mgr.points.putAll(points);
        return mgr;
    }));

    private static final SavedDataType<QuickLinkFluidNetworkManager> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("quicklink", "fluid_networks"),
            QuickLinkFluidNetworkManager::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final Map<Integer, Set<GlobalPosRef>> plugs = new HashMap<>();
    private final Map<Integer, Set<GlobalPosRef>> points = new HashMap<>();

    public QuickLinkFluidNetworkManager() {}

    public static QuickLinkFluidNetworkManager get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public void registerPlug(ServerLevel level, int key, BlockPos pos) {
        plugs.computeIfAbsent(key, k -> new HashSet<>()).add(GlobalPosRef.of(level, pos));
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
        points.computeIfAbsent(key, k -> new HashSet<>()).add(GlobalPosRef.of(level, pos));
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
        out.sort(QuickLinkFluidNetworkManager::compareRefs);
        return out;
    }

    public List<GlobalPosRef> getPointsSnapshot(int key) {
        Set<GlobalPosRef> s = points.get(key);
        if (s == null || s.isEmpty()) return Collections.emptyList();
        ArrayList<GlobalPosRef> out = new ArrayList<>(s);
        out.sort(QuickLinkFluidNetworkManager::compareRefs);
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
