package com.maximpolyakov.quicklink.forge;

import com.maximpolyakov.quicklink.QuickLink;
import com.maximpolyakov.quicklink.forge.block.ChemicalPlugBlock;
import com.maximpolyakov.quicklink.forge.block.EnergyPlugBlock;
import com.maximpolyakov.quicklink.forge.block.FluidPlugBlock;
import com.maximpolyakov.quicklink.forge.block.ItemPlugBlock;
import com.maximpolyakov.quicklink.forge.blockentity.ChemicalPlugBlockEntity;
import com.maximpolyakov.quicklink.forge.blockentity.EnergyPlugBlockEntity;
import com.maximpolyakov.quicklink.forge.blockentity.FluidPlugBlockEntity;
import com.maximpolyakov.quicklink.forge.blockentity.ItemPlugBlockEntity;
import com.maximpolyakov.quicklink.forge.config.QuickLinkConfig;
import com.maximpolyakov.quicklink.forge.item.QuickLinkPlugBlockItem;
import com.maximpolyakov.quicklink.forge.item.QuickLinkUpgradeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(QuickLink.MOD_ID)
public final class QuickLinkForge {

    public static final boolean MEKANISM_LOADED = ModList.get().isLoaded("mekanism");

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, QuickLink.MOD_ID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, QuickLink.MOD_ID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, QuickLink.MOD_ID);

    // Item Plug
    public static final RegistryObject<Block> ITEM_PLUG = BLOCKS.register("item_plug",
            () -> new ItemPlugBlock(BlockBehaviour.Properties.of().strength(0.3f).noOcclusion()));

    public static final RegistryObject<Item> ITEM_PLUG_ITEM = ITEMS.register("item_plug",
            () -> new QuickLinkPlugBlockItem(ITEM_PLUG.get(), new Item.Properties()));

    @SuppressWarnings("unchecked")
    public static final RegistryObject<BlockEntityType<ItemPlugBlockEntity>> ITEM_PLUG_BE =
            (RegistryObject<BlockEntityType<ItemPlugBlockEntity>>) (Object) BLOCK_ENTITIES.register("item_plug",
                    () -> BlockEntityType.Builder.of(ItemPlugBlockEntity::new, ITEM_PLUG.get()).build(null));

    // Fluid Plug
    public static final RegistryObject<FluidPlugBlock> FLUID_PLUG_BLOCK = BLOCKS.register("fluid_plug",
            () -> new FluidPlugBlock(BlockBehaviour.Properties.of().strength(0.3f).noOcclusion()));

    public static final RegistryObject<BlockItem> FLUID_PLUG_ITEM = ITEMS.register("fluid_plug",
            () -> new QuickLinkPlugBlockItem(FLUID_PLUG_BLOCK.get(), new Item.Properties()));

    @SuppressWarnings("unchecked")
    public static final RegistryObject<BlockEntityType<FluidPlugBlockEntity>> FLUID_PLUG_BE =
            (RegistryObject<BlockEntityType<FluidPlugBlockEntity>>) (Object) BLOCK_ENTITIES.register("fluid_plug",
                    () -> BlockEntityType.Builder.of(FluidPlugBlockEntity::new, FLUID_PLUG_BLOCK.get()).build(null));

    // Energy Plug
    public static final RegistryObject<EnergyPlugBlock> ENERGY_PLUG_BLOCK = BLOCKS.register("energy_plug",
            () -> new EnergyPlugBlock(BlockBehaviour.Properties.of().strength(0.3f).noOcclusion()));

    public static final RegistryObject<BlockItem> ENERGY_PLUG_ITEM = ITEMS.register("energy_plug",
            () -> new QuickLinkPlugBlockItem(ENERGY_PLUG_BLOCK.get(), new Item.Properties()));

    @SuppressWarnings("unchecked")
    public static final RegistryObject<BlockEntityType<EnergyPlugBlockEntity>> ENERGY_PLUG_BE =
            (RegistryObject<BlockEntityType<EnergyPlugBlockEntity>>) (Object) BLOCK_ENTITIES.register("energy_plug",
                    () -> BlockEntityType.Builder.of(EnergyPlugBlockEntity::new, ENERGY_PLUG_BLOCK.get()).build(null));

    // Chemical Plug
    public static final RegistryObject<ChemicalPlugBlock> CHEMICAL_PLUG_BLOCK = BLOCKS.register("chemical_plug",
            () -> new ChemicalPlugBlock(BlockBehaviour.Properties.of().strength(0.3f).noOcclusion()));

    public static final RegistryObject<BlockItem> CHEMICAL_PLUG_ITEM = ITEMS.register("chemical_plug",
            () -> new QuickLinkPlugBlockItem(CHEMICAL_PLUG_BLOCK.get(), new Item.Properties()));

    @SuppressWarnings("unchecked")
    public static final RegistryObject<BlockEntityType<ChemicalPlugBlockEntity>> CHEMICAL_PLUG_BE =
            (RegistryObject<BlockEntityType<ChemicalPlugBlockEntity>>) (Object) BLOCK_ENTITIES.register("chemical_plug",
                    () -> BlockEntityType.Builder.of(ChemicalPlugBlockEntity::new, CHEMICAL_PLUG_BLOCK.get()).build(null));

    // Upgrade item
    public static final RegistryObject<Item> UPGRADE_ITEM = ITEMS.register("quicklink_upgrade",
            () -> new QuickLinkUpgradeItem(new Item.Properties().stacksTo(64)));

    public QuickLinkForge() {
        QuickLink.init();

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, QuickLinkConfig.SPEC);

        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);

        modBus.addListener(this::addCreative);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent e) {
        // Add to "Search" / all tabs — Forge 1.20.1 uses tab key or name comparison
        e.accept(new ItemStack(ITEM_PLUG_ITEM.get()));
        e.accept(new ItemStack(FLUID_PLUG_ITEM.get()));
        e.accept(new ItemStack(ENERGY_PLUG_ITEM.get()));
        if (MEKANISM_LOADED) e.accept(new ItemStack(CHEMICAL_PLUG_ITEM.get()));
        e.accept(new ItemStack(UPGRADE_ITEM.get()));
    }
}
