# QuickLink — Project Context for Claude

## What This Mod Does

QuickLink is a NeoForge mod that connects machines wirelessly. Three block types:
- **ItemPlugBlock** — moves items between inventories
- **FluidPlugBlock** — transfers fluids between tanks
- **EnergyPlugBlock** — transfers FE energy between machines

Each plug has a **role** (PLUG = source, POINT = destination) and a **color** for pairing. Same color = connected. Upgrade items boost transfer throughput (tier 1–4, multiplier ×2/×4/×8/×16).

## Branch & Version

- **Active migration branch:** `migration/mc-26.1.2`
- **Target:** MC 26.1.2 / NeoForge 26.1.2.48-beta
- **Main branch:** `master` (still MC 1.21.1)

## Project Structure

```
QuickLink/
├── build.gradle              # Root build — MDG, merged srcDirs
├── common/src/main/
│   ├── java/                 # Shared logic (QuickLink.java, UpgradeTier, QuickLinkConfig, etc.)
│   └── resources/            # Shared assets (blockstates, models, lang/en_us.json, recipes)
└── neoforge/src/main/
    ├── java/                 # NeoForge-specific code (blocks, BEs, renderers, network, items)
    └── resources/            # NeoForge-specific assets (lang/ru_ru.json, lang/zh_cn.json, items/)
```

**Key rule:** `common/` resources win over `neoforge/` on filename conflict (`DuplicatesStrategy.EXCLUDE`, common is first in srcDirs). If you change a file that exists in both, update `common/`.

## Key Files

| File | Purpose |
|------|---------|
| `common/.../QuickLink.java` | MOD_ID = `"quicklink"`, `init()` |
| `common/.../UpgradeTier.java` | `MAX_TIER=4`, `multiplier(tier)` |
| `common/.../QuickLinkConfig.java` | Forge config: move/transfer batch sizes |
| `neoforge/.../QuickLinkNeoForge.java` | DeferredRegisters for blocks/items/BEs; capabilities; creative tab |
| `neoforge/.../block/{Item,Fluid,Energy}PlugBlock.java` | Block logic: right-click role cycle, dye color, upgrade interaction |
| `neoforge/.../blockentity/{Item,Fluid,Energy}PlugBlockEntity.java` | Server tick, capability provider, NBT save/load, client sync |
| `neoforge/.../client/{Item,Fluid,Energy}PlugBlockEntityRenderer.java` | New 2-type-param renderer: `createRenderState/extractRenderState/submit` |
| `neoforge/.../client/NeoForgeClientEvents.java` | Registers BE renderers + HUD GuiLayer |
| `neoforge/.../client/QuickLinkHudOverlay.java` | HUD layer: shows upgrade tier when looking at plug block |
| `neoforge/.../network/QuickLink{Item,Fluid,Energy}NetworkManager.java` | `SavedData` with Codec — persists plug networks per world |

## MC 26.1.2 API Changes (vs 1.21.1)

### Registration
```java
// Must pass Identifier to factory, wrap into ResourceKey for setId():
BLOCKS.register("name", id ->
    new MyBlock(Block.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id))));
ITEMS.register("name", id ->
    new MyItem(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id))));
```

### GUI / HUD
- `GuiGraphics` → `GuiGraphicsExtractor`
- HUD layers: use `RegisterGuiLayersEvent` (mod bus) + `GuiLayer` interface  
  `event.registerAboveAll(Identifier, GuiLayer)` — NOT `RenderGuiEvent.Post` (fires after flush)

### Rendering
- `BlockEntityRenderer<BE>` → `BlockEntityRenderer<BE, RenderState>`
- New methods: `createRenderState()`, `extractRenderState(be, state, ...)`, `submit(state, ...)`
- `LightTexture` → `LightCoordsUtil`
- `RenderType.entityCutoutNoCull(atlas)` → `RenderTypes.entityCutout(TextureAtlas.LOCATION_BLOCKS)`
- Sprite: `Minecraft.getInstance().getAtlasManager().get(new SpriteId(atlas, id))`

### SavedData
```java
// Codec-based, no more Factory/save(CompoundTag):
private static final SavedDataType<T> TYPE = new SavedDataType<>(id, T::new, CODEC, DataFixTypes...);
level.getDataStorage().computeIfAbsent(TYPE);  // no factory lambda
// ResourceKey.identifier() not .location()
```

### Other renames
- `ResourceLocation` → `Identifier`
- `appendHoverText`: new params `TooltipDisplay display, Consumer<Component> tooltipAdder`; `tips.add()` → `tooltipAdder.accept()`
- `level.isClientSide` field → `level.isClientSide()` method
- `DyeItem.getDyeColor(stack)` → `stack.get(DataComponents.DYE)`
- `@EventBusSubscriber` lost `bus=` param — FML 11 auto-routes by `IModBusEvent`
- `BlockItem.getDescriptionId()` returns `item.*` not `block.*`

### Resources
- Recipe ingredients: `{"item":"minecraft:X"}` → `"minecraft:X"`
- Item models: need `assets/<ns>/items/<name>.json` → `{"model":{"type":"minecraft:model","model":"..."}}`
- Lang: block items need BOTH `block.ns.name` AND `item.ns.name` keys

## Build Notes

```bash
./gradlew build          # produces build/libs/QuickLink-1.0.9-26.1.2.jar
./gradlew runClient      # dev client (run/ dir)
./gradlew compileJava    # fast compile check
```

`build.gradle` in root handles everything. `neoforge/build.gradle` is a stub comment only.

## Open Issues

**HUD overlay not rendering** (`QuickLinkHudOverlay`):
- Event fires ✓, BE found ✓, `gui.text()` called ✓, but nothing appears on screen
- Tried: `RenderGuiEvent.Post` (doesn't work — fires after GuiRenderState flush)
- Tried: `RegisterGuiLayersEvent` + `registerAboveAll` (current code — still not working)
- Next to investigate: how does NeoForge 26.x `GuiLayer.render()` actually commit draws?
  Check `VanillaGuiLayers` source or decompile `GuiLayerManager` to see render flow.

## Conventions

- No comments unless WHY is non-obvious
- Java 25 (project toolchain)
- All text resources in `common/` unless neoforge-specific (like `items/` and `ru_ru`/`zh_cn` lang)
