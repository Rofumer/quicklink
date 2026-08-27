## Quick Link

[![Quick Link Mod Showcase](https://img.youtube.com/vi/qT2zOe4GoMY/maxresdefault.jpg)](https://youtu.be/qT2zOe4GoMY)

Quick Link adds a lightweight and intuitive wireless item, **fluid**, and **energy** transfer system to Minecraft.

## What's New

### New Block Design

* **Completely redesigned visuals** – a fully modeled 3D block with legs and side cones
* **Concrete-style textures** with colored quadrant insets on every face
* **Matching particles** – block breaking and mining particles now reflect the block's color

### Inventory Icons

* **Proper 3D item models** – all plug blocks now display their full 3D appearance in the inventory and hotbar

### Upgrade System

* **Four upgrade tiers** – insert upgrade items directly into the plug block
* **Higher throughput** – each tier increases transfer speed by **×2 / ×4 / ×8 / ×16**

### HUD Overlay

* **On-screen upgrade indicator** – when looking at a plug block, the current upgrade tier is displayed

### Item Tooltips

* **Multilingual tooltips** – all items now include helpful descriptions in **English and Chinese**

### Bug Fixes

* Fixed **round-robin distribution** when a single plug has multiple **POINT** sides — all destinations are now cycled evenly
* Fixed several **fluid transfer edge cases** for improved robustness
* Fixed a **crash (StackOverflowError)** when item, fluid or energy plugs of the same network were wired into a loop — a network is now traversed at most once per transfer

## Main Features

* **Instant wireless item, fluid and energy transfer** between containers at any distance
* **Color-based networks** – paint quadrants to link blocks together
* **Per-side configuration** – each face can act as sender or receiver (RMB + empty hand, Shift + RMB + empty hand to disable a side)
* **Visual role indicators** – easy to understand at a glance
* **Configurable transfer speed** – adjust item, energy and fluid throughput via the config file
* **Infinite Fluid Source** – right-click a Plug side of a Fluid Plug with a water bucket to turn it into an infinite water source
* **No cables, no pipes, no clutter**

## Compatibility

Tested and works alongside **Pipez** and **Mekanism** transport pipes and logistics systems without conflicts.

### FTB Teams / FTB Chunks

* With **FTB Teams** installed, networks additionally require matching team membership, not just matching color.
* With **FTB Chunks** also installed, a block inside a claimed chunk takes its network from the *claim's owning team* instead of from whoever placed it. Outside any claim, a block follows its owner's current team.
* **Why claim:** claiming is how a block becomes the team's permanently. If a player leaves the team, their blocks inside the claim stay in the team network, while their blocks outside any claim drop out of it. The same applies to blocks with no recorded owner, and to blocks placed by guests who are not team members at all.
* A block placed inside *another* team's claim joins that team's network, not its owner's — check whose claim you are building in.
* Installing FTB Chunks on an existing world normally changes nothing: blocks inside your own team's claims keep the network they already had. Only blocks sitting inside another team's claim will move.

## How It Works

1. Craft the Quick Link block.
2. Place it next to a container or tank.
3. Paint quadrants to assign it to a network.
4. Set each side as **Plug (output)** or **Point (input)**.
5. Items, fluids, and energy will automatically transfer between linked blocks.

### Network loops

A plug side is an ordinary capability handler, so a network could be routed back into itself (plug next
to plug, or through a pipe that leads back to another plug of the same colour/team). That used to
recurse until the game crashed. Each traversal now claims its network key for the duration of the call
and refuses re-entry, and a plug never treats a same-network plug -- or the side it was just fed
through -- as a destination. Item, fluid and energy networks are guarded independently, so a busy
fluid network never holds up an item transfer.

Chemical plugs were never affected: they expose an inert handler and already skip plugs as both source
and destination.

To verify by hand on a server (or single-player world), for each of the item, fluid and energy plugs:

1. Place two plugs face to face, paint both with the same colours, and set the touching faces to
   **BOTH**. Nothing should happen and the log should stay clean; before the fix, feeding either plug
   crashed the game.
2. Place plug A next to a full container (side facing it = **POINT**), plug B next to an empty one
   (side facing it = **PLUG**), same colours. Contents must still move A -> B at the normal rate.
3. Add plug C on the same network with a pipe running from C's **PLUG** side back into A's **POINT**
   side. Transfers keep working; the loop through C simply moves nothing.
4. Repeat 2 with the two plugs in different dimensions, and (for fluids) with an **Infinite Water**
   point, to confirm the normal paths are unaffected.

## Design Goals

* Simple to learn
* Minimal setup
* High performance
* Vanilla-friendly aesthetics

Perfect for compact bases, automation builds, and long-distance logistics without messy pipe systems.

## Download

* CurseForge: https://www.curseforge.com/minecraft/mc-mods/quick-link
* Modrinth: https://modrinth.com/mod/quick-link
