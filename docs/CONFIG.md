# Kyokalith configuration reference

[繁體中文](CONFIG.zh-TW.md)

`plugins/Kyokalith/config.yml`. **There is no `/kyo reload`** — config is read once in `onEnable`; restart the server after changes.

Config validation is **fail-fast**: if any single ore definition is invalid (no material, `y_min > y_max`, `cell_chance` outside 0..1, an empty `ores:` block), **the plugin disables itself** rather than running with a broken config. If Kyokalith didn't enable, read the first line of the log.

---

## `config_schema_version`

| Key | Type | Current | Description |
|---|---|---|---|
| `config_schema_version` | Int | `2` | Version of the YAML config shape. This is separate from the DB `schema_version` and `vein_algorithm_version` |

On startup, Kyokalith reads the version from the file itself with `contains(path, true)` **before** merging defaults. A missing key means v1.

This ordering matters for v1 → v2. Bukkit `copyDefaults(true)` otherwise inserts every bundled `y_weight_points` list into the old ore section while retaining that server's old `y_min`/`y_max`; the endpoints can disagree and fail-fast validation disables the plugin. For a detected v1 file, Kyokalith therefore:

1. merges ordinary new defaults;
2. replaces every inherited `ores.<type>.y_weight_points` with an empty list;
3. saves `config_schema_version: 2`;
4. logs `Legacy config detected...` and falls back to each ore's existing triangular `preferred_y` distribution.

This makes an in-place old-config startup safe, but it does **not** apply the new bundled calibration curves. To use those curves, install the complete v2 `config.yml` shipped in the release/patch; do not paste only `y_weight_points` into old ranges.

---

## `locale`

| Key | Type | Default | Description |
|---|---|---|---|
| `locale` | String | `zh_TW` | Language of admin-command output. Bundled: `en`, `zh_TW` |

Language files live in `plugins/Kyokalith/lang/<locale>.yml` and override the built-in text key-by-key — keys you delete fall back to the bundled defaults (and any key missing from a locale falls back to English). To add a language, copy `lang/en.yml` to `lang/<name>.yml`, translate the values, and set `locale: <name>`. Color codes use `&`; `{placeholders}` are filled in by the plugin.

---

## `database`

| Key | Type | Default | Description |
|---|---|---|---|
| `database.file` | String | `kyokalith.db` | SQLite file name, relative to `plugins/Kyokalith/` |
| `database.dirty_flush_interval_ticks` | Long | `40` | Interval for writing dirty positions back to the DB, in ticks (40 = 2 s) |

> 🔴 **`dirty_flush_interval_ticks` is a red line in both directions.**
>
> The write-back task runs on the **async scheduler** (since 1.4.2; the AsyncScheduler on Folia); one cycle shares a single JDBC connection and a single transaction for a batched `INSERT OR REPLACE`. The task never touches the Bukkit API, the write paths (flush / flushAll / clearEpoch) are mutually exclusive through the store's own lock, and the final flush in `onDisable` waits for an in-flight cycle to finish.
>
> - **Too small (e.g. `1`)**: a SQLite batch write every tick — off the ticking thread, but the disk and lock pressure are still real. Values below `1` are clamped to `1`.
> - **Too large**: more dirty positions are lost on a crash — and losing dirty flags **is a correctness/exploit problem**, not just data loss: blocks a player covered up become "first-exposure resolvable" again, reopening the cover-and-dig exploit.
>
> The default 40 is the balance point. Don't touch it unless you know what you're doing.

---

## `ores`

Data-driven. Adding an ore type, removing one, or changing depth distribution **requires no code changes**.

```yaml
ores:
  diamond:
    enabled: true
    materials:
      stone: DIAMOND_ORE
      deepslate: DEEPSLATE_DIAMOND_ORE
    dimension: NORMAL
    y_min: -63
    y_max: 16
    preferred_y: -59
    y_weight_points:
      - [-63, 1.0]
      - [16, 0.0]
    density: 1.0
    vein_size_min: 1
    vein_size_max: 4
    cell_chance: 0.06
```

| Key | Type | Default | Description |
|---|---|---|---|
| `enabled` | Boolean | `true` | `false` = Kyokalith ignores this ore entirely: decoys stay vanilla, mining stays vanilla, no check events |
| `materials.stone` | Material | required (at least one) | Ore block to generate when the base block is `STONE` **or `NETHERRACK`** |
| `materials.deepslate` | Material | – | Ore block when the base is `DEEPSLATE`. **No fallback to `stone`** — unset means this ore never appears in deepslate layers |
| `dimension` | `NORMAL` / `NETHER` / `THE_END` | `NORMAL` | Only resolves in this dimension. **Nether ores must explicitly say `NETHER`** |
| `y_min` / `y_max` | Int | `0` | Hard range; outside it, never resolves |
| `preferred_y` | Int | `0` | Peak of the legacy triangular Y weight. Used only when `y_weight_points` is absent |
| `y_weight_points` | List of `[y, weight]` | – | Optional piecewise-linear Y curve. Points are sorted by Y; weights between points are interpolated and outside the first/last point use the endpoint weight |
| `density` | Double | `1.0` | Multiplier applied to `cell_chance` |
| `vein_size_min` / `vein_size_max` | Int 1–32 | `1` / `1` | Exact target block count of one deterministic, face-connected vein. Every `veinId` is strictly bounded by this value |
| `cell_chance` | Double 0.0–1.0 | required | Probability that an 8×8×8 cell spawns a vein origin (before `density` and the Y weight) |
| `priority` | Int | `0` | Atomic tie-break for overlapping shapes of different ores that support the queried base material: the lower-priority candidate is discarded in full; equal priority falls back to lower `veinId`. Surviving veins keep their full connected shape and exact size. Touching same-ore neighbors likewise suppress the higher stable `veinId`. Shown in `/kyo inspect` |

