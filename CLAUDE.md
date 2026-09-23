# QuickLink — Project Context for Claude

## What This Mod Does

QuickLink is a NeoForge mod that connects machines wirelessly. Three block types:
- **ItemPlugBlock** — moves items between inventories
- **FluidPlugBlock** — transfers fluids between tanks
- **EnergyPlugBlock** — transfers FE energy between machines

Each plug has a **role** (PLUG = source, POINT = destination) and a **color** for pairing. Same color = connected. Upgrade items boost transfer throughput (tier 1–4, multiplier ×2/×4/×8/×16).

## Branch & Version

- **Active migration branch:** `migration/mc-26.3`
- **Target:** MC 26.3 / NeoForge 26.3.0.12-beta (ModDevGradle 2.0.147 — 2.0.141 cannot recompile the 26.3 Minecraft sources)
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

## MC 26.x API Changes (vs 1.21.1)

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

### MC 26.3 specifics

- **Block codecs are gone.** `simpleCodec(...)` and `codec()` no longer exist on `Block`; the
  `MapCodec` field and its override were deleted from all three plug blocks.
- **`spawnDestroyParticles` lost its `Player`.** It is now `(Level, BlockPos, BlockState)`, and the
  entity-aware variant to override is `spawnDestroyByEntityParticles(Level, @Nullable Entity, BlockPos, BlockState)`.
- **The legacy fluid bridge is gone.** `net.neoforged.neoforge.fluids.capability.IFluidHandler` and
  `IFluidHandler.of(ResourceHandler)` were removed in NeoForge 26.3, so `FluidPlugBlockEntity` now
  speaks `ResourceHandler<FluidResource>` end to end: `insert`/`extract` with the caller's
  `TransactionContext` instead of `fill`/`drain` with a `FluidAction`. Consequence worth knowing:
  the side handler now honours the caller's transaction — before, a simulated insert moved fluid
  for real, because the bridge always passed `EXECUTE`.
- Probing without a transaction of your own (e.g. `ResourceHandler.getResource`) goes through
  `ResourceHandlerUtil.findExtractableResource(handler, filter, Transaction.getCurrentOpenedTransaction())`;
  passing `null` there throws if the thread already has a transaction open.

### Resources
- Recipe ingredients: `{"item":"minecraft:X"}` → `"minecraft:X"`
- Item models: need `assets/<ns>/items/<name>.json` → `{"model":{"type":"minecraft:model","model":"..."}}`
- Lang: block items need BOTH `block.ns.name` AND `item.ns.name` keys

## Build Notes

```bash
./gradlew build          # produces build/libs/QuickLink-1.1.18-26.3.jar
./gradlew runClient      # dev client (run/ dir)
./gradlew compileJava    # fast compile check
```

`build.gradle` in root handles everything. `neoforge/build.gradle` is a stub comment only.

## Block Entity Performance Pattern

All three BEs use `BlockCapabilityCache` to avoid raw `level.getCapability(...)` calls every tick:

```java
// Field (per BE, 6 entries — one per Direction):
BlockCapabilityCache<HandlerType, Direction>[] neighborCaches = new BlockCapabilityCache[6];

// Initialized in onLoad() (server-side only):
@Override
public void onLoad() {
    super.onLoad();
    if (level instanceof ServerLevel sl) {
        for (Direction side : Direction.values()) {
            neighborCaches[dirIndex(side)] = BlockCapabilityCache.create(
                Capabilities.X.BLOCK, sl,
                worldPosition.relative(side), side.getOpposite(),
                () -> !isRemoved(), () -> {}
            );
        }
        syncRegistration();
    }
}

// Access via instance method (NOT a static helper). The network key is required: a neighbour
// plug on that same network is not an endpoint, it is the network seen from the other side.
@Nullable
private HandlerType getAttachedNeighborHandler(Direction side, int excludeNetworkKey) {
    Direction face = side.getOpposite();
    if (level.getBlockEntity(worldPosition.relative(side)) instanceof ThisBE plug
            && plug.isSideEnabled(face)
            && plug.getRole(face) != SideRole.NONE
            && plug.getNetworkKey(face) == excludeNetworkKey) return null;
    BlockCapabilityCache<HandlerType, Direction> cache = neighborCaches[dirIndex(side)];
    return cache != null ? cache.getCapability()
        : level.getCapability(Capabilities.X.BLOCK, worldPosition.relative(side), face);
}
```

