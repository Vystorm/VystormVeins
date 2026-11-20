## 1.22.1 — 2026-11-20

- Fixed: when `config.yml` has a YAML mistake, the answer of `/vveins reload` and the console now name the line and column of the mistake (before, only "while scanning a simple key"). The file is never changed; the previous settings stay active on a reload, and at start the plugin stays off until the file is fixed.
- With Vystorm Core, `/vadmin features` names every switch of the plugin in seven languages ("Replace vanilla diamond ore", "Mining safety") instead of the key path ("Replace natural › Diamond"). Core stays optional.
- A feature that needs another switch now names it in full: "This needs “Ore veins in new chunks” (generation.enabled), which is switched off on this server." Before, the message only gave the key. Without Core the name comes in English. A language file already on your server keeps its own text.
- README: the supported servers are written as tested: Paper and Purpur 26.2, Paper 1.20.6 and Spigot 26.2.

## 1.22.0 — 2026-11-18

- Vystorm Core stays optional. The Core features still need Core 0.64.0 or newer; the new placeholders need Core 0.65.0 or newer and are simply off with an older Core.
- Every feature can now be switched on and off in `config.yml`, also without Core (`/vveins reload` applies them). A missing key keeps the value the plugin used before, so a server that updates keeps working as before. The new keys are added to your existing `config.yml` with their comments, and only those keys: a missing `vein-mining` or `ore-map` section of a config from before 1.5.0 still means off. With Core each switch is listed in `/vadmin features` with a one-line description in seven languages. Only `tutorial.enabled` needs a restart.
  - The generation itself: `generation.enabled`. Off: new chunks keep vanilla ore, no vanilla ore is removed, and `/vveins preview`, `apply` and `cancel` are switched off. Existing veins stay. Prospecting, vein mining, claims and the journal keep working on any ore.
  - New switches: `chunk-preview.enabled` (needs `generation.enabled`), `mining-journal.enabled` (off: `/veinjournal` is switched off and nothing is counted; the journals stay on the players), `tutorial.enabled` (needs a restart).
  - Keys that already existed keep their names and are now listed as switches too: the eleven `replace_natural.<ore>` keys (they need `generation.enabled`), and the `enabled` keys of prospecting, vein mining, mining safety, the ore map, claims, deep veins and regrowth.
- A switched-off feature answers one shared message, "This feature is switched off on this server.", and with Core its commands are hidden from help and tab completion. A feature that needs another switch names it. The ore map and claims now use this message too.
- Tab completion and help follow a switch at once, without registering the command again. This needs Core 0.65.0; with an older Core they follow a switch after a restart.
- Fix (also in 1.21.0): `/vveins reload` read `config.yml` without the plugin's built-in values, so a config from before 1.18.0 without a `deep-veins` section lost the deep-vein generation after a reload while the start had it. Reload now reads the config like the start: a missing `deep-veins` section uses the built-in values, a missing `vein-mining` or `ore-map` section still means off, and a missing `deposits` section is still rejected. `/vveins status` says "Deep veins: on" only while deep veins are really generated.
- Placeholders (with Core 0.65.0): the plugin registers its values under the namespace `veins`: journal discoveries in total and per ore family with their tiers, discovered families, the collection tier, the player's mining claim and the regrowth counters. Examples: `%vystorm_veins_journal_total%` (all discoveries of the player) and `{veins:claim_family}` (the ore of the player's claim). `/vadmin placeholders veins` lists them. A value of a switched-off feature is empty.
- README: the jar name no longer names version 1.14.0, and the compatibility line says what the build checks (Paper 1.20.6 API) instead of claiming a Spigot run that is not recorded for this plugin.

## 1.21.0 — 2026-11-17

