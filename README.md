# VystormVeins

Configurable connected ore deposits, prospecting, safe vein mining and a personal mining journal. Generation and the journal work standalone; optional Vystorm Core integration adds localization, central commands, ore-map menus and regrowth services.

## Mining claims (1.17.0)

Right-click an ore with the prospecting rod (without sneaking) to claim the connected vein for 20 minutes – nobody else can mine it meanwhile, except your party and admins. `/veinclaim` shows your claim, `/veinclaim release` frees it.

## Personal mining journal (1.14.0)

Run `/veinjournal` to see your discoveries for ten ore families, unlocked tiers and collection goals. Per-family milestones are 1, 16, 64 and 256 discoveries; broader collection goals require 3, 6 and all 10 families. These are cosmetic achievements, with no items, money or XP rewards. Normal/deepslate variants share a family; Nether gold belongs to gold.

Only the first confirmed successful survival harvest at a position is credited to its miner. Protected/cancelled breaks, creative mining, player-placed ore, piston-moved ore and repeated regrowth at the same position do not add discoveries. Extra blocks mined by the existing vein miner use the same normal break path. A later cancellation or an unchanged block is checked on the next tick. The journal never loads chunks, searches undiscovered ore or reveals new locations.

`vystormveins.journal` defaults to true and controls tracking/reading. Progress is private player persistent data; placement/discovery markers are chunk persistent data. Both survive normal saves, reconnects and restarts without Core. They share the server's normal save/checkpoint durability rather than a separate fsynced transaction. A chunk ledger is capped at 4,096 marked positions; saturated chunks stop awarding journal progress without affecting mining. Existing player-placed ores from before installation and direct world edits that emit no placement event cannot be identified retroactively.

## Existing features

- Configurable deposit shape, materials, biome/world restrictions and deterministic generation in new chunks.
- Three supplied presets: rare-large, frequent-small and mixed; independent replacement of vanilla ore.
- Administrator chunk preview/apply with confirmation and cancellation through `/vveins`.
- Prospecting rod with localized direction/distance, sounds and optional trails.
- `/veinmine` persistent personal toggle; optional sneaking vein mining honors tools, protections, regrowth seeds and configured lava/water/gravity/unloaded-chunk safety limits.
- `/veinmap` ore-map menu through optional Core, plus bounded persistent regrowth tracking.

## Installation and compatibility

Copy `VystormVeins-1.14.0.jar` into `plugins/` and restart. Configure enabled worlds/deposits in `config.yml`; the default generation world is `world`. Existing chunks are not silently regenerated. Generation has no runtime dependency on OreVeins.

Core is optional. If installed, use **0.64.0+** (with an older Core the Core features stay off and one log line says so); rich text explicitly crosses Core's separate Bukkit library class loader through the existing bridge. Standalone messages remain English. With Core, English, German, French, Italian, Brazilian Portuguese, Russian and Simplified Chinese are available.

API paths support Paper/Purpur 1.20.6 onward and Spigot/CraftBukkit 26.2/26.3. Use the Java version required by your server; the plugin targets Java 21. Folia is unsupported.

## Build and reference

Use JDK 25 and Gradle 9.4.1: `./gradlew -PvystormCoreJar=/path/to/Core.jar build`. Core is a compile dependency even for standalone builds. `build` includes `compat1206` and `test1206` checks.

## License

Copyright (c) 2026 Esoren / Vystorm

Versions after 1.20.0 are licensed under the GNU General Public License v3.0 (GPL-3.0), see [LICENSE](LICENSE).
Version 1.20.0 and earlier were released under the MIT License ([LICENSE-MIT-until-1.20.0.txt](LICENSE-MIT-until-1.20.0.txt)) and stay available under those terms.
