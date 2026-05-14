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

## Open Bug: HUD Overlay Not Showing

**What it should do:** When the player looks at an item/fluid/energy plug block, text like `"Upgrade: none"` or `"Upgrade: tier 2 / 4 (×4)"` appears centered slightly below the crosshair.

**What's confirmed working:**
- `RegisterGuiLayersEvent` fires (no errors in log)
- `GuiLayer.render()` IS being called — confirmed via `LOGGER.info` during debug run
- `mc.level.getBlockEntity(pos)` returns the correct `ItemPlugBlockEntity` instance
- `getUpgradeTier()` returns 0 correctly
- `gui.text(mc.font, text, x, y, color, true)` is called with correct args

**What's NOT working:**
- Text never appears on screen despite the above

**Current code:** `QuickLinkHudOverlay.java` — `GuiLayer LAYER = QuickLinkHudOverlay::render`  
Registered in `NeoForgeClientEvents.registerGuiLayers` via `event.registerAboveAll(...)`.

**Hypothesis (not yet verified):**  
`GuiGraphicsExtractor.text()` in MC 26.x may require being inside a `GuiRenderState` batch that's active at layer render time. The `render(GuiGraphicsExtractor, DeltaTracker)` call might need to use `GuiRenderState` directly, or use `Font.drawInBatch()` with a `MultiBufferSource`, or use a different API.

**Things to investigate:**
1. Decompile `GuiGraphicsExtractor.text()` and trace what `GuiRenderState` it submits to — is there a `flush()` needed?
2. Look at how NeoForge's own HUD layers (crosshair, hotbar, etc.) render text — `VanillaGuiLayers` references.
3. Check if `RegisterGuiLayersEvent` in NeoForge 26.x actually passes a valid render-ready `GuiGraphicsExtractor` or a stub.
4. Maybe MC 26.x text rendering needs explicit push/pop matrix scope.

**JAR paths for API inspection:**
```
MC client:  %USERPROFILE%\.gradle\caches\neoformruntime\artifacts\minecraft_26.1.2_client.jar
NeoForge:   %USERPROFILE%\.gradle\caches\modules-2\files-2.1\net.neoforged\neoforge\26.1.2.48-beta\bcf92260d56dc7345c83a4d8e795e80282bb5b86\neoforge-26.1.2.48-beta-universal.jar
FML loader: %USERPROFILE%\.gradle\caches\modules-2\files-2.1\net.neoforged.fancymodloader\loader\11.0.13\c9c89eaf35535990110088224d188748d81eaecf\loader-11.0.13.jar
```
Use `jar xf <jar> <class>` then `javap -p <class>` to inspect APIs.

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
