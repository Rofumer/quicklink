## Quick Link

[![Quick Link Mod Showcase](https://img.youtube.com/vi/qT2zOe4GoMY/maxresdefault.jpg)](https://youtu.be/qT2zOe4GoMY)

Quick Link adds a lightweight and intuitive wireless item, **fluid**, **energy**, and **chemical** transfer system to Minecraft.

## What's New

### Cross-Dimension & Unloaded Chunks

* **Networks survive chunk unloading** – plugs stay registered in their network even when their chunk is unloaded
* **Works across dimensions** – including setups where the target chunk is almost never loaded and no chunkloader can reach it
* Verified with **Compact Machines** rooms

### Jade & WTHIT Integration

* **Native Jade and WTHIT support** – look at any Plug block to see live transfer information
* View the current **upgrade tier and speed multiplier**
* See the configured **maximum transfer rate**
* Monitor actual **incoming and outgoing transfer amounts**
* Idle plugs are clearly marked as **Idle**

### FTB Teams & FTB Chunks Integration

* **Team-isolated networks** – with FTB Teams installed, matching colors only connect blocks belonging to the same team
* **Claim-aware networks** – with FTB Chunks installed, a plug inside a claimed chunk takes its network from the **claim's owning team** instead of from whoever placed it
* **Claims make blocks permanently the team's** – a plug inside your team's claim keeps serving the team even after the player who placed it leaves it, while plugs outside any claim drop out of the network with their owner. The same applies to plugs with no recorded owner and to plugs placed by guests
* **Claims do not split networks** – a claim and plain team membership of the same team form one and the same network
* A plug placed inside *another* team's claim joins **that** team's network — check whose claim you are building in

> **Note:** Installing FTB Chunks on an existing world normally changes nothing — plugs inside your own team's claims keep the network they already had. Only plugs sitting inside another team's claim will move.

### Color Removal

* **Clear individual network colors** – hold **Shift + RMB with any dye** on a quadrant to remove its color
* Easily reconfigure networks without breaking or replacing the Plug block

### New Block Design

* **Chemical Plug support** – added a new Chemical Plug block for Mekanism chemical transfer
* **Completely redesigned visuals** – a fully modeled 3D block with legs and side cones
* **Concrete-style textures** with colored quadrant insets on every face
* **Matching particles** – block breaking and mining particles now reflect the block's color

### Inventory Icons

* **Proper 3D item models** – all plug blocks now display their full 3D appearance in the inventory and hotbar

### Upgrade System

* **Four upgrade tiers** – insert upgrade items directly into the Plug block
* **Higher throughput** – each tier increases transfer speed by **×2 / ×4 / ×8 / ×16**
* **Non-destructive removal** – Shift + RMB with an empty hand pulls the upgrade back out

### HUD Overlay

* **On-screen upgrade indicator** – when looking at a Plug block, the current upgrade tier is displayed

### Item Tooltips

* **Multilingual tooltips** – all items now include helpful descriptions in **English, Russian, and Chinese**

### Bug Fixes

* Fixed **round-robin distribution** when a single Plug has multiple **POINT** sides — all destinations are now cycled evenly
* Fixed several **fluid transfer edge cases** for improved robustness
* Improved **Chemical Plug compatibility with Mekanism Pressurized Tubes**
* Fixed plugs being permanently dropped from their saved network after a chunk unload
* Fixed stale network entries left behind when a Plug was reloaded
* Fixed a **crash (StackOverflowError)** when item, fluid or energy plugs of the same network were wired into a loop — a network is now traversed at most once per transfer

## Main Features

* **Instant wireless item, fluid, energy, and chemical transfer** between compatible containers at any distance, across dimensions, even into unloaded chunks
* **Color-based networks** – paint quadrants to link blocks together
* **Team and claim-aware networks** with FTB Teams and FTB Chunks
* **Live transfer statistics** through Jade and WTHIT
* **Easy color editing** – use **Shift + RMB with any dye** to clear an individual quadrant color
* **Per-side configuration** – each face can act as sender or receiver (RMB + empty hand, Shift + RMB + empty hand to disable a side)
* **Visual role indicators** – easy to understand at a glance
* **Configurable transfer speed** – adjust item, energy, fluid, and chemical throughput via the config file
* **Infinite Fluid Source** – right-click a Plug side of a Fluid Plug with a water bucket to turn it into an infinite water source
* **Settings survive relocation** – breaking a Plug keeps its quadrant colors on the dropped item, and installed upgrades drop alongside it
* **No cables, no pipes, no clutter**

## Compatibility

Tested and works alongside **Pipez** and **Mekanism** transport pipes and logistics systems without conflicts.

### Jade / WTHIT

When **Jade** or **WTHIT** is installed, looking at a Plug displays:

* Current upgrade tier and multiplier
* Maximum transfer rate
* Last outgoing transfer amount
* Last incoming transfer amount
* Current idle status

Supports **Item**, **Fluid**, **Energy**, and **Chemical** Plugs.

### FTB Teams / FTB Chunks

* With **FTB Teams** installed, networks additionally require matching team membership, not just matching color.
* With **FTB Chunks** also installed, a block inside a claimed chunk takes its network from the *claim's owning team* instead of from whoever placed it. Outside any claim, a block follows its owner's current team.
* **Why claim:** claiming is how a block becomes the team's permanently. If a player leaves the team, their blocks inside the claim stay in the team network, while their blocks outside any claim drop out of it. The same applies to blocks with no recorded owner, and to blocks placed by guests who are not team members at all.
* A block placed inside *another* team's claim joins that team's network, not its owner's — check whose claim you are building in.
* Claim-based and membership-based networks of the same team are **one network**, not two.

## How It Works

1. Craft the Quick Link block.
2. Place it next to a container, tank, energy storage, or compatible chemical handler.
3. Paint quadrants to assign it to a network.
4. Set each side as **Plug (output)** or **Point (input)**.
5. Items, fluids, energy, or chemicals will automatically transfer between linked blocks.
6. To remove a quadrant color, hold **Shift and right-click it with any dye**.

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

Perfect for compact bases, automation builds, multiplayer servers, and long-distance logistics without messy pipe systems.

## Download

* CurseForge: https://www.curseforge.com/minecraft/mc-mods/quick-link
* Modrinth: https://modrinth.com/mod/quick-link
