# "The moment I spawn a cat or a monster my game lags": results

Three preregistered series and a set of exploratory runs, 2026-09-14, in the real 26.1.2 client pack
(317 mods), singleplayer, a fresh copy of the world where the lag was seen. Reports in `raw/`, each
produced by the reader named beside it. Profiles and logs stay in `bench/client-runs/` (not committed:
about 7 MB per profile, and the logs carry account details); `raw/SHA256SUMS-profiles.txt` pins them.

## The answer

**PathWeaver did not cause the lag. Enhanced Cats did, with or without PathWeaver.** Enhanced Cats makes
every cat look for villagers within 10 blocks and walk them towards it, every tick, and that code also
runs on the client, so the Render thread runs full path searches. With PathWeaver absent and Enhanced
Cats present, 4.1% of frames took over 50 ms in the phase where a cat was spawned; with Enhanced Cats
held out, 0.1% (`e2-none`, `e3-noecats-none`, one run each).

**PathWeaver did have a real defect of its own, and it is fixed.** Two things on the per-node path of
every search, including searches it leaves on the server thread, made those searches more expensive
than vanilla: ThreadLocal reads, and a cancellable mixin inject that allocates on every call. Villager
point-of-interest search took 24.1 to 30.2% of tick in every one of six 0.9.0 runs, and 19.7 to 23.5% in
every one of six runs without PathWeaver. With both fixes: 23.1, 23.3 and 26.1%.

That is a few percent of tick in a villager-heavy world, not a lag spike. It is worth fixing because it
taxed exactly the searches PathWeaver cannot move off the thread.

## c1: is PathWeaver the cause? (`PREREGISTRATION.md`, `raw/c1-report.txt`, `bench/client/lag_report.py`)

Enhanced Cats was installed for this series.

| | prediction | verdict |
|---|---|---|
| K0 | none's rounds agree on base tick mean within 15% | PASS, 30.3 and 29.6 ms |
| C1 | 0.9.0 is worse than no PathWeaver | **prediction failed**: not worse |
| C2 | 0.9.0 is not worse than 0.8.0 | held |
| C3 | 0.9.0 with the player's config is worse than with defaults | held, in the one phase only, and weakly |

C3 met its rule on FPS in round 1 and on tick mean in round 2, not on the same measure twice. FPS in this
harness is not trustworthy: an unfocused game caps itself at 30 FPS, and several runs (`c1-r2-none`,
`c1-r2-v080b`) sit exactly at that cap. The first round-2 0.8.0 run was voided when the game lost focus
and is kept as `c1-r2-v080-VOID-focus-lost`; `c1-r2-v080b` stands in for it.

## Exploratory, after c1: where the time went

Not preregistered. Each is one run; they located the cause, and the series after them tested it.

- `e2-none` / `e2-v090` (Enhanced Cats in) against `e3-noecats-none` / `e3-noecats-v090` (held out).
  Frames over 50 ms in the one phase: 4.1 and 3.7% with Enhanced Cats, 0.1 and 0.2% without.
- With Enhanced Cats (`e2-v090`), 88% of server-thread path requests came from its two cat behaviours
  (184,960 villagers sent towards a cat, 90,496 cats sent after fish, of 312,753; the probe attributes
  every 32nd call). 88.5% of PathWeaver's dispatched searches were superseded before they finished;
  without Enhanced Cats (`e3-noecats-v090`), 0.1%.
- Superseded requests do not fall back to synchronous search at any real cost: synchronous searches
  that went through PathWeaver's gate were 1.5-1.7% of synchronous search time (`fallback.py`).
- The run script overwrote its record of mods held out, so `e3`'s `jars.txt` does not say Enhanced Cats
  was held. The game's own mod list does: `enhanced-cats 1.0.1` is listed in both `e2` logs and in
  neither `e3` log. Fixed in bae221a.
- Inside synchronous search on the server thread, 0.9.0 showed ThreadLocal internals
  (`ThreadLocalMap.getEntryAfterMiss`), which is what led to the fix.

## t1: the ThreadLocal fix alone (`THREADLOCAL-PREREGISTRATION.md`, `raw/t1-report.txt`)

Candidate a3bd6dd. Enhanced Cats removed from here on.

| | prediction | verdict |
|---|---|---|
| T1 | ThreadLocal frames are at least 10% of sync search in every 0.9.0 run | **FAIL**: 0.0, 22.5, 0.0% |
| T2 | under 2% in every candidate run | PASS, 0.0% in all three |
| T3 | POI search share of tick: candidate within 3 points of none, 0.9.0 more than 3 above | PASS: none 21.1, 0.9.0 28.1, candidate 24.0 |

