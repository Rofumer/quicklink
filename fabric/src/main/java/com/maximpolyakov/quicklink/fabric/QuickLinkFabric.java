package com.maximpolyakov.quicklink.fabric;

import com.maximpolyakov.quicklink.QuickLink;
import com.maximpolyakov.quicklink.fabric.block.EnergyPlugBlock;
import com.maximpolyakov.quicklink.fabric.block.FluidPlugBlock;
import com.maximpolyakov.quicklink.fabric.block.ItemPlugBlock;
import com.maximpolyakov.quicklink.fabric.blockentity.EnergyPlugBlockEntity;
import com.maximpolyakov.quicklink.fabric.blockentity.FluidPlugBlockEntity;
import com.maximpolyakov.quicklink.fabric.blockentity.ItemPlugBlockEntity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class QuickLinkFabric implements ModInitializer {
    public static Block ITEM_PLUG;
    public static Block FLUID_PLUG_BLOCK;
    public static Block ENERGY_PLUG_BLOCK;

    public static Item ITEM_PLUG_ITEM;
    public static Item FLUID_PLUG_ITEM;
    public static Item ENERGY_PLUG_ITEM;

    public static BlockEntityType<ItemPlugBlockEntity> ITEM_PLUG_BE;
    public static BlockEntityType<FluidPlugBlockEntity> FLUID_PLUG_BE;
    public static BlockEntityType<EnergyPlugBlockEntity> ENERGY_PLUG_BE;

    @Override
    public void onInitialize() {
        QuickLink.init();

        ITEM_PLUG = registerBlock("item_plug", new ItemPlugBlock(BlockBehaviour.Properties.of().strength(0.3f).noOcclusion()));
        FLUID_PLUG_BLOCK = registerBlock("fluid_plug", new FluidPlugBlock(BlockBehaviour.Properties.of().strength(0.3f).noOcclusion()));
        ENERGY_PLUG_BLOCK = registerBlock("energy_plug", new EnergyPlugBlock(BlockBehaviour.Properties.of().strength(0.3f).noOcclusion()));

        ITEM_PLUG_ITEM = registerItem("item_plug", new BlockItem(ITEM_PLUG, new Item.Properties()));
        FLUID_PLUG_ITEM = registerItem("fluid_plug", new BlockItem(FLUID_PLUG_BLOCK, new Item.Properties()));
        ENERGY_PLUG_ITEM = registerItem("energy_plug", new BlockItem(ENERGY_PLUG_BLOCK, new Item.Properties()));

        ITEM_PLUG_BE = registerBlockEntity("item_plug", BlockEntityType.Builder.of(ItemPlugBlockEntity::new, ITEM_PLUG).build(null));
        FLUID_PLUG_BE = registerBlockEntity("fluid_plug", BlockEntityType.Builder.of(FluidPlugBlockEntity::new, FLUID_PLUG_BLOCK).build(null));
        ENERGY_PLUG_BE = registerBlockEntity("energy_plug", BlockEntityType.Builder.of(EnergyPlugBlockEntity::new, ENERGY_PLUG_BLOCK).build(null));

        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries -> {
            entries.accept(ITEM_PLUG_ITEM);
            entries.accept(FLUID_PLUG_ITEM);
            entries.accept(ENERGY_PLUG_ITEM);
        });
    }

    private static Block registerBlock(String id, Block block) {
        return Registry.register(BuiltInRegistries.BLOCK, ResourceLocation.fromNamespaceAndPath(QuickLink.MOD_ID, id), block);
    }

    private static Item registerItem(String id, Item item) {
        return Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath(QuickLink.MOD_ID, id), item);
    }

    private static <T extends net.minecraft.world.level.block.entity.BlockEntity> BlockEntityType<T> registerBlockEntity(String id, BlockEntityType<T> type) {
        return Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(QuickLink.MOD_ID, id), type);
    }
}
