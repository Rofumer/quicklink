package com.maximpolyakov.quicklink.neoforge.blockentity;

import com.maximpolyakov.quicklink.QuickLinkColors;
import com.maximpolyakov.quicklink.QuickLinkNbt;
import com.maximpolyakov.quicklink.neoforge.QuickLinkNeoForge;
import com.maximpolyakov.quicklink.neoforge.network.QuickLinkNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ItemPlugBlockEntity extends BlockEntity {

    public enum Mode {
        PLUG((byte) 0),
        POINT((byte) 1);

        public final byte id;
        Mode(byte id) { this.id = id; }

        public static Mode fromId(byte id) {
            return (id == 1) ? POINT : PLUG;
        }
    }

    private QuickLinkColors colors = QuickLinkColors.unset();

    // Грань, на которой рисуем и куда "ориентирован" блок (мы используем её же как направление подключения)
    private Direction side = Direction.NORTH;

    private boolean enabled = true;
    private Mode mode = Mode.PLUG;

    // round-robin индекс для POINT
    private int rrIndex = 0;

    // кеш для корректной перерегистрации в менеджере
    private int lastRegKey = Integer.MIN_VALUE;
    private Mode lastRegMode = null;

    public ItemPlugBlockEntity(BlockPos pos, BlockState state) {
        super(QuickLinkNeoForge.ITEM_PLUG_BE.get(), pos, state);
    }

    // ---------------- getters/setters ----------------

    public Direction getSide() { return side; }

    public void setSide(Direction side) {
        this.side = (side == null) ? Direction.NORTH : side;
        setChangedAndSync();
        // side не влияет на сеть, реестр не трогаем
    }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        setChangedAndSync();
        // enabled не влияет на сеть, реестр не трогаем
    }

    public Mode getMode() { return mode; }

    public void setMode(Mode mode) {
        if (mode == null) mode = Mode.PLUG;
        if (this.mode == mode) return;

        this.mode = mode;
        setChangedAndSync();
        syncRegistration(); // важно: PLUG<->POINT меняет реестр
    }

    public QuickLinkColors getColors() { return colors; }

    public void setColors(QuickLinkColors colors) {
        this.colors = (colors == null) ? QuickLinkColors.unset() : colors;
        setChangedAndSync();
        syncRegistration(); // важно: ключ сети меняется
    }

    public int getNetworkKey() {
        return colors.networkKey();
    }

    public void setColor(int slot, byte colorId) {
        int oldKey = getNetworkKey();

        colors = colors.with(slot, colorId);
        setChangedAndSync();

        // если ключ поменялся — перерегистрируем
        if (oldKey != getNetworkKey()) {
            syncRegistration();
        }

        if (level != null && !level.isClientSide) {
            System.out.println("[QuickLink][BE] setColor pos=" + worldPosition
                    + " slot=" + slot + " colorId=" + (colorId & 0xFF) + " packed=" + colors.pack());
        }
    }

    public void toggleMode() {
        setMode(this.mode == Mode.PLUG ? Mode.POINT : Mode.PLUG);
    }

    // ---------------- BE sync (server -> client) ----------------
    // Это нужно для твоего рендера: цвета/side/mode должны попасть на клиент.

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ---------------- lifecycle / networking ----------------

    private void setChangedAndSync() {
        setChanged();

        if (level != null) {
            // иногда полезно в 1.21+, чтобы движок точно понял, что BE менялся
            level.blockEntityChanged(worldPosition);

            if (!level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            syncRegistration();
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            unregisterFromManager();
        }
        super.setRemoved();
    }

    private void unregisterFromManager() {
        if (!(level instanceof ServerLevel sl)) return;
        if (lastRegMode == null || lastRegKey == Integer.MIN_VALUE) return;

        QuickLinkNetworkManager mgr = QuickLinkNetworkManager.get(sl);
        if (lastRegMode == Mode.PLUG) mgr.unregisterPlug(lastRegKey, worldPosition);
        else mgr.unregisterPoint(lastRegKey, worldPosition);

        lastRegKey = Integer.MIN_VALUE;
        lastRegMode = null;
    }

    private void syncRegistration() {
        if (!(level instanceof ServerLevel sl)) return;

        int key = getNetworkKey();

        // убираем старую запись если поменялось что-то важное
        if (lastRegMode != null && lastRegKey != Integer.MIN_VALUE) {
            if (lastRegKey != key || lastRegMode != mode) {
                unregisterFromManager();
            }
        }

        // регистрируем заново
        QuickLinkNetworkManager mgr = QuickLinkNetworkManager.get(sl);
        if (mode == Mode.PLUG) mgr.registerPlug(key, worldPosition);
        else mgr.registerPoint(key, worldPosition);

        lastRegKey = key;
        lastRegMode = mode;
    }

    // ---------------- ticking / transfer ----------------

    public static void serverTick(Level level, BlockPos pos, BlockState state, ItemPlugBlockEntity be) {
        if (!(level instanceof ServerLevel sl)) return;
        if (!be.enabled) return;
        if (be.mode != Mode.POINT) return;

        // чтобы не спамить: пробуем раз в 10 тиков (0.5 сек)
        long gt = sl.getGameTime();
        if ((gt % 10L) != 0L) return;

        be.tryPullOnce(sl);
    }

    /**
     * POINT тянет 1 предмет из одного из PLUG'ов с тем же networkKey
     * и пытается положить в контейнер, к которому подключён POINT.
     */
    private void tryPullOnce(ServerLevel sl) {
        int key = getNetworkKey();

        QuickLinkNetworkManager mgr = QuickLinkNetworkManager.get(sl);
        List<BlockPos> plugs = mgr.getPlugsSnapshot(key);
        if (plugs.isEmpty()) return;

        // куда складываем: контейнер "за" выбранной гранью
        Container dst = getAttachedContainer(sl, worldPosition, side);
        if (dst == null) return;

        // round-robin по PLUG’ам
        int start = rrIndex;
        for (int i = 0; i < plugs.size(); i++) {
            int idx = (start + i) % plugs.size();
            BlockPos plugPos = plugs.get(idx);

            BlockEntity other = sl.getBlockEntity(plugPos);
            if (!(other instanceof ItemPlugBlockEntity plugBe)) continue;
            if (plugBe.mode != Mode.PLUG) continue;
            if (!plugBe.enabled) continue;

            Container src = getAttachedContainer(sl, plugPos, plugBe.side);
            if (src == null) continue;

            if (moveOneItem(src, dst)) {
                rrIndex = (idx + 1) % plugs.size();
                setChanged(); // rrIndex сохраняем на диск, клиенту он не нужен
                return;
            }
        }

        // если ничего не смогли — всё равно крутим индекс, чтобы не “залипать”
        rrIndex = (rrIndex + 1) % plugs.size();
        setChanged();
    }

    /**
     * Контейнер, к которому "пристыкован" наш блок.
     * Мы считаем, что контейнер находится на блоке "за" гранью: pos.relative(side.getOpposite()).
     */
    private static Container getAttachedContainer(ServerLevel level, BlockPos selfPos, Direction side) {
        BlockPos target = selfPos.relative(side.getOpposite());
        return HopperBlockEntity.getContainerAt(level, target);
    }

    /**
     * Переместить 1 предмет из src в dst. Без sided-логики (для сундуков хватает).
     * Возвращает true если реально переместили.
     */
    private static boolean moveOneItem(Container src, Container dst) {
        for (int i = 0; i < src.getContainerSize(); i++) {
            ItemStack s = src.getItem(i);
            if (s.isEmpty()) continue;

            ItemStack one = s.copy();
            one.setCount(1);

            if (tryInsertOne(dst, one)) {
                src.removeItem(i, 1);
                src.setChanged();
                dst.setChanged();
                return true;
            }
        }
        return false;
    }

    private static boolean tryInsertOne(Container dst, ItemStack one) {
        for (int j = 0; j < dst.getContainerSize(); j++) {
            ItemStack d = dst.getItem(j);

            if (d.isEmpty()) {
                dst.setItem(j, one);
                return true;
            }

            if (ItemStack.isSameItemSameComponents(d, one) && d.getCount() < d.getMaxStackSize()) {
                d.grow(1);
                dst.setItem(j, d);
                return true;
            }
        }
        return false;
    }

    // ---------------- NBT ----------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        tag.putInt(QuickLinkNbt.COLORS, colors.pack());
        tag.putByte(QuickLinkNbt.SIDE, (byte) side.get3DDataValue());
        tag.putBoolean(QuickLinkNbt.ENABLED, enabled);

        tag.putByte("ql_mode", mode.id);
        tag.putInt("ql_rr", rrIndex);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        int defaultPackedColors = QuickLinkColors.unset().pack();
        colors = QuickLinkColors.unpack(tag.contains(QuickLinkNbt.COLORS, Tag.TAG_INT)
                ? tag.getInt(QuickLinkNbt.COLORS)
                : defaultPackedColors);

        side = tag.contains(QuickLinkNbt.SIDE, Tag.TAG_BYTE)
                ? Direction.from3DDataValue(tag.getByte(QuickLinkNbt.SIDE))
                : Direction.NORTH;

        enabled = !tag.contains(QuickLinkNbt.ENABLED, Tag.TAG_BYTE)
                || tag.getBoolean(QuickLinkNbt.ENABLED);

        mode = tag.contains("ql_mode", Tag.TAG_BYTE)
                ? Mode.fromId(tag.getByte("ql_mode"))
                : Mode.PLUG;

        rrIndex = tag.contains("ql_rr", Tag.TAG_INT)
                ? tag.getInt("ql_rr")
                : 0;
    }
}
