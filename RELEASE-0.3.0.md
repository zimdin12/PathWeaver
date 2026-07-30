# PathWeaver 0.3.0 — Modrinth release notes (draft, not uploaded)

**Version number:** `0.3.0+26.1.2` · **Type:** `alpha` · **Loader:** fabric · **Game version:** 26.1.2
**Jar:** `pathweaver-0.3.0+26.1.2.jar` · SHA-256 `ac018ed45c4c58acbd754ebdca5aad1247228a8968e19c75efbb4daca9fc11f8`
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
you had it on you get `Everything`, otherwise you get the shipped default, `Audited`. That boolean
selected the scanner behaviour of its day rather than a tier that existed, so mapping "off" to
`Strict` would read as faithful and would in fact take a working install inert on upgrade.

**`STRICT`** — only runs off-thread where a worker *provably* cannot see the other mod's change.
Every exemption rests on a structural proof checked against exact artifact bytes; an unexpected build
of an audited mod denies rather than assuming.

**`STRICT` is not the default, and would be inert if you chose it.** Fabric API's own interaction
module is cleared by a bounded audit rather than a structural proof, so it is not admitted here —
which means a stock Fabric install denies under `STRICT`. Calling a six-class sample an exhaustive
proof was the alternative, and it was rejected. **`AUDITED` ships as the default** for exactly that
reason.

**`AUDITED`** — additionally trusts mods cleared by bounded evidence rather than by proof.
**Lithium is the one that matters** — it ships in most performance packs, and at `STRICT` its
presence alone kept PathWeaver off. Four distinct mechanisms sit behind this tier, and they are not
equally strong: Lithium and Diagonal Blocks pass a field-write-opcode check on the search path;
Fabric API's interaction module rests on an inventory of direct calls from six pinned classes;
mods that merely mark blocks dangerous rest on an assumption that their rule is a pure function of
block state; and Farmer's Delight's stove rests on reading one artifact's bytecode. The honest
trade on the first of those: no worker-side write was *found*, by a check that does not model array
stores, mutations inside methods those classes call, or effects reached through helpers — so it
lowers the risk of corruption from a worker rather than ruling it out. COMPATIBILITY.md states each
mechanism separately. These mods also add live reads, so a search can return a slightly
stale path if the world changes mid-search.

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
  this release. It rests on such a rule being a pure function of block state — the signature shows
  the provider is not handed the world, not that its answer is stable — so it is honoured at
  `AUDITED` rather than at the `STRICT` default.
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

**The caveat that matters:** at the shipped in-flight limit, 13.6–20.6% of dispatched searches were
not installed within the capture window — roughly one in six not making it back before it is wanted.
The harness stops at the end of the window, so read that as work unused in time rather than as
proven-discarded. It still wins decisively, but that is the default doing real work at saturation.

### And on an actual modpack

**On reading these two metrics together:** effective tick rate is derived from mean tick interval (`mean > 50 ms ? 1000/mean : 20`), not measured separately, so "tick time halved" and "tick rate doubled" are the same fact stated twice. The `20.0` is a clamp: any mean at or under the 50 ms budget reports exactly 20.0, and a server with headroom sleeps to hold that rate, so this metric cannot show how much spare capacity the async arm actually had. The independent signals are p99 and main-thread cost per request.

The four-mod environment isolates the effect. The same jar, same load, on a **371-mod server-side
derivative of a real pack** at `ALL`: mean 99.4 -> 49.9 ms and 90.9 -> 61.5 ms (mean **41%**, the same
figure), p99 893 -> 353 ms and 815 -> 449 ms. The scanner parsed **331 mixin configs with zero
failures**.

The spread is wider though, and the share of work that went unused says why: **13.4% of dispatches not installed within the capture window in one pair, 38.0% in the other**.
On a busy pack the workers compete with everything else, so more results arrive too late to use. The
shape of the win holds; the size is less predictable than the isolated benchmark suggests. If you see
heavy discarding, `maxInFlight` is the knob.

**`maxInFlight` is an admission bound, not a buffer — and the default is already where it should be.**
Sweeping it on that pack, measured as dispatches not installed within the capture window: 13.5% at
the shipped 256, **90.7% at 1024, and nothing at all installed during the window at 4096** -- because
workers are a fixed pool, so a deeper queue only makes each result land later, and a result that
arrives after its mob has asked again is superseded. That failure shows no errors and still
reports 20 TPS, so 0.3.0 now samples its own install ratio once a minute and warns if under a quarter
of completed searches are being used.

Swept against load (512/1024/1536 mobs) and worker count (4/8/16), the picture is consistent: 256 has
the better p99 by 8–30%, while 64 leaves almost no work unused, and mean tick time is the same either
way. A refused request runs on the tick instead, so the larger bound trades some wasted worker CPU
for a shorter tail — the right trade for a mod whose purpose is cutting spikes. **Leave it at 256**
unless worker CPU is scarce. Raising it is the clearly bad move.

This is a synthetic burst with all other mob AI stripped out. It shows what happens when pathfinding
alone overloads a server. It is not a measurement of ordinary play, and PathWeaver's benefit is
**fewer tick spikes, not a higher average TPS**.

## What it still does not do

Only the exact vanilla `WalkNodeEvaluator` and `SwimNodeEvaluator` searches. Flying mobs, amphibious
mobs and evaluator subclasses always stay synchronous. Mob classes added by mods stay synchronous
unless you opt in with `allowModdedMobAsync`, or choose `ALL`, which implies it. It does not move
entity ticking, collision, or AI goals off-thread.

Still labelled **alpha**. A worker reads live chunk and mob state through a read-only view, not a
copy — see COMPATIBILITY.md for the residual assumptions, stated plainly.

---

## Pre-publish checklist

- [x] 217 unit tests, 0 failures, 7 skipped
- [x] Three game-test harnesses green — default 3/3; `fabricAggregateHarness` 2/2 `deniedFamilies=0`;
      `auditedTierHarness` 2/2 `deniedFamilies=0`
- [x] Release jar booted on a dedicated server **outside** Loom's dev classpath, with Farmer's
      Delight and FerriteCore loaded, at every tier
- [x] Release jar booted at **modpack scale** — 371 mods, 331 mixin configs scanned, 0 failures —
      with a paired A/B and the audited-tier denial list read from the live scan
- [x] Both accelerated families load-tested: Walk (1024 zombies) and Swim (1024 cod, 95204 searches)
- [x] Farmer's Delight audit verified both ways outside Loom — `AUDITED` dispatches, `STRICT` refuses
- [x] Clean build from `master` reproduces the jar hash byte-for-byte
- [x] Tagged `v0.3.0` on `master`
- [x] Benchmark claims reconciled with measurements
- [ ] **Independent review — NOT APPROVED for this tree.** The earlier approval attached only to
      commit `5854b606…2bc1` and jar `9baa9f7d…4314`, and is **void**: the tree has changed. The
      current tree returned REVISE, and the findings that produced this revision were the per-worker
      cap contradicting the p99 evidence, warning text that overstated what the counters prove, and
      release notes still certifying the void tree. Re-review pending.
- [ ] Upload to Modrinth (project `ZQJOU3vB`, `POST /v2/version`) — awaiting go-ahead
