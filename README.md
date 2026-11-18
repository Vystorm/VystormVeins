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

Copy `VystormVeins-<version>.jar` into `plugins/` and restart. Configure enabled worlds/deposits in `config.yml`; the default generation world is `world`. Existing chunks are not silently regenerated. Generation has no runtime dependency on OreVeins.

Core is optional. If installed, use **0.64.0+** for the Core features (with an older Core they stay off and one log line says so) and **0.65.0+** for the placeholders; rich text explicitly crosses Core's separate Bukkit library class loader through the existing bridge. Standalone messages remain English. With Core, English, German, French, Italian, Brazilian Portuguese, Russian and Simplified Chinese are available.

Built for Paper/Purpur 1.20.6 to 26.x (Java 21 bytecode, API version 1.20.6); the build compiles the plugin against the Paper 1.20.6 API and runs its adapter tests there (`compat1206`, `test1206`). The code keeps Bukkit/Spigot fallback paths for Spigot/CraftBukkit 26.2/26.3, but no live start on Spigot is recorded for this plugin. Use the Java version required by your server. Folia is unsupported.

## Switches

Every feature has an on/off switch in `config.yml`. Without Core, set them there and run `/vveins reload`; with Core they are also listed in `/vadmin features`. A missing key keeps the value the plugin used before, so an old config behaves as before; the new keys are added to an existing `config.yml` with their comments (only those keys: a missing `vein-mining` or `ore-map` section of a config from before 1.5.0 still means off). Only `tutorial.enabled` needs a restart. A switched-off feature answers "This feature is switched off on this server." and its placeholders are empty.

- Generation: `generation.enabled` (off: new chunks keep vanilla ore, nothing is removed, `/vveins preview` and `apply` are off; existing veins stay), the eleven `replace_natural.<ore>` keys and `chunk-preview.enabled`, which all need it.
- Mining: `prospecting.enabled`, `vein-mining.enabled`, `mining-safety.enabled`, `claims.enabled`, `deep-veins.enabled`, `mining-journal.enabled`.
- With Core: `ore-map.enabled`, `regrowth.enabled`, `tutorial.enabled`.

The presets and `/vveins status` and `reload` have no switch: the presets are files you copy yourself, and status and reload are needed to see and apply the switches.

## Placeholders (with Core 0.65.0)

Namespace `veins`, listed by `/vadmin placeholders veins`: `%vystorm_veins_journal_total%`, `journal_discovered`, `journal_families`, `journal_collection_tier`, `journal_<family>` and `journal_<family>_tier` (coal, iron, copper, gold, redstone, lapis, diamond, emerald, nether_quartz, ancient_debris), `claim_family`, `claim_blocks_left`, `veins_tracked`, `veins_renewable`. Short form in Vystorm texts: `{veins:journal_diamond}`.

## Build and reference

Use JDK 25 and Gradle 9.4.1: `./gradlew -PvystormCoreJar=/path/to/Core.jar build`. Core is a compile dependency even for standalone builds. `build` includes `compat1206` and `test1206` checks.

## License

Copyright (c) 2026 Esoren / Vystorm

Versions after 1.20.0 are licensed under the GNU General Public License v3.0 (GPL-3.0), see [LICENSE](LICENSE).
Version 1.20.0 and earlier were released under the MIT License ([LICENSE-MIT-until-1.20.0.txt](LICENSE-MIT-until-1.20.0.txt)) and stay available under those terms.
