package com.maximpolyakov.quicklink.neoforge.blockentity;

import com.maximpolyakov.quicklink.QuickLinkColors;
import com.maximpolyakov.quicklink.QuickLinkNbt;
import com.maximpolyakov.quicklink.neoforge.QuickLinkNeoForge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class ItemPlugBlockEntity extends BlockEntity {

    private QuickLinkColors colors = QuickLinkColors.unset();
    private Direction side = Direction.NORTH; // later: set on placement
    private boolean enabled = true;

    public ItemPlugBlockEntity(BlockPos pos, BlockState state) {
        super(QuickLinkNeoForge.ITEM_PLUG_BE.get(), pos, state);
    }

    public Direction getSide() {
        return side;
    }

    public QuickLinkColors getColors() {
        return colors;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getNetworkKey() {
        return colors.networkKey();
    }

    // единая точка, чтобы не забывать флаги/пакеты
    private void sendUpdates() {
        setChanged();
        if (level != null) {
            // на клиенте это просто перерисовка/обновление, на сервере ещё и пакет
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void setColors(QuickLinkColors colors) {
        this.colors = colors;
        if (level != null && !level.isClientSide) {
            System.out.println("[QuickLink][BE] setColors pos=" + worldPosition + " packed=" + colors.pack());
        }
        sendUpdates();
    }

    public void setColor(int slot, byte colorId) {
        colors = colors.with(slot, colorId);

        if (level != null && !level.isClientSide) {
            System.out.println("[QuickLink][BE] setColor pos=" + worldPosition +
                    " slot=" + slot + " colorId=" + (colorId & 0xFF) +
                    " packed=" + colors.pack());
        }

        sendUpdates();
    }

    public void setSide(Direction side) {
        this.side = side;
        if (level != null && !level.isClientSide) {
            System.out.println("[QuickLink][BE] setSide pos=" + worldPosition + " side=" + side);
        }
        sendUpdates();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (level != null && !level.isClientSide) {
            System.out.println("[QuickLink][BE] setEnabled pos=" + worldPosition + " enabled=" + enabled);
        }
        sendUpdates();
    }

    // =========================
    // NBT save/load (disk + tag)
    // =========================

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        tag.putInt(QuickLinkNbt.COLORS, colors.pack());
        tag.putByte(QuickLinkNbt.SIDE, (byte) side.get3DDataValue());
        tag.putBoolean(QuickLinkNbt.ENABLED, enabled);

        // лог только на сервере, и только если мир есть
        if (level != null && !level.isClientSide) {
            System.out.println("[QuickLink][BE] save pos=" + worldPosition +
                    " side=" + side +
                    " packed=" + colors.pack() +
                    " enabled=" + enabled);
        }
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        // важно: не затираем дефолты, если тега нет
        if (tag.contains(QuickLinkNbt.COLORS)) {
            colors = QuickLinkColors.unpack(tag.getInt(QuickLinkNbt.COLORS));
        }
        if (tag.contains(QuickLinkNbt.SIDE)) {
            side = Direction.from3DDataValue(tag.getByte(QuickLinkNbt.SIDE));
        }
        if (tag.contains(QuickLinkNbt.ENABLED)) {
            enabled = tag.getBoolean(QuickLinkNbt.ENABLED);
        }

        if (level != null && !level.isClientSide) {
            System.out.println("[QuickLink][BE] load pos=" + worldPosition +
                    " side=" + side +
                    " packed=" + colors.pack() +
                    " enabled=" + enabled);
        }
    }

    // =========================
    // Client sync for BER
    // =========================

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        // Это то, что клиент получит при chunk sync
        CompoundTag tag = super.getUpdateTag(registries);
        // кладём туда наши поля
        tag.putInt(QuickLinkNbt.COLORS, colors.pack());
        tag.putByte(QuickLinkNbt.SIDE, (byte) side.get3DDataValue());
        tag.putBoolean(QuickLinkNbt.ENABLED, enabled);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        // вызывается на клиенте при chunk sync
        loadAdditional(tag, registries);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        // Это пакет, который уходит при sendBlockUpdated(..., 3)
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider registries) {
        // вызывается на клиенте при получении пакета
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            loadAdditional(tag, registries);
        }
    }
}
