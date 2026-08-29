# Changelog

All notable changes to Kyokalith are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/); versions follow [SemVer](https://semver.org/). The release CI extracts the matching `## [x.y.z]` section as the GitHub Release notes — a tag without a section here fails the release on purpose.

## [1.5.0] - 2026-08-29

### Fixed

- **Explosions (TNT, beds, end crystals) stalled the ticking thread for hundreds of milliseconds.** Players reported that blast mining — the standard way to look for ancient debris — dropped TPS in whatever region it happened in. Reproduced on the test server: a 512-block-capped blast in solid netherrack at Y15 pushed the worst tick from a 2 ms idle baseline to **291–500 ms**. With the plugin unloaded the same blast cost 3–18 ms, so all of it was Kyokalith. **Three independent causes, each measured, none guessed:**

  1. **A fresh JDBC connection per block in the blast list.** `OreLifecycleListener.onEntityExplode` / `onBlockExplode` called `EligiblePlacedOreStore.remove()` once per block, and every call opened a connection and ran a `SELECT` — unconditionally, against a table holding **4 rows** on the live server. Measured **1.8–2.1 ms per block**: 172 ms for an ~80-block bed blast, **919 ms** at the 512-block cap. The same path also ran on *every player ore break* via `OreEligibilityService.find`, violating the "no DB I/O on the hot path" red line in `KYOKALITH_SPEC.md` §15.1. The table is now held entirely in memory (loaded once in `onEnable`, write-through afterwards), so lookups and no-op removes never reach SQLite.

  2. **A separate transaction per vein hit.** `MaterializationService.resolveAndLock` opened its own connection and committed on every hit. All locks produced by one event are now buffered in `MaterializationLockBuffer` and committed once at the end of it. The buffer reports not-yet-flushed locks to later lookups within the same event, so resolution results are identical to the old per-hit writes; the difference is that the batch is now all-or-nothing instead of leaving a half-committed prefix behind when a later write fails.

  3. **Every `connect().use { }` closed the *last* connection, forcing SQLite to checkpoint the whole WAL.** This was the one left after (1) and (2), and it was invisible in the first round of profiling because it does not scale with rows: a **10-row** commit cost the same as a 4-row one. Kyokalith now holds one connection open for the plugin's lifetime purely so that a close is never the last one, and sets `synchronous=NORMAL` on each connection. Measured on a copy of the live database, on the drive the server actually runs on:

     | | 10-row commit |
     |---|---|
     | before | 55–90 ms |
     | keep-alive that never ran a statement | 55–87 ms |
     | keep-alive + one statement | 23–25 ms |
     | `synchronous=NORMAL` alone | 65 ms |
     | keep-alive + one statement + `synchronous=NORMAL` | **6.5–11.6 ms** |

     Two things worth keeping: `DriverManager.getConnection` is **lazy** — a keep-alive connection that never executes a statement has not opened the database file and blocks nothing, which is why the first attempt at this fix measured as a complete no-op. And the two changes only work together: `synchronous=NORMAL` alone is pointless while every close still checkpoints.

  **Result** (same scenario, same server): the worst tick after a blast went from **291–500 ms** to **13.9–48 ms** in steady state. The first blast after a restart still costs about 110 ms while chunk caches and the WAL warm up. For reference, the same blast with Kyokalith unloaded costs 3–18 ms.

  **Durability note.** `synchronous=NORMAL` in WAL mode means a commit is no longer fsynced. It stays durable against a plugin or server crash — the case §9.4 is about — and only a power loss or OS crash can drop the last few seconds. What is at risk is `materialized_positions`, a derived cache of a pure function: a lost `collectShape` lock re-resolves to the same answer at that coordinate's own first exposure. Only a worldgen-continuation shape could come out differently, and never in a way that reveals a buried block early.

  **No anti-x-ray behaviour changed.** Unexposed positions still only ever get a database row, never a `setBlock`; the 512-block and 4096-row per-event caps are untouched; the "resolved but not persisted must never change a block" ordering of §9.4 is now stricter, not weaker, because the single flush happens before any `setType` in the event.

### Changed

- **`ancient_debris` recalibrated against vanilla.** Players reported netherite hunting felt nothing like vanilla, and the numbers agree. The shipped curve (peak `1.0` at Y15, background `0.65`) had a peak-to-background ratio of **1.54**, against roughly **21** in vanilla. Total yield was 2.07x vanilla, but smeared across 112 Y levels: the band players actually mine (Y8~24) held only **0.55x** vanilla, and a single Y16 layer only **0.34x**. The total was never the problem — the shape was. The curve now reproduces vanilla's two batches, a triangle over Y8~24 peaking at Y16 plus a much thinner uniform Y8~119 background:
  - `preferred_y` 15 → 16, `cell_chance` 0.045 → 0.125, `y_weight_points` background 0.65 → 0.05. `density` stays 1.0 (this config keeps `density` as the global abundance knob and expresses per-ore rarity through `cell_chance`).
  - Background weight 0.05 is vanilla's background layer density divided by its peak layer density (0.0054 / 0.1234); `cell_chance` then solves `total = 56 * cell_chance * mean(weight) * E[vein_size]` for vanilla's ~1.65 blocks per chunk. Source: <https://minecraft.wiki/w/Ancient_Debris>.
  - Measured over 1024 chunks x 4 salts: **1.65** blocks/chunk total (vanilla ~1.65), **1.03** in Y8~24 (vanilla ~1.05), **0.118** at Y16 (vanilla ~0.123) — all within 5%. Mean tunnel spacing at Y15 improves from 1057 m to **426 m**.
  - **Deliberate tradeoff:** matching vanilla makes Y25+ genuinely dry (mean tunnel spacing at Y60 goes from ~1.5 km to ~6 km). That is what vanilla does — netherite is found at Y8~24 or not at all. The `ancient_debris` Y60 threshold in `OreDistributionMetricsTest` was relaxed accordingly, and the Y9/Y15/Y60 density band in `NetherOreDensityMonteCarloTest` split into three vanilla-referenced bands, because a single threshold across three Y levels that differ by an order of magnitude in vanilla cannot express the intent.
  - **Existing locked positions are not re-resolved.** `materialized_positions` rows decided under the old curve keep their answer; `vein_algorithm_version` is unchanged because the geometry did not change, only the config parameters. Re-deciding an already-decided coordinate would break the §9.4 invariant.
  - Servers upgrading must take the shipped `config.yml` — Bukkit's `copyDefaults` never overwrites keys that already exist on disk, so a stale `config.yml` keeps the flat curve.

  The two reports are the same activity seen from two sides: blast mining at Y15 is how players look for netherite, and it was both laggy and unrewarding.

### Added

- `KyokalithDatabase.connectionsOpened`, surfaced in `/kyo stats` as "DB connections opened". A per-coordinate connection on a hot path has now caused two separate incidents (1.4.2's dirty-position flush, and this one), so it is both observable in ops and pinned by a regression test: a 512-block explosion-sized block list with nothing placed must open **zero** connections.
- A warning when resolving one explosion takes longer than 25 ms, naming the block count and location. Without it, "blast mining lags" is only ever a player report that has to be re-measured from scratch.

### Verification

- **L1** `./gradlew build`: **90 tests green**. New: `EligiblePlacedOreStore` (zero connections over a 512-block list, `loadAll` restores a previous run's rows, `removeInChunk` evicts only that chunk), `MaterializationLockBuffer` (fixed row budget, re-adding a buffered position costs no budget, buffered locks visible to later lookups but only landing on `flush`), and `KyokalithDatabase` (the keep-alive really holds the file open — asserted through the `-wal` file — and `close` releases it; opening it twice leaks nothing).
- **L2** Enabled cleanly on s01 against the real 4 MB database (13 416 materialized rows, 4 eligible rows), both from a cold start and through repeated `plugman reload` — hot-swap still works, and `onDisable` releases the keep-alive (the `-wal` file disappears on shutdown).
- **L3/L4** `tools/mineflayer/kyokalith-explosion-tps-test.js` (new, in the Lycohinya repo) drives real TNT blasts through RCON and reads `/mspt`, run against the 1.4.2 jar and the 1.5.0 jar at separate coordinates so neither run inherits the other's locks, plus a Kyokalith-unloaded control. `tools/mineflayer/kyokalith-explosion-behaviour-test.js` (new): **7/7**, including both directions of the anti-x-ray invariant on a live blast — every decoy newly exposed by the crater was resolved, and every decoy that stayed fully buried was left completely untouched.
- All costs above were measured against the real code paths on the drive the server runs on. An early round of this work measured on the SSD holding `%TEMP%` while the server runs from a SATA HDD, which understated every commit by roughly an order of magnitude; **the production server's disk is different again, so these absolute numbers do not transfer to it.**

## [1.4.2] - 2026-08-13

### Changed

- **Dirty position write-back moved off the ticking thread and batched into one transaction.** A spark profile of a live 12-player server showed `DirtyPositionStore.persist` accounting for roughly 1% of server-thread wall time: every flush cycle opened a *fresh JDBC connection per pending chunk* and committed each one separately, so a cycle with a few hundred dirty chunks paid a few hundred file opens and a few hundred `fsync`s — on the thread that ticks the world. The cycle now runs on the async scheduler (`Schedulers.asyncTimer`: `runTaskTimerAsynchronously` on Spigot/Paper, `AsyncScheduler.runAtFixedRate` on Folia) and writes the whole batch through a single connection and a single transaction.
  - **Nothing about persistence guarantees was relaxed.** A dirty flag that fails to land re-opens the cover-and-dig exploit, so the same data is still written on the same cadence — only the thread and the batching changed. A failed batch (e.g. `SQLITE_BUSY`) falls back to the original per-chunk path, preserving the "one bad chunk must not skip the rest of the cycle" guarantee that has held since 1.x; anything still failing is requeued for the next cycle.
  - **Folia/threading safety.** The flush task touches no Bukkit API. `DirtyPositionStore`'s shared state was already built on concurrent collections; the write paths (`flush`, `flushAll`, `clearEpoch`) are now additionally serialized by an internal lock. `clearEpoch` matters specifically here: it and the flush used to be implicitly mutually exclusive because both ran on the main thread, and without the lock an in-flight batch could write back an epoch that NatureRevive had just cleared. The final `flushAll` in `onDisable` waits for an in-flight cycle, keeping PlugMan hot-swaps safe.
  - `docs/CONFIG.md` / `docs/CONFIG.zh-TW.md`: the `dirty_flush_interval_ticks` red-line note was rewritten to describe the new behaviour.

### Verification

- `./gradlew test build`: 83 tests green, including the new `DirtyPositionBatchFlushTest` (batch lands, empty-queue no-op, repeated flush, `clearEpoch` is not overwritten by a later batch, positions marked after a flush survive) and the pre-existing `DirtyPositionConcurrencyTest`, which hammers 80 000 positions with concurrent `markDirty`/`flushAll`.
- `AsyncScheduler.runAtFixedRate`'s signature was checked with `javap` against the exact `folia-api-26.1.2.build.8-stable.jar` this project compiles the Folia source set against, not from memory.
- **L1 only.** Not yet deployed anywhere; L2–L4 pending.

## [1.4.1] - 2026-08-07

### Fixed

- **`/kyo notify off` (and editing `config.yml` directly) could fail to actually stop broadcasts.** `OreFindNotifyListener` and `/kyo notify <on|off>` both went through `plugin.config` (Bukkit `YamlConfiguration`) independently. Consolidated into a single `@Volatile var notifyOnOreFind` on `KyokalithPlugin`: `/kyo notify` writes it (and still persists to `config.yml`), the listener reads it directly instead of touching the config map on every mining event. This closes a real class of latent bug — `OreVeinResolver`'s cache already needed `Collections.synchronizedMap` for the same non-thread-safe-config-object reason — but an earlier draft of this note incorrectly asserted a Folia cross-region-thread race as the *confirmed* production root cause. **That assertion was wrong and unverified**: production runs plain Paper, not Folia, so that specific race cannot be what's happening there. **Closed 2026-08-11: the reported production failure was never Kyokalith's.** The broadcasts the admin was seeing (`X found N <ore> in M% light`) come from **Prism**'s `prism.alert-block-break`, which `/kyo notify off` cannot possibly affect — Kyokalith's own broadcast reads `[Kyokalith] {player} 挖到了 {ore},座標 x y z`. Confirmed on a live server with a Mineflayer bot in both directions (alert present, then silent after disabling Prism's `alerts.block-break-alerts`); see `docs/HANDOFF.md` in the main Lycohinya repo. This 1.4.1 change stands on its own as a correctness improvement regardless.

### Verification

- `./gradlew test build`: all existing tests pass, no behavior change to any other subsystem.
- **L3:** Paper 26.2 (build 103) `runServer` booted the 1.4.1 jar cleanly, zero `ERROR`/`Exception` in the log.
- **L4:** `tools/mineflayer/kyo-notify-toggle-test.js` (new, in the Lycohinya repo) drives a real Mineflayer bot — bridged from 1.21.11 to 26.2 via ViaVersion/ViaBackwards, since Mineflayer has no native 26.x support yet (`docs/MINEFLAYER_TESTING.md`) — through `/kyo giveeligible` → place → mine with a real pickaxe, asserting on actual chat output: `/kyo notify on` → broadcast received; `/kyo notify off` → no broadcast on an identical break; `config.yml` on disk shows `notify_admins_on_ore_find: false` afterward. All three assertions passed.
- **Not yet confirmed:** whether this change actually resolves what Yana saw on production. The mechanism above is a real simplification, not a proven fix for the reported incident — see `docs/HANDOFF.md` for open root-cause hypotheses (stale/duplicate jar, PlugMan hot-reload listener leak) and what's still unverified.

## [1.4.0] - 2026-08-06

### Added

- **Optional admin notification on real ore finds.** When a player mines a real (non-decoy) ore block, Kyokalith can now broadcast it to every online player holding `kyokalith.admin`. Off by default — toggle at runtime with `/kyo notify <on|off>`, which writes `notify_admins_on_ore_find` to `config.yml` and takes effect immediately (the only config key in this plugin that doesn't need a restart). Implemented as a plugin-internal listener on its own `OreCheckTriggerEvent` at `MONITOR` priority with `ignoreCancelled = true`, so it observes only and never affects decoy resolution.

## [1.3.5] - 2026-07-25

### Fixed

- **Silk Touch'd eligible ore no longer refuses to stack.** `EligibleOrePdc.tag()` stamped a fresh random UUID into the ItemStack's PDC on every call, so any two otherwise-identical eligible ore items always had different meta and could never merge — each Silk Touch break sat in the inventory as its own 1-count stack. The per-item `token_id` is gone from the ItemStack; eligible ore now stacks normally whenever `oreType`/`origin_world`/`origin_epoch` match, matching the spec's "eligibility follows the ItemStack" model. `token_id` still exists as a debug-only column on the `eligible_placed_ores` DB row, generated fresh at placement time instead of carried from the item.

## [1.3.4] - 2026-07-25

### Fixed

- **TNT now authenticates the destroyed volume, not only the new crater surface.** Before Bukkit creates drops, accepted explosions deterministically lock visible natural-ore entrances first, then resolve every base/decoy block in `blockList` and every newly exposed neighbor. A buried decoy directly targeted through X-Ray therefore becomes its resolved base before destruction, while real hits inside the blast volume still produce vanilla drops.
- **Explosion batches are deterministic and atomically visible.** Coordinates are processed in stable world/x/y/z order. All lock batches in one event share a 4,096-row hard budget, and block-type changes are deferred until every required batch succeeds. SQLite failure or budget exhaustion cancels the event without leaving partial world mutations; ordinary misses still write nothing.

### Calibration

- Radius-4 TNT crater sampling counts both destroyed-volume hits and new-surface hits. Distinct encounters per 100 blasts at critical layers: coal 36.328, iron 30.469, copper 28.516, gold 19.922, redstone 29.688, lapis 21.094, diamond 10.547, emerald 3.516, quartz 59.375, Nether gold 34.375, ancient debris 5.469.
- The bundled ore configuration is unchanged from 1.3.3. The TNT regression fixes event semantics; it does not increase total density.

### Verification

- `./gradlew test`: 78 tests passed, including TNT destroyed-volume/surface encounter bands and the 4,096-row event budget alongside all 1.3.3 geometry, density, migration, and failure-rollback regressions.
- The previously shipped 1.3.3 JAR was reloaded successfully on localhost Paper 26.2 before the TNT change. A final 1.3.4 TNT-drop runtime fixture was attempted but blocked by the desktop tool's external-execution quota; it remains explicitly listed in the test handoff and is not claimed as passed.
- Production deployment was not performed.

## [1.3.3] - 2026-07-24

### Fixed

- **Visible vanilla ore now opens a real, bounded vein instead of erasing the ore behind it.** v1.3.2 treated an adjacent buried vanilla ore as an independent decoy; a resolver miss changed it to stone before exposure. A player could therefore see and mine the surface ore, then find no vein underneath. The first player break of an already-visible, unlocked natural ore now creates one salt-protected, face-connected continuation whose target size is the ore's configured `vein_size`. Its full result is locked in one batch before exposure; breaking locked members cannot seed another continuation.
- **Seed maps still cannot predict the continuation.** The visible vanilla block is only an authenticated entrance. Hidden continuation order and size use the private Kyokalith salt, not the vanilla ore component or world seed. No unexposed block is changed or sent differently before first exposure.
- **Every continuation has a sealed endpoint.** The salt-grown shape is capped by `vein_size_max`, Chebyshev radius 4, and a directly adjacent original-ore stop frontier. At most `7 × vein_size` rows are written (224 at the global size limit of 32), across loaded chunk boundaries in one SQLite transaction. A failed batch rolls back completely and cancels the triggering break.
- **Synthetic veins lock their complete shape once.** The former moving 5×5×5 window was removed; all positions of the accepted shape (at most 32) are persisted on the first hit, so continued mining cannot extend the lock into another candidate.
- **Buried-ore encounters are materially more frequent.** Candidate cells are now 8³ instead of 16³. All 11 bundled ores were measured and recalibrated with smaller veins and lower per-cell chances, raising encounter count without returning to large spheres. A deterministic 2×1 tunnel probe now guards distinct vein encounters and dry-spell distance instead of treating flat-layer hit density as player experience.
- **Oversized explosions are rejected in O(1).** Events above 512 affected blocks are cancelled instead of trimming an arbitrarily large tail; accepted events still perform at most 512 ownership/exposure checks.
- **A failed lock can no longer expose an unauthenticated decoy.** Synthetic and worldgen lock batches are all-or-nothing. SQLite failure is logged at `SEVERE` and propagates back to cancel the triggering break, explosion, burn, entity change, or piston event before the covering block disappears.
- **Existing databases upgrade safely.** Vein algorithm version 3 invalidates only `materialized_positions`. Salt, epochs, dirty positions, eligible ores, suspended chunks, and all other state remain untouched.

### Calibration

- Critical-layer hits/10k: coal 21.289, iron 21.777, copper 22.852, gold 12.695, redstone 17.383, lapis 10.742, diamond 6.738, emerald 0.879, quartz 49.219, Nether gold 19.531, ancient debris 2.441.
- Mean metres between distinct veins in an eight-surface 2×1 tunnel probe: coal 125.308, iron 125.069, copper 137.681, gold 255.004, redstone 163.840, lapis 220.660, diamond 434.013, emerald 1260.308, quartz 68.624, Nether gold 128.502, ancient debris Y9/Y15/Y60 `1489.455 / 1057.032 / 1456.356`.
- Nether hits/10k at Y9 / Y15 / Y60: quartz `0 / 45.605 / 49.219`, Nether gold `0 / 19.531 / 23.047`, ancient debris `0.977 / 2.441 / 1.074`. Ancient debris remains clearly rare without becoming functionally absent.
- Current sampled vein maxima match the shipped bounds: coal/iron/redstone/nether gold 7, copper 8, gold 6, lapis/diamond 5, emerald 2, quartz 9, ancient debris 3. No same-ore layer component exceeded its configured maximum.
- Fixed 64³ samples contain 1,060 ore blocks in the Overworld and 1,660 in the Nether (0.404% / 0.633% of sampled coordinates), below the test ceilings of 1,200 / 1,800.

### Verification

- `./gradlew test`: 76 tests passed, including the v1.3.2 visible-ore failure reproduction, salt separation, fixed continuation/frontier limits, negative cross-chunk atomic rollback, algorithm-v2 migration, tunnel dry spells, connected components, priority overlap, Y boundaries, and Nether ratios.
- **L3:** Paper 26.2 build 65 enabled Kyokalith 1.3.3 with 11/11 ores; `/kyo stats` and plugin version responded normally, shutdown was clean, and no test server process or port remained.
- **L4 focused regression:** the final packaged JAR was exercised by Mineflayer 1.21.11 through official ViaVersion/ViaBackwards 5.11.0 on a localhost-only test server. It broke one visible iron entrance and followed newly exposed ore to a six-block vein within the shipped `3..7` bound. This proves the reported surface-only path through real `BlockBreakEvent`; broader multi-biome mining feel remains a deployment-test task.
- Production deployment was not performed.

## [1.3.2] - 2026-07-24

### Changed

- **`vein_size` now means an exact block count (1–32).** Each accepted `veinId` grows as a deterministic, face-connected voxel shape with a hard proof-friendly upper bound; the former size-to-radius sphere mapping is gone. Different sizes now change actual vein volume instead of being magnified or collapsed by integer radius conversion.
- **Candidate arbitration is whole-vein atomic.** Touching same-ore candidates suppress the higher stable `veinId`, preventing adjacent cells from merging into a long belt. If different ore shapes overlap, the lower `priority` candidate is discarded in full; equal priority falls back to lower `veinId`. Every survivor therefore keeps its complete connected shape and exact target count instead of leaving 1–2 block fragments.
- **Y distributions can use `y_weight_points`.** The optional sorted `[y, weight]` list is linearly interpolated; definitions without it keep the legacy triangular `preferred_y` fallback. The cell cache is now bounded at 20,000 entries.
- **YAML config schema v2.** The bundled config now declares `config_schema_version: 2`. Upgrade detection reads only the file-owned value before Bukkit merges defaults, keeping YAML schema, DB schema, and vein-algorithm versions independent.
- **All 11 bundled ores were recalibrated from measurements.** Current critical-layer coordinate hits/10k are coal 9.082, iron 8.105, copper 5.566, gold 2.539, redstone 3.418, lapis 4.688, diamond 1.563, emerald 0.293, quartz(y60) 18.945, nether gold(y15) 7.129, and ancient debris(y15) 0.781. Distinct encounters/10k in the same order are 2.734, 2.734, 1.660, 1.074, 1.563, 1.563, 0.879, 0.293, 7.520, 2.734, and 0.586.

### Fixed

- **Finite veins and restored encounter spacing.** The v1.3.0 baseline converted configured sizes into overlapping spheres and measured only hit-coordinate density, masking the combination of huge individual veins and long distances between encounters. Baseline volume samples found only 4 overworld veins (max 123) and 15 Nether veins (max 257), plus a reproducible 613-block legacy quartz chain. v1.3.2 samples found 54 overworld veins (max 9, P50 5, P95 9; nearest-vein P50 11.18, P95 19.90) and 75 Nether veins (max 14, P50 8, P95 13; nearest-vein P50 9, P95 14.765). The largest Nether mixed-ore connected component was 15; no single surviving `veinId` exceeded its configured exact size.
- **Already-visible ore beside rails and other non-occluding blocks no longer re-resolves.** The old exposure test recognized only air/water/lava, so ore beside a rail could be visible to the player yet still be classified as buried; removing the rail then falsely triggered "first exposure" and could make that ore disappear. Exposure now uses Bukkit `Material.isOccluding`. Listeners snapshot each removed block's occlusion state before the event applies; Paper and ownership-verified Folia execution therefore make the same decision in the event tick.
- **5×5×5 materialization locks cannot relay into another vein.** A hit persists only positions that resolve to the trigger's exact accepted `veinId`; ordinary misses and neighboring candidates are not written. Work remains bounded to at most 124 extra checks per hit, without scanning chunks, force-loading neighbors, or changing unexposed blocks.
- **Explosion exposure work now has a hard event bound.** `EntityExplodeEvent` and `BlockExplodeEvent` block lists are capped at 512 during `HIGHEST`, before the `MONITOR` materialization pass snapshots them. Excess entries are removed from the event list—so those blocks remain in the world—and a warning records the requested and retained counts. Eligible-ore cleanup also runs at `MONITOR` against the capped final list, so retained blocks do not lose their token state.
- **Folia region ownership is now enforced rather than assumed.** Before any exposure handler reads a block plus its neighboring chunk radius, it verifies `Bukkit.isOwnedByCurrentRegion`. Unsafe single-block/piston events are cancelled; foreign explosion entries are removed from the event list and remain in the world. Paper/Spigot keep their normal single-thread behavior.
- **Priority arbitration now considers only ores that support the queried base material.** A high-priority deepslate-only definition can no longer suppress a valid stone ore (or vice versa) and leave an empty hole in custom API configurations.
- **Existing databases invalidate only stale derived locks.** On first startup, algorithm metadata v2 clears `materialized_positions` once and preserves salt, epochs, dirty positions, eligible placed ores, suspended chunks, and all other state.
- **Old configs no longer inherit incompatible v2 Y curves.** The first runServer attempt with a real v1 config reproduced a fail-fast disable: Bukkit `copyDefaults` had inserted new `y_weight_points` while retaining old `y_min`/`y_max`, creating endpoint mismatches. v1 startup now clears only inherited curves, warns, saves schema 2, and falls back to each existing `preferred_y` triangle. The complete v2 config shipped in the patch is still required to enable the measured calibration curves.
- **Nether bands remain useful without flattening rarity.** Quartz / Nether gold / ancient debris hits per 10k are y9 `0 / 0 / 0.684`, y15 `18.652 / 7.129 / 0.781`, and y60 `18.945 / 6.055 / 0.293`. Ancient debris remains clearly rarer than common ores without returning to near-zero.

### Verification

- **L1:** deterministic geometry, distribution, connected-component, spacing, overlap, materialization-lock, non-occluding exposure snapshot, 512-block explosion cap, negative-coordinate/boundary, and database-migration tests produced the evidence above.
- **L3 startup:** after the schema-aware merge fix, runServer enabled Kyokalith 1.3.2 with both a legacy config (11/11 ores using triangular fallback) and the complete shipped v2 config (`/kyo stats` 11/11), then stopped normally. The final log contained no ERROR/SEVERE/Exception and ports 25565/25575 were released. L4 player-visible mining behavior remains unverified; no production deployment was performed.

## [1.3.0] - 2026-07-24

### Added
- **`ores.<oreType>.priority` config field (original 1.3.0 behavior, superseded).** This release introduced operational cross-ore ordering and exposed the winning priority in `/kyo inspect`. Its coordinate-level overlap behavior was replaced by v1.3.2's whole-candidate atomic arbitration.
- **Persisted vein locking (`materialized_positions` table; original 1.3.0 behavior, superseded).** This release introduced bounded 5×5×5 derived locks without writing unexposed blocks. v1.3.2 narrows those locks to the trigger's exact `veinId` and performs the one-time algorithm-v2 invalidation described above.

### Fixed
- **Historical vein-radius change (superseded by v1.3.2).** This release still used sphere geometry; v1.3.2 replaces that model with exact, bounded connected shapes and should be used for current behavior and tuning guidance.
- **`ancient_debris` was functionally absent in the y8-22 band it's supposed to live in.** `cell_chance` was 0.006 (the lowest of any ore, ~8x lower than the next-rarest) and `vein_size_max` was 2 — Monte Carlo sampling (`NetherOreDensityMonteCarloTest`, method: resolve every coordinate in a fixed-Y grid, count hits per 10,000 samples) measured this at **0.14/10k at y8, 0.056/10k at y15, 0.0/10k at y22** — i.e. not just rare, essentially zero. That's the real root cause of "can't find ancient debris near y15" reports (`nether_quartz`/`nether_gold`'s `y_min: 10` is correct vanilla behavior and was **not** touched — Monte Carlo showed no anomaly there once aggregated over a Y-band, see the test). `cell_chance` 0.006 → 0.05 (~8x) and `vein_size_max` 2 → 3 (matching vanilla's real per-vein block count of 3 for both of its overlapping distributions) measures **0.81/10k at y15** — about 14x the old value, and still only ~9% of `nether_quartz`'s own measured peak density (9.17/10k at its preferred Y of 60) — clearly rarer than a common ore, no longer indistinguishable from zero. `y_min`/`y_max`/`preferred_y` (8/119/15) were left as-is; they already matched vanilla's dual-distribution shape.

## [1.2.1] - 2026-07-23

### Fixed
- **Ore could flicker into view and back out during normal mining.** Branch/"fishbone"-style miners reported real ore blocks visibly appearing then disappearing in front of them, or a promising-looking vein resolving to only a couple of real ore blocks. Root cause: first-exposure resolution required the neighbor's *live* block state to already show the removal (already air/water/lava), so every listener deferred a full tick via `Schedulers.atRegion` and let vanilla's own block removal land first. But a buried decoy is genuine world data already sitting in every player's loaded-chunk cache — that's the whole point of the anti-X-Ray model. The instant the covering block's removal reaches the client, face culling renders whatever the decoy actually is; Kyokalith's correction (real ore, or a revert to stone) only lands a tick later, so the player briefly sees the decoy's true, unresolved appearance before it flips. `MaterializationService.isNewlyExposed` (now a pure, unit-tested function) treats a neighbor's membership in the triggering event's own `removed` set as sufficient evidence it is about to go transparent, so resolution no longer needs to wait for the removal to actually apply. `MaterializationListener`'s block-disappearance handlers moved to `EventPriority.MONITOR` (so no other plugin can still cancel the event out from under us) and now call `Schedulers.atRegionNow` instead of `Schedulers.atRegion` — running inline, in the same tick as the triggering event, whenever the current thread already owns the block's region (the normal case: a player breaking a block in front of themselves). Multi-block events that happen to straddle a Folia region boundary still fall back to the next tick, same as before. No change to the decoy model, the vein function, or eligibility — this only closes the reveal-then-correct timing gap.

## [1.2.0] - 2026-07-17

> Folia support was contributed by [RiceChen_ (RICE0707)](https://github.com/RICE0707) in [#1](https://github.com/TinyYana/Kyokalith/pull/1) — including the source-set isolation that keeps `folia-api` off the main compile classpath. Thank you!

### Added
- **Folia support.** `folia-supported: true` is declared, and every scheduled task now runs on the thread that owns the data it touches: block work on the owning region, `/kyo giveeligible` on the recipient's entity scheduler, and the dirty-position flush (which only writes SQLite) on the global region. Spigot and Paper behaviour is unchanged — the same code path resolves to `Bukkit.getScheduler()` there. The startup line reports which mode is active (`scheduler: Folia regionized` / `Bukkit main thread`).
- Admin commands that read or write blocks (`inspect`, `preview`, `sample`, `markeligible`, `resolve`) now run on the thread that owns the target coordinate — inline when the current thread already owns it, scheduled onto the owning region otherwise. On Folia a console command runs on the global thread, which owns no blocks at all, so these would otherwise have thrown. Spigot and Paper always dispatch commands on the main thread, so the inline path is always taken there and replies — including over RCON — behave exactly as in 1.1.0. Known limit: on Folia, an RCON command that has to hop regions gets an empty RCON response (the response buffer is flushed when dispatch returns and cannot wait for another thread).

### Changed
- **Folia's scheduler API is compiled in a separate `folia` source set**, not added to `main`. `folia-api` transitively exposes the whole Paper API, so putting it on `main`'s compile classpath would have silently retired the [1.0.0] guarantee that Spigot compatibility is a compile-time fact. `main` still sees only `spigot-api`; the one file that calls Folia (`FoliaSchedulers`) is loaded only when running on Folia, and `SchedulersSpigotSafetyTest` fails if a Folia reference ever reaches the class Spigot does load.
- `database.dirty_flush_interval_ticks` below `1` is now clamped to `1`. Bukkit silently did this already; Folia rejects it outright, so the same config would have failed startup there.
- **`api-version` lowered from `26.2` to `26.1`.** Folia's newest release is 26.1.2 — no 26.2 build exists — and a server refuses to load a plugin whose `api-version` is above its own version, so this is the price of one jar running on all three platforms. The compile baseline is unchanged (`spigot-api` 26.2); on Spigot/Paper 26.2 the plugin now merely declares the older API level.

### Fixed
- **Dirty positions could be lost on Folia**, which is an exploit problem rather than mere data loss — a dropped dirty flag makes a block a player covered up "first-exposure resolvable" again, reopening the cover-and-dig hole. Each chunk's position set was a plain `HashSet`, safe on Spigot only because `markDirty` and the flush task shared the main thread. Under regionized scheduling the flush runs on the global thread and encodes the set while a region thread is still adding to it, throwing `ConcurrentModificationException` mid-write. The per-chunk sets are now concurrent; `DirtyPositionConcurrencyTest` reproduces the original failure. Spigot and Paper were never affected.
- A failed dirty-position flush no longer drops the chunk from the flush queue: the chunk is requeued for the next cycle and the failure is logged, and one chunk's failure no longer aborts the rest of the cycle. Relevant on Folia, where region threads and the global flush task can hit SQLite concurrently and collide with `SQLITE_BUSY`; previously the pending flag was consumed before the write, so a failed write meant those dirty positions were never retried — the same exploit-reopening loss the flush-interval note in CONFIG.md warns about.

## [1.1.0] - 2026-07-16

### Changed
- **Eligibility now includes already-exposed vanilla ore.** Previously, only ore Kyokalith itself materialized on first exposure (`NATURAL_BLOCK`) or moved through the placed-block token flow (`PLACED_BLOCK`) could fire `OreCheckTriggerEvent`. Ore that was already exposed at world generation — cave walls, ravine faces, i.e. most of what a player actually mines while exploring — almost never coincidentally matched the deterministic vein function, so it silently never fired the event. That exclusion caught zero cheaters (X-Ray gives no informational edge on ore that's already visible) while starving downstream reward plugins of legitimate check opportunities: a server measured **zero triggers across ~200 ores mined** in ordinary cave exploration. A new `EligibilitySource.WORLDGEN_EXPOSED` now covers this case — any real, currently-standing ore of an enabled type that isn't in a dirty position is eligible. Anti-X-Ray guarantees are unaffected: this only changes reward-check eligibility, not the decoy/materialization logic that decides what's real. See [docs/API.md](docs/API.md#eligibility-tokens).

## [1.0.0] - 2026-07-14

First public release. The decoy-materialization model shipping here has been running in production on a live survival server for the past two weeks without incident.

**What Kyokalith is, in one paragraph:** anti-X-Ray that never touches world generation. Vanilla ores generate exactly as always; any ore fully enclosed by solid blocks is a *decoy* — X-Ray, freecam, and seed-map tools all see it, but whether the block is real is only decided the moment mining first exposes it, by a deterministic function keyed with a private per-server salt. Cheaters tunnel to what they saw through the wall and hit stone; ore an honest player can see is always real. Per-event cost is a constant (removed blocks × 6 neighbor checks) — no packet obfuscation, no chunk scanning. See the [README](README.md) for the full model.

### Added
- **Customizable messages / locales.** All admin-command output moved out of the code into `plugins/Kyokalith/lang/<locale>.yml`. Bundled locales: `en` (default) and `zh_TW`; new config key `locale` selects one. Edit the generated files to customize — deleted keys fall back to the built-in text, and any key missing from a locale falls back to English. Add your own `lang/<name>.yml` and set `locale: <name>` for a new language, no code required.
- **Tab completion for every `/kyo` subcommand**: subcommand names, coordinates (pre-filled from the block you're looking at, or your position), world names, online player names, ore type ids, radius/amount suggestions. Players without `kyokalith.admin` get no completions.
- **LICENSE**: TinyYana Universal Software License (TYUSL) 1.0 — free to use, modify, integrate, and redistribute (commercial servers included); selling the plugin itself or repackaging it as a paid product requires written permission.
- **English documentation** as the primary set: [README.md](README.md), [docs/CONFIG.md](docs/CONFIG.md), [docs/API.md](docs/API.md). 繁體中文版本: [README.zh-TW.md](README.zh-TW.md), [docs/CONFIG.zh-TW.md](docs/CONFIG.zh-TW.md), [docs/API.zh-TW.md](docs/API.zh-TW.md).
- **Automated releases**: pushing a `v*` tag builds, tests, verifies the tag matches `gradle.properties`, and publishes a GitHub Release with the jar and this file's matching section as notes.

### Changed
- **Compiled against the Spigot API** (26.2) instead of the Paper API. The single Paper-only call in the codebase (`pluginMeta`) was replaced with the Bukkit equivalent, making Spigot compatibility a compile-time fact. Production testing still runs on Paper; Folia is not supported.
- Kotlin runtime pinned to stable **2.4.0** (was `2.4.20-Beta1`); the `libraries:` entry in plugin.yml that the Bukkit library loader downloads at startup matches.
- Console/log messages are now English. `/kyo stats` output no longer leaks internal reward-system terminology ("d20").
- Plugin metadata (description, docs) no longer references the private server project this plugin originated from; source comments that pointed at a private design document now explain the concepts inline or link to `docs/` in this repo.

### Fixed
- `processResources` now registers the version as a task input — previously an incremental build after bumping only `gradle.properties` could produce a jar whose embedded `plugin.yml` still carried the old version string.

### Upgrading from a pre-release 0.x build
Drop-in: replace the jar and restart.
- Your existing `config.yml` is kept as-is; the new `locale: en` key is merged in automatically with your values and comments untouched. Set `locale: zh_TW` for Traditional Chinese admin output.
- `plugins/Kyokalith/lang/` is created with editable copies of both bundled locales.
- Database schema, the `salt`, and all tracked state are unchanged — no migration, nothing re-rolls.

## [0.1.0] - 2026-07-04 (never released)

Internal development versions for a private server: the original datapack-strip + chunk-scan anti-X-Ray (v0.3, deleted after it dragged TPS to 18.9), its replacement decoy-materialization model (internally "v0.4"), the eligible-ore token lifecycle, and `OreCheckTriggerEvent`.