### Effective hit probability

```
activation = clamp(cell_chance × density × yWeight(y), 0, 1)
```

When `y_weight_points` is present, `yWeight` is the piecewise-linear curve described by those points. Otherwise Kyokalith retains the legacy triangular fallback: 1.0 at `preferred_y`, falling linearly to 0 at `y_min` / `y_max`.

### 🔴 Red lines

**`vein_size` controls yield per encounter, not encounter frequency.** It is an exact target count from 1 through 32, and the generated shape is deterministic and face-connected. Want players to meet veins more often? Calibrate `cell_chance`, `density`, and the Y curve while measuring total block density; do not inflate vein size.

**Do not copy a pre-1.3.3 `cell_chance` by itself.** One 16³ cell contains eight 8³ cells, so the number is not directly comparable. Treat the bundled 11-ore `cell_chance`, `vein_size`, and Y curves as one measured set; the regression suite guards tunnel spacing and 64³ total-volume ceilings together.

**Touching same-ore candidates do not merge.** Adjacent cells are checked deterministically; when their same-ore shapes touch, only the lower stable `veinId` survives. This is part of the generation contract, not a configurable probability.

**Cross-ore overlap never carves fragments.** Arbitration is candidate-atomic, not coordinate-by-coordinate: the losing vein disappears in full, while the winner remains face-connected at exactly its configured `vein_size`.

**Unset `dimension` = overworld only.** (An older config comment claimed "unset = matches all dimensions"; that was wrong — the code defaults to `NORMAL` and matches exactly.)

**`cell_chance` / `density` are literally your server's ore faucet.** The design target: the ore density a player experiences while tunneling (everything they hit is a decoy) should feel like the density of vanilla ore seen on cave walls. Think about which economy you want before touching these two numbers.

**`salt` is not in the config, and must never be reset.** It lives in the DB `meta` table, generated randomly on first startup. Resetting the salt re-rolls every unexposed vein in the world.

---

## Bundled ores

Overworld: `coal` `iron` `copper` `gold` `redstone` `lapis` `diamond` `emerald`
Nether (`dimension: NETHER`): `nether_quartz` `nether_gold` `ancient_debris`

All `enabled: true`, `density: 1.0`.

**The shape of `y_weight_points` matters more than the total.** `ancient_debris` shipped a nearly flat curve until 1.5.0 — peak `1.0` at Y15 against a `0.65` background, a peak-to-background ratio of 1.54 where vanilla's is roughly 21. Total yield was *above* vanilla (2.07x), but spread over 112 Y levels, so the band players actually mine held 0.55x vanilla and players correctly reported that netherite felt nothing like vanilla. If an ore feels wrong, check the ratio between its peak and its background before reaching for `density` or `cell_chance`: raising those on a flat curve just dumps more ore where nobody digs.

**Per-ore rarity goes in `cell_chance`, not `density`.** They multiply, so they are mathematically interchangeable; the convention here keeps every bundled ore at `density: 1.0` so that `density` stays available as a clean global abundance knob for later.

## Adding a new ore

```yaml
ores:
  my_custom_ore:
    enabled: true
    materials:
      stone: EMERALD_ORE            # generated when the base is stone/netherrack
      deepslate: DEEPSLATE_EMERALD_ORE
    dimension: NORMAL
    y_min: -16
    y_max: 80
    preferred_y: 32                 # distribution peak
    y_weight_points:                # optional; remove for legacy triangular fallback
      - [-16, 0.0]
      - [32, 1.0]
      - [80, 0.0]
    density: 1.0
    vein_size_min: 1
    vein_size_max: 3                # exactly 1..3 blocks per accepted vein
    cell_chance: 0.02
```

Save, restart. Check `/kyo stats` for the ore count +1, then stand at the target height and run `/kyo preview 16` to sanity-check hit density.

## Verifying changes

```
/kyo sample volume 24      # stand at the target Y, check hit/scanned ratio
/kyo preview 16            # see actual hit coordinates and ore types
/kyo inspect <x> <y> <z>   # single point: epoch, dirty, vein-function result
```

These three are brute-force scans, radius clamped to 1..24, **admin-only** — don't put them in automation that runs repeatedly.
