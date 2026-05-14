# Migration Handoff: MC 26.1.2 / NeoForge 26.1.2.48-beta

## Status as of 2026-05-14

**Branch:** `migration/mc-26.1.2`  
**Build:** PASSING — `./gradlew build` produces `build/libs/QuickLink-1.0.9-26.1.2.jar`  
**In-game:** Blocks load, register, recipe/lang errors fixed. **One open bug: HUD overlay not rendering.**

---

## What's Done (all committed, pushed)

| Commit | What |
|--------|------|
| `0c42b18` | Build: ModDevGradle, Gradle 9, merged common+neoforge srcDirs |
| `2cd18b7` | Full Java API migration (18 files) |
| `87c7a2f` | Block/item `setId()` fix; recipe ingredient format; `items/` model JSONs |
| `7411c5f` | Lang: `item.*` keys, `tooltip.item.*` keys, en_us into common/ |
| `5e62d72` | HUD overlay: switched from `RenderGuiEvent.Post` to `RegisterGuiLayersEvent` + `GuiLayer` |

---

## HUD Overlay — FIXED

**Root cause (found via bytecode decompile):**  
`GuiGraphicsExtractor.text()` in MC 26.x checks `ARGB.alpha(color) == 0` and immediately returns without rendering.  
Colors like `0xAAAAAA` and `0xFFD700` are 24-bit RGB — in 32-bit ARGB their alpha byte is **0x00**, so all draws were silently discarded.

**Fix:** Prepend `0xFF` alpha byte to all colors passed to `gui.text()`:
```java
// Before (broken — alpha=0):
int color = tier == 0 ? 0xAAAAAA : 0xFFD700;
// After (fixed — alpha=0xFF):
int color = tier == 0 ? 0xFFAAAAAA : 0xFFFFD700;
```

**Architecture confirmed via decompile:**  
- `Gui.extractRenderState()` calls `layerManager.render(gui, delta)` — our `GuiLayer` is called correctly  
- `gui.text()` submits to retained-mode `GuiRenderState` buffer — no flush needed, works same as vanilla layers  
- `registerAboveAll` correctly places our layer last in the list

---

## Key API Facts (MC 26.1.2)

### Registration
```java
// DeferredRegister factory must receive Identifier, wrap into ResourceKey:
BLOCKS.register("name", id -> new MyBlock(
    Block.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id)).strength(0.3f)
));
ITEMS.register("name", id -> new MyItem(
    new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id))
));
```

### SavedData (network managers)
```java
static final Codec<MyManager> CODEC = RecordCodecBuilder.create(...);
private static final SavedDataType<MyManager> TYPE = new SavedDataType<>(
    Identifier.fromNamespaceAndPath("quicklink", "name"), MyManager::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE
);
public static MyManager get(ServerLevel level) {
    return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
}
// ResourceKey.identifier() — not .location()
```

### BlockEntityRenderer
```java
class MyRenderer implements BlockEntityRenderer<MyBlockEntity, MyRenderer.State> {
    public static class State extends BlockEntityRenderState { int lightCoords; }
    public State createRenderState() { return new State(); }
    public void extractRenderState(MyBlockEntity be, State state, float partial,
                                   Vec3 cameraPos, ModelFeatureRenderer.CrumblingOverlay c) {
        BlockEntityRenderState.extractBase(be, state, c);
    }
    public void submit(State state, PoseStack ps, SubmitNodeCollector col, CameraRenderState cam) {
        TextureAtlasSprite sprite = Minecraft.getInstance().getAtlasManager()
            .get(new SpriteId(TextureAtlas.LOCATION_BLOCKS, WHITE_TEX));
        col.submitCustomGeometry(ps, RenderTypes.entityCutout(TextureAtlas.LOCATION_BLOCKS),
            (pose, vc) -> { /* vertices */ });
    }
}
```

### Other renamed APIs
- `ResourceLocation` → `Identifier` (`net.minecraft.resources.Identifier`)
- `LightTexture` → `LightCoordsUtil` (`net.minecraft.util.LightCoordsUtil`)
- `RenderType.entityCutoutNoCull(atlas)` → `RenderTypes.entityCutout(TextureAtlas.LOCATION_BLOCKS)`
- `InventoryMenu.BLOCK_ATLAS` → `TextureAtlas.LOCATION_BLOCKS`
- `Minecraft.getTextureAtlas().apply(id)` → `Minecraft.getInstance().getAtlasManager().get(new SpriteId(atlas, id))`
- `BlockEntityType.Builder.of().build(null)` → `new BlockEntityType<>(supplier, block)`
- `appendHoverText(stack, ctx, tips, flag)` → `appendHoverText(stack, ctx, display, tooltipAdder, flag)`; `tips.add()` → `tooltipAdder.accept()`
- `GuiGraphics` → `GuiGraphicsExtractor` (`net.minecraft.client.gui.GuiGraphicsExtractor`)
- `GuiGraphics.drawString(...)` → `GuiGraphicsExtractor.text(...)`
- `ItemResource.isBlank()` → `ItemResource.isEmpty()`
- `level.isClientSide` (field) → `level.isClientSide()` (method)
- `DyeItem.getDyeColor(stack)` → `stack.get(DataComponents.DYE)`
- `EventBusSubscriber.Bus.MOD/GAME` — parameter removed from `@EventBusSubscriber` in FML 11; auto-routes by `IModBusEvent`
- `ModLoadingContext.get().getActiveContainer()` → inject `ModContainer` in `@Mod` constructor
- `BlockItem.getDescriptionId()` returns `item.*` prefix (not `block.*`) in 26.x

### Resources (changed formats)
- Shaped recipe ingredients: `{"item":"minecraft:X"}` → `"minecraft:X"` (plain string)
- Item models: need `assets/<ns>/items/<name>.json` with `{"model":{"type":"minecraft:model","model":"<ns>:item/<name>"}}`
- HUD layers: `RegisterGuiLayersEvent` (mod bus) + `GuiLayer` interface, not `RenderGuiEvent.Post`
- Lang keys for block items: both `block.ns.name` AND `item.ns.name` needed

### Build (build.gradle)
```groovy
tasks.withType(AbstractCopyTask).configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE  // common/ wins over neoforge/ on conflict
}
sourceSets.main {
    java { srcDirs 'common/src/main/java', 'neoforge/src/main/java' }
    resources { srcDirs 'common/src/main/resources', 'neoforge/src/main/resources' }
}
```
**Warning:** `DuplicatesStrategy.EXCLUDE` means common/ resources win. If neoforge/ has the authoritative version of a file that also exists in common/, you must update common/ or delete the common/ copy.

### Non-obvious decisions
- `waterAccumBySide` stored as 6 individual longs (`"ql_waccum_0"` … `"ql_waccum_5"`) — `ValueOutput` has no `putLongArray`
- Renderer `PlugRenderState` uses primitive arrays — `extractRenderState` is game thread, `submit` is render thread
- `IFluidHandler.of(ResourceHandler<FluidResource>)` wraps capability for internal FluidPlugBlockEntity logic
