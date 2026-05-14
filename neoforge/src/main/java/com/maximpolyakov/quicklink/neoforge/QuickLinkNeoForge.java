package com.maximpolyakov.quicklink.neoforge;

import com.maximpolyakov.quicklink.neoforge.config.QuickLinkConfig;
import com.maximpolyakov.quicklink.neoforge.item.QuickLinkPlugBlockItem;
import com.maximpolyakov.quicklink.neoforge.item.QuickLinkUpgradeItem;
import net.neoforged.fml.config.ModConfig;
import com.maximpolyakov.quicklink.QuickLink;
import com.maximpolyakov.quicklink.neoforge.block.FluidPlugBlock;
import com.maximpolyakov.quicklink.neoforge.block.EnergyPlugBlock;
import com.maximpolyakov.quicklink.neoforge.block.ItemPlugBlock;
import com.maximpolyakov.quicklink.neoforge.blockentity.EnergyPlugBlockEntity;
import com.maximpolyakov.quicklink.neoforge.blockentity.FluidPlugBlockEntity;
import com.maximpolyakov.quicklink.neoforge.blockentity.ItemPlugBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(QuickLink.MOD_ID)
public final class QuickLinkNeoForge {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, QuickLink.MOD_ID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, QuickLink.MOD_ID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, QuickLink.MOD_ID);

    public static final DeferredHolder<Block, Block> ITEM_PLUG =
            BLOCKS.register("item_plug",
                    id -> new ItemPlugBlock(Block.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id)).strength(0.3f).noOcclusion()));

    public static final DeferredHolder<Item, Item> ITEM_PLUG_ITEM =
            ITEMS.register("item_plug", id -> new QuickLinkPlugBlockItem(ITEM_PLUG.get(), new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id))));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ItemPlugBlockEntity>> ITEM_PLUG_BE =
            BLOCK_ENTITIES.register("item_plug",
                    () -> new BlockEntityType<>(ItemPlugBlockEntity::new, ITEM_PLUG.get()));

    public static final DeferredHolder<Block, FluidPlugBlock> FLUID_PLUG_BLOCK = BLOCKS.register(
            "fluid_plug",
            id -> new FluidPlugBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id)).strength(0.3F).noOcclusion())
    );

    public static final DeferredHolder<Item, BlockItem> FLUID_PLUG_ITEM = ITEMS.register(
            "fluid_plug",
            id -> new QuickLinkPlugBlockItem(FLUID_PLUG_BLOCK.get(), new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)))
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FluidPlugBlockEntity>> FLUID_PLUG_BE =
            BLOCK_ENTITIES.register("fluid_plug",
                    () -> new BlockEntityType<>(FluidPlugBlockEntity::new, FLUID_PLUG_BLOCK.get()));

    public static final DeferredHolder<Item, Item> UPGRADE_ITEM =
            ITEMS.register("quicklink_upgrade", id -> new QuickLinkUpgradeItem(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)).stacksTo(64)));

    public static final DeferredHolder<Block, EnergyPlugBlock> ENERGY_PLUG_BLOCK = BLOCKS.register(
            "energy_plug",
            id -> new EnergyPlugBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id)).strength(0.3F).noOcclusion())
    );

    public static final DeferredHolder<Item, BlockItem> ENERGY_PLUG_ITEM = ITEMS.register(
            "energy_plug",
            id -> new QuickLinkPlugBlockItem(ENERGY_PLUG_BLOCK.get(), new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)))
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EnergyPlugBlockEntity>> ENERGY_PLUG_BE =
            BLOCK_ENTITIES.register("energy_plug",
                    () -> new BlockEntityType<>(EnergyPlugBlockEntity::new, ENERGY_PLUG_BLOCK.get()));


    public QuickLinkNeoForge(IEventBus modBus, ModContainer container) {
        QuickLink.init();

        container.registerConfig(ModConfig.Type.COMMON, QuickLinkConfig.SPEC);

        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);

        modBus.addListener(this::addCreative);
        modBus.addListener(this::registerCapabilities);
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, ITEM_PLUG_BE.get(),
                (be, side) -> be.getExternalItemHandler(side));
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, FLUID_PLUG_BE.get(),
                (be, side) -> be.getExternalFluidHandler(side));
        event.registerBlockEntity(Capabilities.Energy.BLOCK, ENERGY_PLUG_BE.get(),
                (be, side) -> be.getExternalEnergyStorage(side));
    }

    private void addCreative(BuildCreativeModeTabContentsEvent e) {
        if (e.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            e.accept(ITEM_PLUG_ITEM.get());
            e.accept(FLUID_PLUG_ITEM.get());
            e.accept(ENERGY_PLUG_ITEM.get());
            e.accept(UPGRADE_ITEM.get());
        }
    }
}
