# Kyokalith developer reference

[繁體中文](API.zh-TW.md)

Kyokalith exposes exactly **one** integration point: `OreCheckTriggerEvent`. No `ServicesManager` registration, no `-api` artifact, no interface layer — deliberately. There is only one thing to hook, so it uses Bukkit's native event mechanism.

---

## `OreCheckTriggerEvent`

`com.tinyyana.kyokalith.event.OreCheckTriggerEvent` — a standard Bukkit `Event`, implements `Cancellable`, fired **synchronously**.

### When it fires

A player mines an ore and **all** of the following hold:

- the player is in **survival mode** (creative/spectator/adventure never fire it)
- the player does **not** have `kyokalith.bypass`
- the ore carries a Kyokalith **eligibility token** (explained below)
- it is **not** a Silk Touch break (Silk Touch takes another path: the token moves onto the ItemStack)
- vanilla drops are non-empty

Explosions, pistons, and fire do **not** fire it — `TriggerSource` currently has a single value, `PLAYER_BREAK`. An explosion merely clears the eligible record from the DB.

### Fields

```kotlin
class OreCheckTriggerEvent(
    val player: Player,
    val blockLocation: Location,
    val oreMaterial: Material,
    val oreType: String,                    // ore id from config.yml, e.g. "diamond"
    val tool: ItemStack,                    // a clone; mutating it does nothing
    val fortuneLevel: Int,
    val drops: MutableList<ItemStack>,      // ← the only thing you may mutate
    val triggerSource: TriggerSource,       // PLAYER_BREAK
    val eligibilitySource: EligibilitySource, // NATURAL_BLOCK / PLACED_BLOCK / WORLDGEN_EXPOSED
) : Event(), Cancellable
```

### ⚠ Drop-rewrite semantics (counter-intuitive — read this first)

**Cancelling the event does not mean "no drops" — it means "keep vanilla drops".**

The decision logic:

| What you do | Result |
|---|---|
| `event.isCancelled = true` | **Vanilla drops kept**, Kyokalith stays out of it entirely |
| Leave `drops` untouched | **Vanilla drops kept** (same content = treated as unchanged) |
| Mutate `drops` (add/remove/replace) | Every originally dropped item entity is `remove()`d, then each non-air, `amount > 0` stack in `drops` is re-dropped at the block via `dropItemNaturally` |

So "drop nothing this time" is `event.drops.clear()` — **not** `event.isCancelled = true`.

### Example

```kotlin
class OreCheckListener : Listener {
    @EventHandler
    fun onOreCheck(event: OreCheckTriggerEvent) {
        val total = (1..20).random() + bonusOf(event.player)
        when {
            total >= 20 -> {                       // critical success: double
                val bonus = event.drops.map { it.clone() }
                event.drops.addAll(bonus)
            }
            total >= 15 -> {                       // success: one extra
                event.drops.firstOrNull()?.clone()?.let { event.drops.add(it) }
            }
            else -> return                         // failure: untouched = vanilla drops
        }
    }
}
```

Put Kyokalith in your `plugin.yml` `softdepend` and load the event class via **reflection**, and your plugin runs fine without Kyokalith installed — that's exactly how the original consumer hooks it, with zero compile-time dependency.

---

## Eligibility tokens

This is the rule for "which ore blocks can trigger a check". **Not every ore block can.**

| Where the ore came from | Token? |
|---|---|
| Produced by Kyokalith's first-exposure resolution | ✅ yes (`NATURAL_BLOCK`) |
| Silk Touch'd by a player, then placed back | ✅ yes (`PLACED_BLOCK`) |
| Any other real, currently-standing ore of an enabled type (e.g. exposed on a cave wall at world generation) | ✅ yes (`WORLDGEN_EXPOSED`) |
| Admin `/give`, WorldEdit paste, shop purchase, or any other player-placed block | ❌ no (placing anything marks the position dirty) |

`WORLDGEN_EXPOSED` exists because X-Ray gives zero information advantage on ore that was already visible without it — excluding it only starved reward plugins of legitimate check opportunities, not caught any cheater.

**One ore burns exactly one check.** Lifecycle:

```
first-exposure resolution → block carries a token
   ├─ normal break ──────→ fires OreCheckTriggerEvent, token consumed
   └─ Silk Touch break ──→ token moves onto the ItemStack's PDC, no event
         └─ placed back ─→ token moves into the eligible_placed_ores table
               └─ mined again → fires the event (eligibilitySource = PLACED_BLOCK), token consumed
```

So a token-carrying ore can be traded, moved, stockpiled, and re-mined, but **the check happens once**.