- Vystorm Core stays optional. If Core is installed, it must be 0.64.0 or newer; with an older Core the Core-based features are off.
- The order in which Vystorm plugins react to the same event is now set in one place, Vystorm Core (`event-order` in Core's `config.yml`). The protection of mining claims now denies before other plugins act. Without Core, Veins keeps handling these events on its own.
- License: GPL-3.0 from this version. Earlier versions stay under the MIT License.

## 1.20.0 — 2026-11-16

- `/vveins status` now also shows the mining claims (duration and size) and the deep veins (players needed, radius and depth), or that they are switched off. In all seven languages.

## 1.19.0 — 2026-11-15

- Rebuilt the ore map as a Core Panel with chunk selection, ore details, filtering and rescanning. The original chest menu remains as the fallback for unavailable panels or older Core; there are no item-transfer menus in this plugin.

## 1.18.2 — 2026-11-14

- Fixed: `/veinclaim` and `/veinclaim release` from the console now say to run them in game instead of answering nothing.

## 1.18.1 — 2026-11-12

- Deep veins are smaller and rarer by default: size 24 (about 6 netherite ingots per vein) and chance 0.01 per chunk, about a tenth of the Nether's ancient debris. Servers that still have the untouched 1.18.0 values (48 / 0.02) are switched automatically; changed values stay.
- New installs hide ancient debris from the prospecting rod (`prospecting.hidden-ores`), so deep veins are found by digging, not by scanning.

## 1.18.0 — 2026-11-12

- Deep veins: rare, large veins of ancient debris far down in new chunks. Their core blocks can only be mined while enough players are close together (default 3 within 12 blocks); every miner nearby gets XP for each core block. Placed core blocks are ordinary blocks. Settings under `deep-veins` (ore, depth, size, chance, players, radius, XP), also in the first-run setup.

## 1.17.0 — 2026-11-11

- Mining claims: right-click an ore with the prospecting rod (without sneaking) to claim the whole connected vein for 20 minutes. Others cannot mine it until the claim ends; you, your party and admins can. One claim per player, `/veinclaim` shows it and `/veinclaim release` frees it. Settings under `claims`, also in the first-run setup.

## 1.16.0 — 2026-11-11

- Short tutorial in the main menu (/vmenu → Tutorials) in all seven languages.
- The regrowth warning and all config comments are English now; the warning comes from the language files in each player's language (warning-message: default).

## 1.15.0 — 2026-11-10

- First-run setup: when the plugin is installed for the first time, admins answer a few questions or take the recommended settings, with a preview before anything is saved (needs Vystorm Core 0.54.0 or newer).
- All settings, including extra configuration files, appear in Core's new `/vadmin config` menu.
- Fixed an error when breaking blocks that are not ores.

## 1.14.0 — 2026-11-07

- Add a standalone-capable personal mining journal with ore-family tiers, collection goals and persistent first-harvest/placement guards.
- Include all seven bundled languages and targeted persistence, ownership and cancellation tests.
- Preserve Bukkit/Spigot fallback paths and existing Paper behavior.

## 1.13.1 (2026-11-07)

- Fix the Bukkit/Spigot class-loader conflict between optional Core and standalone Adventure libraries. Localized messages, clickable ore-map hints, names and lore now cross that boundary as lossless JSON.
- Keep standalone operation and shared Paper Adventure behavior. Add a regression using two isolated Adventure class loaders.

## 1.13.0 (2026-11-07)

- Keep standalone operation without Core: declare Adventure libraries and use Bukkit legacy chat serialization; optional menus require the new Core API.
- Use shared Core compatibility adapters for Bukkit/Spigot rich text, menus and the server operations used by this plugin.
- Requires Vystorm Core 0.51.0 for Core integration. Server acceptance is recorded separately; a successful build alone does not establish runtime compatibility.

## 1.12.0 (2026-11-06)

- Add complete French (`fr`), Brazilian Portuguese (`pt_br`), Italian (`it`) and Russian (`ru`) language bundles. Minecraft client locales and the Core language selector choose them automatically.
- Validate packaged language keys, placeholders, formatting and tutorial coverage in server-loader tests.

## 1.10.0 (2026-11-03)

- Runs on Paper 1.20.6 – 26.x. Java 21 bytecode and minimum API version 1.20.6.
- Fix biome key access across the old enum and new interface APIs using their shared Keyed interface.
- Add offline compat1206 compilation to check, plus compatibility tests against both APIs, including material detection and icon fallback.
- No features disabled on older servers; the ore map keeps its chest menu. Configured materials/biomes must exist on the running server (unknown entries retain validation errors); unavailable map icons fall back to stone. Optional Core requirements unchanged. Live Paper 1.20.6 verification pending.

## 1.9.0 (2026-11-03)

- New: configurable mining safety checks every extra vein-mining block immediately before breaking it. Stops the remaining batch before exposing adjacent lava, optional water (including waterlogged blocks), or a gravity block directly above. Reports the reason, ore coordinates and completed extra breaks in English, German and Simplified Chinese. Unloaded neighbours are never loaded and stop the batch when they cannot be checked.
- New `mining-safety.enabled`, `lava`, `water` and `falling-blocks` settings, validated on reload; `/vveins status` reports the switch. New installations enable lava and falling-block protection. Missing keys are added to existing configs at startup with protection disabled, preserving existing choices. Manual mining is unchanged.

## 1.8.0 (2026-11-02)

- New: /veinmine toggles vein mining for the individual player, so players can mine single ores even on servers with sneak disabled. The preference is saved in player data across reconnects and regular restarts. Uses the existing vystormveins.veinmine permission; server settings and tool requirements still apply. Core provides localized help and root aliases /aderabbau and /连锁采矿; without Core the English command works. English, German and Simplified Chinese texts included.

## 1.7.0 (unreleased)

- Vein mining: the items and experience of the extra blocks now land where you broke the first ore, instead of scattered along the vein and down cave walls (the experience as one orb). Every block is still a normal player break, so protection plugins, Fortune, Silk Touch and drop plugins work as before. New `vein-mining.collect-drops` (default `true`; `false` restores the old behaviour).

## 1.6.0 (unreleased)

Needs Vystorm Core 0.45.0 for localized commands; with an older Core or without Core the English commands work as before.

- Simplified Chinese (zh_cn) translation.
- `/vveins` and `/veinmap` now run through Vystorm Core's command system: subcommands, help, tab completion and error messages in the player's language (`/vveins vorschau`, `/vveins 预览` work next to the English names for everyone), a clickable `/vveins help` (`hilfe`, `帮助`), a "did you mean" for typos and entries in `/vhelp`. New localized root commands `/erzadern` (German) and `/矿脉` (Chinese) for `/vveins`, `/矿石地图` (Chinese) for `/veinmap`, from the language files (`commands.*`); which languages are active is set server-wide in Core (`commands.localized-aliases`). `/vveins`, `/veinmap`, `/oremap` and `/erzkarte` stay. Because Vystorm Veins also runs without Core, the commands stay declared in plugin.yml and Core adopts them.

## 1.5.0 (2026-10-30)

- New: vein mining. Sneak + break an ore with a fitting pickaxe and the whole connected vein of that ore comes along (also diagonal blocks and the deepslate variant), nearest blocks first, up to `vein-mining.max-blocks` (default 32, 1–128) and never more than 16 blocks away. Every extra block is a normal player break: protection plugins can deny it, Fortune and Silk Touch work, the pickaxe wears per block, experience drops. `protect-tool` stops before the pickaxe breaks, `respect-regrowth` leaves the 10 % a renewable vein needs to grow back, `excluded-ores` skips chosen ores (rejected with the reason by /vveins reload if unknown). Survival mode only. Permission vystormveins.veinmine (default: everyone).
- New: ore map. /veinmap (aliases /oremap, /erzkarte), the /vmenu entry "Ore map" (World) or the clickable [Ore map] button under every prospecting-rod result opens a chest map of the 9 × 5 chunks around the player, north up. Each tile shows the most common ore of that chunk with its count, the tooltip lists all ores; only a height band around the player is counted (`ore-map.depth`, default 8 blocks up and down). Bottom row: rescan, ore filter (left/right-click), close. Clicking a tile names its direction and distance in chat. Reads only loaded chunks and counts them off the main thread; `prospecting.hidden-ores` also hides ores from the map. Per-player cooldown `ore-map.cooldown-seconds` (default 30). Needs Vystorm Core. Permission vystormveins.map (default: everyone).
- Both features are on in the bundled config.yml for new installs. An existing config.yml without the new `vein-mining` / `ore-map` sections keeps them off, so updating does not change your gameplay; add the sections and run /vveins reload. /vveins status shows both.
- English and German texts for everything new (lang/en.yml, lang/de.yml).

## 1.4.0 (2026-10-29)

- New: `prospecting.hidden-ores` keeps chosen ores off the prospecting rod, for example `[diamond, ancient_debris]` so the rod is no X-ray for valuables. Accepts the vanilla family names known from `replace_natural` or blocks from your deposits; deepslate variants are hidden together with the normal ore. Unknown names are rejected by /vveins reload with the reason. Default: nothing hidden. /vveins status lists the hidden ores.
- Fixed: the particle trail of the prospecting rod pointed at the wrong spot when you moved during its 1-second display. The ore position is now fixed at the moment of the scan, so all three bursts point at the same ore.

## 1.3.1 (2026-10-28)

- Registers itself for the Vystorm Core update check (Core 0.40.2+); older Cores are unaffected.

## 1.3.0 (2026-10-28)

- Texts are now English and German: with Vystorm Core through its language system (`lang/en.yml`, `lang/de.yml`, each player's language, `/vadmin language audit`), without Core in English. Before, all texts were German-only in `config.yml`. Texts you changed under `messages:` or `ore-names:` keep working and win.
- New: prospecting rod. Sneak + right-click with an amethyst shard to scan a 10-block radius. Chat lists the nearest ore of up to three families with distance, compass direction and height (for example "Eisen: 7 Blöcke, nordöstlich, weiter unten – 12 Stück in Reichweite"). A coloured particle trail points to the nearest ore, and an amethyst chime rises in pitch the closer it is. Covers all vanilla ores plus the ores from your deposits; only loaded chunks are scanned. Permission vystormveins.prospect (default: everyone). Item, sneak requirement, radius (2–16), cooldown, particles and line count are configurable under prospecting, ore names under ore-names. An invalid item is rejected by /vveins reload.
- /vveins status now also shows the regrowth state (off, or tracked veins, how many are still renewable and tracked original blocks) and whether the prospecting rod is active.

## 1.2.2 (2026-10-27)

- /vveins reload now shows why an invalid config was rejected (for example a missing deposits section, an unknown block or a YAML syntax error) directly in chat. The previous settings stay active, as before.
- /vveins apply reports an expired preview separately instead of the generic "run preview first" message.
- /vveins cancel tells you when there was no pending preview instead of claiming one was cancelled.
- New message keys reload-failed, no-preview and preview-expired; existing configs fall back to the bundled defaults. The bundled presets now list cancel in their usage text.

## 1.2.1 (2026-10-26)

- Discard pending apply previews when a player changes worlds. Returning to the old chunk requires a new preview, preventing stale confirmations after travel.

## 1.2.0 (2026-10-25)

- Optional renewable generated veins: retain at least 10% of the original connected ore component (rounded up), and mined cells slowly regrow. Below that threshold the vein is permanently exhausted.
- Default growth is one block per vein per 60 seconds, with no offline catch-up or chunk loading. Buildings, liquids, player replacements and entity-occupied cells are not overwritten. Growth fires a cancellable BlockSpreadEvent.
- At 80% mined / 20% remaining, warn the miner with three vanilla note-block bass sounds six ticks apart and one configurable chat message. Persist the once-per-vein warning; it never rearms after regrowth or restart. Sound and message can be disabled independently.
- Add per-deposit overrides for regrowth enabled, interval, warning sound and warning message, inheriting global defaults. Preserve the exact generating rule ID even when nodes use the same ore material.
- Use Vystorm Core TaskGroup, VersionedStore and AtomicFiles for scheduling and persistent original sizes, depletion and warning state. Core is optional for generation, required for regrowth. Regrowth defaults to disabled and tracks new generation/application only.

## 1.1.2 (2026-10-25)

- Add /vveins cancel to discard your pending chunk preview without changing blocks. The existing admin permission applies; apply requires a fresh preview afterward.

# 1.1.1 (2026-10-24)

Reject surplus command arguments before preview/apply can run, so a mistyped apply command cannot modify the current chunk. Discard pending previews when a player logs out; a new session requires a fresh preview.

# 1.1.0 (2026-10-23)

Ship three complete, editable config templates: rare-large, frequent-small, mixed. Export missing templates without overwriting user edits. Add per-family replace_natural switches independent of custom vein rules. Plan custom veins before vanilla replacement so switches do not change their positions. Retain legacy replace-vanilla configs. Add automatic world-generation acceptance fixture and selective replacement tests.

# 1.0.0 (2026-10-23)

Initial independently implemented plugin with a new configuration/data schema. See README.md for supported behavior and validation limits.
