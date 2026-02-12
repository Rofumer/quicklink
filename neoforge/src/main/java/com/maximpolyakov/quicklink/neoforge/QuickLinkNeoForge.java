package com.maximpolyakov.quicklink.neoforge;

import com.maximpolyakov.quicklink.neoforge.config.QuickLinkConfig;
import net.neoforged.fml.config.ModConfig;
import com.maximpolyakov.quicklink.QuickLink;
import com.maximpolyakov.quicklink.neoforge.block.FluidPlugBlock;
import com.maximpolyakov.quicklink.neoforge.block.ItemPlugBlock;
import com.maximpolyakov.quicklink.neoforge.blockentity.FluidPlugBlockEntity;
import com.maximpolyakov.quicklink.neoforge.blockentity.ItemPlugBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
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
                    () -> new ItemPlugBlock(Block.Properties.of().strength(1.5f)));


    public static final DeferredHolder<Item, Item> ITEM_PLUG_ITEM =
            ITEMS.register("item_plug", () -> new BlockItem(ITEM_PLUG.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ItemPlugBlockEntity>> ITEM_PLUG_BE =
            BLOCK_ENTITIES.register("item_plug",
                    () -> BlockEntityType.Builder.of(ItemPlugBlockEntity::new, ITEM_PLUG.get()).build(null));

    public static final DeferredHolder<Block, FluidPlugBlock> FLUID_PLUG_BLOCK = BLOCKS.register(
            "fluid_plug",
            () -> new FluidPlugBlock(BlockBehaviour.Properties.of().strength(2.0F).noOcclusion())
    );

    public static final DeferredHolder<Item, BlockItem> FLUID_PLUG_ITEM = ITEMS.register(
            "fluid_plug",
            () -> new BlockItem(FLUID_PLUG_BLOCK.get(), new Item.Properties())
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FluidPlugBlockEntity>> FLUID_PLUG_BE =
            BLOCK_ENTITIES.register("fluid_plug",
                    () -> BlockEntityType.Builder
                            .of(FluidPlugBlockEntity::new, FLUID_PLUG_BLOCK.get())
                            .build(null)
            );


    public QuickLinkNeoForge() {
        QuickLink.init();

        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.COMMON, QuickLinkConfig.SPEC);

        IEventBus modBus = ModLoadingContext.get().getActiveContainer().getEventBus();
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);

        modBus.addListener(this::addCreative);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent e) {
        if (e.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            e.accept(ITEM_PLUG_ITEM.get());
            e.accept(FLUID_PLUG_ITEM.get()); // <-- добавь эту строку
        }
    }
}