**PDC on the ItemStack** (namespace `kyokalith`): `eligible` (BYTE) / `ore_type` (STRING) / `origin_world` (STRING) / `origin_epoch` (INTEGER). No per-item `token_id` on the ItemStack — an earlier version stamped a fresh random UUID on every tag, which made the meta differ between any two otherwise-identical ore items and silently broke vanilla stacking (each Silk Touch'd ore sat as its own 1-count stack). Same-origin ore now stacks normally; `token_id` still exists as a debug column on the `eligible_placed_ores` DB row, generated fresh at placement time.

**No free re-rolls**: on `BlockBreakEvent` a `PendingBreak` is stashed and a next-tick reclaimer scheduled. If `BlockDropItemEvent` never arrives (e.g. another plugin suppressed the drops), the token is **consumed anyway**, with a `fine`-level log line.

---

## The deterministic vein function `f`

```
f(salt, world, epoch, oreType, cellX, cellY, cellZ) -> hit / miss
```

- **Pure function**: splitmix64 hashing; reads no world state, no DB.
- **8³ cells**, with at most one candidate per ore type and cell. The smaller cell restores encounter frequency while each accepted candidate remains a deterministic, face-connected voxel shape whose block count is exactly the configured `vein_size` (1–32).
- **No same-ore chains**: if adjacent cells produce touching shapes of the same ore, the candidate with the higher stable `veinId` is suppressed in full.
- **Atomic cross-ore arbitration**: among definitions that support the queried base material, if two ore shapes overlap at all, the lower-priority candidate is discarded in full; equal priority falls back to lower `veinId`. A surviving `veinId` therefore retains its complete face-connected shape and exact configured block count—never a 1–2 block remnant cut out by another ore.
- **Idempotent**: same inputs, same output, forever. That's why `/kyo resolve` is safe to re-run — if you suspect a coordinate missed its event, re-run it and the result is guaranteed identical to "what should have happened".
- **`salt` decouples real ore from the world seed**: seed-map sites can compute where vanilla ore generated, but not where Kyokalith's ore is.

**`epoch`**: a per-chunk counter. When a chunk is regenerated (NatureRevive), `epoch += 1` — re-rolling `f` **for that chunk only**, leaving the rest of the world alone.

**Cell cache**: bounded LRU, 20,000 entries; the key includes `epoch`, so entries for regenerated chunks age out naturally — no invalidation sweeps. Cache misses cost nothing correctness-wise (pure function, just recompute).

### Locking a vein's shape at first exposure (`materialized_positions`)

`f` alone is deterministic for fixed salt/epoch/config. On the first hit, Kyokalith persists the accepted vein's **complete shape** (at most 32 positions) in one batch. The next time one of those positions is genuinely first-exposed, it reads the locked decision instead of calling `f` again. There is no moving window and therefore no mining-driven lock relay.

This is deliberately bounded and deliberately inert:

- **Constant work per synthetic hit**: at most 31 extra positions, never a vein/chunk scan. The lock cannot bridge to an adjacent candidate, even if it has the same material.
- **Never writes a block for an unexposed position.** The lock is a row in SQLite (or the in-memory cache) that says "when this coordinate is *later* first-exposed, apply this material" — it is not `Block.setType`. A locked-but-unexposed position is, in every way a client or an x-ray user can observe, identical to any other still-buried decoy: same material in the world data, same bytes in the chunk packet. Only the trigger position — the one actually undergoing first-exposure resolution *this* tick — ever gets `setType` called on it.
- **Misses are not recorded.** A normal miss writes nothing to `materialized_positions`. This prevents ordinary stone mining from adding synchronous DB I/O.

### Visible vanilla ore continuation

An already-visible natural ore has no synthetic `veinId`, but it must not be a one-block shell. On the first direct player break, Kyokalith treats that block only as an authenticated entrance and grows a new continuation using the private salt:

- target size is the ore's configured `vein_size`; hidden order is salt-ranked and does not follow the world-seed vanilla component;
- growth is face-connected, limited to Chebyshev radius 4, loaded/owned blocks, and the same fixed `vein_size_max`;
- the selected positions plus the directly adjacent original same-ore stop frontier are locked once; locked keep/stop members cannot seed another continuation;
- at most `vein_size + 6 × vein_size` rows are written (224 at the global maximum), across loaded chunk boundaries; since 1.5.0 they are buffered with every other lock produced by the same event and committed in one SQLite transaction at the end of it;
- planning never calls `setType`; each selected or stop position changes only when it is itself first exposed;
- if the transaction fails, every row rolls back, the exception is logged, and the triggering cancellable exposure event is cancelled before it can reveal an unlocked decoy.

---

## Tables

`plugins/Kyokalith/kyokalith.db`, SQLite, `journal_mode=WAL`. There is no connection pool: an operation that reaches the database opens a fresh connection. Opening one costs about 0.4 ms, and a connection plus one query about 1.8–2.1 ms, so **no hot path may open a connection per coordinate** — an explosion resolves up to 512 blocks in a single event, and doing that once per block cost 919 ms on the region thread before 1.5.0. Two rules keep it off the hot path:

- `eligible_placed_ores` is held entirely in memory (loaded once in `onEnable`, write-through afterwards). Lookups and removes for coordinates with no record never reach SQLite. This is what the explosion block list and every player ore break go through.
- All materialization locks produced by one event are buffered and written in a single transaction (`MaterializationLockBuffer`), not one transaction per vein hit.

Two further rules keep the *cost per commit* off the hot path — both were needed, and neither works alone:

- **One connection stays open for the plugin's lifetime**, opened in `onEnable` and closed in `onDisable`. It never runs a query after the first one; it exists so that closing an operation's connection is never closing the *last* one, which would make SQLite checkpoint the entire WAL synchronously on that thread. Note that `DriverManager.getConnection` is lazy: a keep-alive connection that never executes a statement has not opened the database file and blocks nothing.
- **`synchronous=NORMAL`** is set on every connection. In WAL mode this stops the per-commit fsync. Commits stay durable against a plugin or server crash (the case §9.4 cares about); only a power loss or OS crash can drop the last few seconds. That risk is bounded by what the table holds — a derived cache of a pure function, which re-resolves to the same answer at each coordinate's own first exposure. Worldgen-continuation shapes are the one part that could come out differently, and never in a way that reveals a buried block early.

Measured on a copy of the live database, on the drive the server runs on, for a 10-row commit: 55–90 ms before, 23–25 ms with the keep-alive alone, 65 ms with `synchronous=NORMAL` alone, **6.5–11.6 ms** with both. The cost is nearly independent of row count — it is checkpoint and fsync, not the insert.

`KyokalithDatabase.connectionsOpened` counts every connection opened and is shown in `/kyo stats`; a regression test asserts that a 512-block explosion-sized block list with nothing placed opens zero.

```sql
meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)
  -- salt (random UUID, never reset), schema_version, created_at

chunk_epoch(world, cx, cz, epoch, PRIMARY KEY(world, cx, cz))

dirty_positions(world, cx, cz, epoch, data BLOB, PRIMARY KEY(world, cx, cz, epoch))
  -- data is not a bitset; it's UTF-8 text "lx,y,lz;lx,y,lz;…"
  -- deliberate: volume never justified a packed encoding, readability wins

eligible_placed_ores(world, x, y, z, epoch, ore_type, ore_material,
                     token_id, placed_by, placed_at, PRIMARY KEY(world, x, y, z))

suspended_chunks(world, cx, cz, reason, created_at, PRIMARY KEY(world, cx, cz))

materialized_positions(world, cx, cz, epoch, lx, y, lz, ore_type, vein_id, material,
                       PRIMARY KEY(world, cx, cz, epoch, lx, y, lz))
  -- derived lock cache for accepted same-vein hits and bounded visible-ore keep/stop plans;
  -- never stores ordinary misses
  -- Cleared per-chunk on NatureRevive regeneration, same as dirty_positions.
```

The generation algorithm has a separate metadata version. On first startup of v1.3.3, any database without `vein_algorithm_version = 3` deletes **only** `materialized_positions`, then records version 3 in the same transaction. This invalidates sparse v2 locks before 8³ cells and one-shot continuation plans take effect, without changing `salt`, chunk epochs, dirty positions, eligible placed ores, suspended chunks, or other metadata.

---

## Config-schema migration

`config_schema_version` versions the YAML shape; it is independent of both database `schema_version` and `vein_algorithm_version`.

`KyokalithPlugin.mergeConfigDefaults()` must read the file-owned value with `config.contains("config_schema_version", true)` before calling `copyDefaults(true)`. Missing means v1. When upgrading v1, Bukkit has already made bundled v2 `y_weight_points` visible as defaults even though the file still owns old Y ranges. Kyokalith explicitly writes empty lists for those inherited paths, then saves schema 2. `OreRegistry` consequently sees no curve and uses the legacy triangular `preferred_y` fallback.

This is a compatibility migration, not a calibration migration. Only a complete shipped v2 config may enable the bundled piecewise curves, because its Y ranges, curve endpoints, density, encounter chance, and exact vein sizes were measured as one set.

---

## First-exposure event contract

An exposure face is any material for which Bukkit `Material.isOccluding` is false. This deliberately includes rails, glass, slabs, and other blocks beyond air/water/lava: ore beside one of them was already player-visible and must never be re-resolved when that covering block is removed.

All removal listeners capture a `RemovedBlockSnapshot(block, wasOccluding)` at `MONITOR`, before Bukkit applies the removal, and resolve in the same tick. Folia first verifies ownership of the target plus one chunk of read radius. Unsafe single-block/piston events are cancelled; foreign explosion entries are removed from the event list and remain in the world. The captured pre-removal state prevents timing from turning an already-visible ore into a false first exposure.

Explosion lists have an additional hard boundary. At `HIGHEST`, an entity or block explosion with more than 512 `blockList` entries is cancelled in O(1); accepted events are not trimmed. At `MONITOR`, coordinates are sorted for deterministic arbitration, visible natural-ore anchors are locked first, then both the destroyed volume and newly exposed crater surface are resolved before Bukkit creates drops. This prevents a buried decoy directly hit by TNT from bypassing authentication. Eligible-lifecycle cleanup consumes the same final bounded list.

---

## Performance contract (read before changing anything)

**Per-event cost is bounded.** Ordinary removal checks six faces; a synthetic hit locks at most 32 positions; one visible natural-ore entrance plans at most 32 keep positions plus 192 direct frontier positions. All lock batches in one event share a hard 4,096-row write budget; exceeding it cancels the event before any pending block-type changes are applied. Ordinary misses still write nothing. Explosions above 512 entries are cancelled before iteration. Work runs in the same tick on the thread that owns the full read radius — the main thread on Spigot/Paper, the verified owning region on Folia. Foreign Folia entries stay in the world, never queued as follow-up work. Never async.

**Forbidden:**

- ❌ scanning chunks (in any form: at generation, on `ChunkLoadEvent`, scheduled, shell scripts)
- ❌ DB queries on the event path
- ❌ iterating all players

The pre-1.0 scan-based model (v0.3) force-loaded 121 chunks and dragged TPS to **18.9**; deleting the entire scanning pipeline (including 23 datapack `configured_feature` JSONs) brought it back to **20.1**. This is not a theoretical concern; it was measured.

The only remaining brute-force scans are `/kyo preview` and `/kyo sample` — admin-only, radius clamped to 1..24, skipping unloaded chunks. **A deliberate exception, not a precedent to extend.**

---

## NatureRevive bridge

Loads `engineer.skyouo.plugins.naturerevive.spigot.events.ChunkRegenEvent` via reflection, registered at `MONITOR`. On chunk regeneration:

```
suspend the chunk → epoch += 1 → drop old-epoch dirty positions
                 → drop the chunk's eligible records → lift suspension
```

No re-scan needed: regeneration itself puts vanilla ore back = a fresh layer of decoys, and `epoch + 1` re-rolls `f` for that chunk only.

**If the bridge throws, the chunk is deliberately left suspended** (fail-closed) — better a chunk that never materializes than one operating on inconsistent state.

---

## Nohara bridge

Loads `com.tinyyana.nohara.api.NoharaChunkRestoredEvent` via reflection, registered at `MONITOR` (not listed in `softdepend` on purpose, it would close a load-order cycle through LycohinyaCore; Nohara always enables later, so the bridge registers on Nohara's `PluginEnableEvent` using Nohara's own class loader, and re-registers if Nohara is reloaded; without Nohara the bridge stays inactive and nothing else changes). Nohara refuses to write while Kyokalith is enabled but not listening, so this bridge is what unlocks restores. Event kinds `RESTORED` and `ROLLED_BACK` are handled identically. The event arrives on the chunk's owner region thread; the handler touches memory only and queues the rest on one dedicated worker thread (the only place SQLite is used):

```
region thread: drop placed tokens whose block no longer matches (memory) → queue job
worker:        carry dirty positions old epoch → epoch+1 (persisted)
               → epoch += 1 → carry again (late marks) → drop old-epoch dirty rows
               → drop old-epoch materialized locks → delete stale token rows
```

Unlike a NatureRevive regeneration, Nohara rewrites only cells that differ from the donor and were not touched by players afterwards, so **dirty positions are carried over, not dropped**: a player-refilled hole that Nohara left alone is still a player block. The cost is that a dirty flag can outlive the cell it described (never the other way round). Locks are dropped so a vein revealed before the restore is not reproduced at the same coordinates. No chunk is scanned.

Every persistence step is ordered so a crash leaves only unreferenced old rows. **If any step fails, the chunk is left suspended** (logged as `KYOKALITH_NOHARA_INVALIDATE_FAILED`; lift it with `/kyo resume <cx> <cz>` after checking). Between the event and the end of the worker job (milliseconds) exposures still resolve against the old epoch; locks written then are discarded with the old epoch.

---

## Known rough edges

Honesty up front, so you don't think you misread:

- `/kyo giveeligible` writes `originEpoch = 0` on the ItemStack. Fine as a QA tool; don't use it as a "official token grant" mechanism.
- `EligibleOrePdc.clear()` is defined but never called.
- Listeners and `KyoCommand` have **no automated tests** (no MockBukkit / server test framework). What is tested: the vein function, the registry, the three stores, first-exposure decision logic, and the message table.
