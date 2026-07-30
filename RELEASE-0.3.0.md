# PathWeaver 0.3.0 — Modrinth release notes (draft, not uploaded)

**Version number:** `0.3.0+26.1.2` · **Type:** `alpha` · **Loader:** fabric · **Game version:** 26.1.2
**Jar:** `pathweaver-0.3.0+26.1.2.jar` · SHA-256 `60ca580ff16073ea5bbc6996141024ce3589d9617760c7eb9d4c9a360302ae5b`
**Dependencies:** fabric-api (required), cloth-config (required), modmenu (optional)

---

## The headline: PathWeaver now runs on packs where it used to sit switched off

PathWeaver refuses to run a path search off-thread when another mod has modified the code that
search executes. That rule is correct, and it is also why the mod did nothing on most modpacks — a
single popular mod anywhere in the pack switched it off entirely.

0.3.0 replaces that all-or-nothing behaviour with a setting, and does the work to open the gate
honestly rather than by ignoring it.

## New setting: Compatibility tier

Replaces the old `overrideCompatibilityScan` checkbox. Existing configs migrate automatically — if
you had it on you get `ALL`, otherwise `STRICT`.

**`STRICT`** (default) — only runs off-thread where a worker *provably* cannot see the other mod's
change. Every exemption rests on a structural proof checked against exact artifact bytes; an
unexpected build of an audited mod denies rather than assuming.

**`AUDITED`** — additionally trusts mods whose bytecode has been read and shown to write nothing on
the search path. **Lithium is the one that matters** — it ships in most performance packs, and at
`STRICT` its presence alone kept PathWeaver off. Diagonal Blocks is also audited. The honest trade:
these cannot corrupt your world from a worker, because they write nothing a worker reaches; they can
still return a slightly stale path if the world changes mid-search.

**`ALL`** — ignores every check and runs off-thread regardless. This runs unaudited third-party code
on a worker thread, which is exactly what the scan exists to prevent. Failure modes are not limited
to a bad path; a crash or a damaged world is possible. **For worlds you can afford to lose.** It
logs a loud warning naming what it waived.

## Also new

- **Blocks that mods mark dangerous no longer switch PathWeaver off.** Mods use a public Fabric API
  to say "mobs should avoid my block". Previously one such registration disabled the mod. Now, where
  that registration is the world-independent kind, PathWeaver asks it for every answer it can give,
  once, on the main thread, and freezes the result. Workers read the frozen table, so the mod's code
  never runs off-thread. This needs no audit and no per-mod entry, and works for mods written after
  this release.
- `compatibilityTier=ALL` now also covers mob classes added by mods. Previously "ignore every check"
  still kept most of a heavily-modded pack's mobs synchronous while reporting nothing was checked.
- The tier appears in the ModMenu settings screen with proper labels.

## Performance

Measured on the configuration you actually get — stock Fabric API, Lithium loaded, `AUDITED`,
shipped limits, gate opened on its own, no harness intervention. 1024 zombies in a walled maze all
retargeted every 6 ticks. Four pairs, interleaved and order-reversed, across two builds.

| | Synchronous | With PathWeaver |
|---|---|---|
| Tick interval, mean | 78–96 ms | **49–50 ms** |
| Tick interval, p99 | 726–1096 ms | **338–390 ms** |
| Effective tick rate | 10.4–12.7 TPS | **20.0 TPS** |

Mean tick time fell **about 40%** (median 40.1%, range 36.2–48.2%), with no overlap — every async run
beat every synchronous run.

**Read that as a range, not a number.** The async arm is stable at 49–50 ms across both builds; the
*synchronous* baseline swings with ambient machine load, so any single pair over- or under-states the
gain. An earlier version of this table said 44.8% because it happened to be paired against the
heaviest sync run.

**The caveat that matters:** at the shipped in-flight limit, 13.6–20.6% of searches were discarded
under this load — roughly one in six thrown away and recomputed later. It still wins decisively, but
that is the default doing real work at saturation.

### And on an actual modpack

The four-mod environment isolates the effect. The same jar, same load, on a **371-mod server-side
derivative of a real pack** at `ALL`: mean 99.4 -> 49.9 ms and 90.9 -> 61.5 ms (mean **41%**, the same
figure), p99 893 -> 353 ms and 815 -> 449 ms. The scanner parsed **331 mixin configs with zero
failures**.

The spread is wider though, and the discard rate says why: **13.4% in one pair, 38.0% in the other**.
On a busy pack the workers compete with everything else, so more results arrive too late to use. The
shape of the win holds; the size is less predictable than the isolated benchmark suggests. If you see
heavy discarding, `maxInFlight` is the knob.

This is a synthetic burst with all other mob AI stripped out. It shows what happens when pathfinding
alone overloads a server. It is not a measurement of ordinary play, and PathWeaver's benefit is
**fewer tick spikes, not a higher average TPS**.

## What it still does not do

Only the exact vanilla `WalkNodeEvaluator` and `SwimNodeEvaluator` searches, and only for mobs whose
class comes from vanilla. Flying mobs, amphibious mobs, evaluator subclasses and mod-defined mob
classes stay synchronous. It does not move entity ticking, collision, or AI goals off-thread.

Still labelled **alpha**. A worker reads live chunk and mob state through a read-only view, not a
copy — see COMPATIBILITY.md for the residual assumptions, stated plainly.

---

## Pre-publish checklist

- [x] 197 unit tests, 0 failures
- [x] Three game-test harnesses green — default 3/3; `fabricAggregateHarness` 2/2 `deniedFamilies=0`;
      `auditedTierHarness` 2/2 `deniedFamilies=0`
- [x] Release jar booted on a dedicated server **outside** Loom's dev classpath, with Farmer's
      Delight and FerriteCore loaded, at every tier
- [x] Release jar booted at **modpack scale** — 371 mods, 331 mixin configs scanned, 0 failures —
      with a paired A/B and the audited-tier denial list read from the live scan
- [x] Clean build from `master` reproduces the jar hash byte-for-byte
- [x] Tagged `v0.3.0`; `master` at `71cb2b6`
- [x] Benchmark claims reconciled with measurements
- [ ] **Independent review — NOT DONE.** Every change here is self-reviewed.
- [ ] Upload to Modrinth (project `ZQJOU3vB`, `POST /v2/version`) — awaiting go-ahead