Network iteration uses `record Src(BEType be, Direction dir)` so that `s.be().getAttachedNeighborHandler(s.dir(), key)` goes through the owning BE's own cache.

**FluidPlugBE specifics:** `getCachedNeighborFluidHandler(Direction, int)` checks if the neighbor is itself a `FluidPlugBlockEntity` first (peer-to-peer path), then falls through to the cache. Returns `IFluidHandler.of(rh)` wrapper.

## Network Loop Guard

Plug sides are published as ordinary capabilities, so `level.getCapability(...)` on a neighbouring
plug hands back that plug's own side handler. Before 1.1.18 a network routed back into itself —
plug next to plug, or a pipe leading back to another plug of the same colour/team — recursed until
the game died with a `StackOverflowError`.

`common/.../NetworkTransferGuard.java` is a stack-scoped, thread-local set of network keys, qualified
by domain (`ITEM`, `FLUID`, `ENERGY`) so the three graphs never block each other. Every traversal —
`receiveIntoNetwork`/`fillIntoNetwork`, `extractFromNetwork`/`drainFromNetwork`, `tryPushOnce`/
`tryTransferOnce`, `peekNetworkFluid` — does:

```java
int networkKey = getNetworkKey(side);
if (!NetworkTransferGuard.enter(NetworkTransferGuard.Domain.ITEM, networkKey)) return 0;
try {
    ...
} finally {
    NetworkTransferGuard.exit(NetworkTransferGuard.Domain.ITEM, networkKey);
}
```

**Key rule:** the guard is scoped to the call stack, never to a tick. A simulated pass and the
committed pass that follows it run at the same depth and must get the same answer — never make the
guard stateful across calls. Traversals also skip the side they were just fed through
(`pBe == this && d == side`); other sides of the same plug stay valid targets.

Unit-tested in `common/src/test/java/.../NetworkTransferGuardTest.java` (`./gradlew test`) — the
fakes there reproduce the crash shape without Minecraft.

## Jade / WTHIT Tooltip Compat

Optional runtime integrations live in `neoforge/.../compat/`. Both are `compileOnly` deps (see `build.gradle` + `gradle.properties` for `jade_version`/`wthit_version`); the mod loads fine with neither installed.

- `compat/PlugCompatData.java` — reads/writes the shared NBT payload (type, max, last sent/received, tick period, upgrade tier). Each of the three BEs tracks `lastSent*`/`lastReceived*`/`pendingReceived*` fields, populated in `serverTick`.
- `compat/jade/` — `QuickLinkJadePlugin` (annotated `@WailaPlugin`, plus a `META-INF/services/snownee.jade.api.IWailaPlugin` fallback entry) registers `JadePlugDataProvider` (server) and `JadePlugRenderer` (client tooltip) for all three plug block/BE classes.
- `compat/wthit/` — registered via `neoforge/src/main/resources/waila_plugins.json` using the **`entrypoints`** schema (`{"quicklink:plugs": {"entrypoints": {"common": ..., "client": ...}, "side": "*"}}`). Do NOT use the deprecated single `"initializer"` key when common/client are split into separate classes — WTHIT silently ClassCastExceptions and drops the plugin (confirmed against the actual `wthit_plugins.json` parser in the runtime jar).
- Jade/WTHIT API packages use `Identifier` (not `ResourceLocation`) on this branch, matching the rest of the MC 26.2.0 migration.

## Open Issues

Migration to MC 26.3 builds and unit-tests clean, but nothing on this branch has been run in-game yet.
FTB Teams/Chunks have no 26.2+ release; the branch still compiles against the 26.1.2 jars (compileOnly,
optional at runtime), so the team/claim integration is untested on 26.3.

## Conventions

- No comments unless WHY is non-obvious
- Java 25 (project toolchain)
- All text resources in `common/` unless neoforge-specific (like `items/` and `ru_ru`/`zh_cn` lang)