T1 failed because the JIT usually inlines the ThreadLocal read into its callers, where the sampler files
the time under other frame names. So T2's zeros prove less than they look like: 0.9.0 also read zero in
two of three runs. What stands on its own is the unit test, which scans the per-node methods' bytecode
for any path to a ThreadLocal and was watched failing when one was put back.

## h1: both fixes (`HOTPATH-PREREGISTRATION.md`, `raw/h1-report.txt`)

Candidate 3d10163, sha256 `0ba3d71a...`, which adds the redirect in place of the cancellable inject.

| | prediction | verdict |
|---|---|---|
| H0 | lookup cost is at least 8% of sync search in two of three 0.9.0 runs | **FAIL**: 11.6, 0.0, 0.0% |
| H1 | under 2% in every candidate run | UNDECIDABLE (H0 failed); read 10.0, 1.3, 0.0% |
| H2 | POI search share of tick: candidate within 3 points of none | PASS: none 22.2, 0.9.0 26.4, candidate 23.3 |
| H3 | ThreadLocal frames under 2% in every candidate run | PASS, 0.0% in all three |
| Q | no run's background load moved more than 8 points | PASS, 31.0 to 34.3% |

Reported without a prediction (H4): median tick mean in the one and horde phases, none 28.7 / 29.3 ms,
0.9.0 31.5 / 27.5 ms, candidate 27.5 / 26.4 ms. Frames over 50 ms were at or under 0.3% in every arm.

**The lookup-cost measure does not work, and H0 caught it.** It was designed after t1 to count the
lookup's self time in every frame the JIT might file it under. In h1 it read zero for 0.9.0 twice, and
10% for the candidate once, in a frame that after the fix does nothing but one map read. It measures
where the JIT drew frame boundaries in that run, not what the lookup costs. `raw/lookup-cost-exploratory.txt`
keeps it for both series.

A second exploratory attempt, synchronous search time per villager POI call (`poi_per_call.py`,
`raw/poi-per-call-exploratory.txt`), is too noisy to separate arms: no PathWeaver alone ranges from 2.95
to 4.11 ms per call across six runs.

## What stands, and on what

- **The defect is gone from the code.** PROVEN in the bytecode: `PathWeaverThreadHotPathTest` finds no
  path from the per-node checks to a ThreadLocal (witness `hot-loop-check-reads-a-threadlocal`), and
  `LandPathTypeRegistryMixinStructureTest` requires a redirect and no cancellable inject on the provider
  lookup (witness `provider-lookup-allocates-per-node`). Each witness puts the old code back and watches
  its test fail for that reason.
- **The fixes preserve behaviour.** PASSES IN TESTS: `LandProviderLookupRedirectTest` pins both answers
  of the lookup, off a worker and on one (witness `worker-reads-the-live-provider-map`); the unit suite
  and the Fabric-aggregate game test pass.
- **Villager search costs about what it costs without PathWeaver again.** Measured on the outcome, in
  two preregistered series (T3, H2), on one world and one machine. Not measured: any other world, and the
  size of the gain on a dedicated server.
- **Not established**: a mechanism-level number for how much of search time the lookup cost. Both
  instruments built for it were shown to be unreliable, and none is claimed.

## What this cannot say

One world, one machine carrying a foreign process that holds about 8 of 32 logical cores, three rounds
per series, and a harness that cannot hold the game's focus. It attributes this lag in this world. It
does not put a number on anyone else's.

## Enhanced Cats back in, with the fixes (H5 and `FOLLOWUP-ECATS.md`)

Two reported pairs, not a prediction. The first pair showed Render-thread path search of 24,720 ms with
0.9.0 against 5,516 ms with the candidate; a second pair, in reversed order and with its rule written
first, showed 4,944 against 3,396 ms and did not meet it. **No claim is made about the Render thread.**
The integrated server's tick mean was lower with the candidate in both pairs, and Enhanced Cats remains
the thing that generates the searches: the fixes make each one cheaper, not fewer.

## The jar that ships is not byte-identical to the one measured here

Found while staging the release, after this series: the checkout the measured jar (`0ba3d71a...`) was built
in had CRLF line endings on disk in `src/main/resources/pathweaver.mixins.json`, which git stores as LF.
A fresh checkout of the same commit builds `47e24d24...`. The two jars have the same entries in the same
order and differ in that one file only, whose bytes are equal once CR is removed and whose parsed JSON is
equal (`docs/evidence/RELEASE-JARS-0.9.0.txt`). Mixin reads that file as JSON, so the measured behaviour
is the shipped jar's; the claim this rests on is that comparison, not a re-run.
