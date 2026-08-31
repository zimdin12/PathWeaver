# 0.8.0 review — seven categories, what came out of them

Seven parallel reviews were run over the tree at `f6a0d66`: recent features, holistic consistency,
player usability, architecture, settings and feature wiring, injection seams, and testing against
claims. Every finding below was verified against the code or against 26.1.2 bytecode before it was
acted on. Two reported findings did not survive that check and were not actioned; one reviewer's
suggested fix would have reintroduced a bug, and the tests caught it in a single run.

## The short version

**Twenty-one defects fixed.** The release is materially more honest and better covered than 0.7.0.
One thing is knowingly unfinished and is named in the docs rather than left to be discovered.

---

## 1. Features and changes

The brain sink is the only new feature. It is sound in mechanism and its bytecode claims all check
out — the guard offsets, the `createPath` tail, the warden's custom pathfinder, "about twenty AI
packages". What the review found was a cluster of claim-versus-implementation mismatches on the
player-facing surface, all now fixed:

- The version. `mod_version` said 0.7.0 while the tree carried a feature the `v0.7.0` tag does not
  contain, and the changelog filed it under 0.7.0. Anyone diffing the published jar against its
  changelog would have found a feature that is not in it. Now 0.8.0, with its own section.
- The tooltip claimed the brain sink was "the largest block of pathfinding still left on the server
  thread", dropping the *that could be moved* qualifier the javadoc and changelog both carry. It is
  false without it: villager POI queries are the larger share and can never be deferred.
- `@Gui.RequiresRestart` on a setting read live on every call.
- No strict type check on `brainSinkAsync` — the only field without one, added in the same commit as
  the comment explaining why the checks exist.

## 2. Holistic consistency

The theme was one feature landing and the rest of the project not being told.

- `release/body.md`, the Modrinth page, was still the 0.6.1 copy and told readers brain movement
  "stays synchronous by design" — in the release that made it asynchronous by default. Rewritten.
- `README` said its benchmarks were run "on the exact jar in this release". They were measured
  2026-07-31 on 0.6.1 and have not been re-measured; no benchmark on that page includes the brain
  sink. It now says which jar they came from.
- `COMPATIBILITY.md` headed a table "Every setting, measured" that covers eight of seventeen.
- `ROADMAP.md` carried a line count of 866 for a 940-line file, thirteen `@Unique` fields where there
  are fifteen, and led a section with "86% of the A*" six lines below a correction to ~9 points.
- Test counts were wrong in three documents, three different ways.

## 3. Player usability

The two that mattered most:

- On a heavy pack the mod emitted **nine to fourteen false WARN lines per launch**, each announcing
  that N movement families had been forced to sync, and then waived every one of them forty lines
  later. At the shipped default none of it was true. An operator triaging by grepping WARN met a wall
  of warnings on a healthy install. Now informational, and accurate.
- `/pathweaver` required permission level 2, so **no singleplayer player on a world without cheats
  could run any diagnostic** — on a mod whose page tells them to run it. `status` is now open.

Also fixed: `/pathweaver status` stopped adding up once parked results existed, and `/pathweaver mobs`
contradicted the shipped default.

Not acted on, and worth your judgement rather than mine: the review argues `compatibilityTier=UNSAFE`
is a global answer to a pack-local question, and that the argument for it in the javadoc ("shipping
AUDITED shipped something indistinguishable from broken") stopped being true in 0.4.0 when the
world-start report landed. That is a product decision, not a defect.

## 4. Architecture

The review was complimentary about the parts that already meet the bar — the pure decision layers,
the exhaustive switches, the parameter-injected policy state — and precise about where the gap is: it
falls almost exactly on the mixin boundary.

Acted on now: `isDiscard()` was the last of four outcome predicates still written as a `!=` chain, and
a chain is exactly what let a new constant default to the wrong answer when it was added. It is an
exhaustive switch.

Deferred to 0.9 with the reasoning recorded, not silently dropped: splitting `PathNavigationMixin`
(940 lines) and `ForeignMixinScanner` (1074), extracting the dispatch decision into a pure policy
object, de-duplicating ~180 lines of ASM toolkit across the four audit classes. Each is a real
improvement and none is release-shaped.

## 5. Wiring trace

Every config field and every user-visible feature traced end to end. The bulk came back clean: all
seventeen fields are read and change behaviour, none is honoured in one branch and ignored in a
sibling, all sixteen `RequestOutcome` constants have a producer and all sixteen are displayed,
migration is strict and fails closed.

Three defects: the `RequiresRestart` lie, the missing type check, and a tier-reconciliation branch
guarded on a predicate that is constant for both tiers — nine lines of comment over code that could
never run. It now guards on the predicate that actually differs.

## 6. Injection seams

Every `@At` target resolves to the instruction it claims, and every documented offset is correct.

Fixed: the brain-sink seams were the weakest-pinned in the mod — a descriptor-less
`method = "tryComputePath"` that would bind to both overloads if Mojang ever added one, and no
explicit `require`/`expect`. And `WallClimberNavigation` was missing from the compatibility scanner's
watch list even though 0.6 began dispatching for wall-climbers, so a foreign mixin into it denied
nothing. Debugify ships one on the reference pack.

Deferred: converting eight `@Redirect`s to `@WrapOperation` so they compose with other mods. The
review proved there are zero live collisions on the reference pack, so this is future-proofing.

## 7. Testing and claims

The sharpest finding was about this feature's own evidence. The brain-sink game test gated success on
a **global** `PARKED_FOR_BRAIN` counter in a harness where three sibling villager tests all park — so
"this mob's search was offloaded" was read off a number any of them could move. It now watches its own
per-entity slot, and a mutation that makes the feature inert kills it.

Same class, second instance: a test asserting exact deltas on the global dispatch counter shared a
harness with one that spawns a mob and dispatches. Their deconfliction was a comment saying the
sibling has `maxTicks = 160`; it declares 1400. Separate harnesses now.

And four dispatch guards that every test asserted the *predicate* of, and nothing asserted were
*called*. One of them, `owesEpilogue`, guards a documented permanent corruption of a mob's pathfinding
malus — its own comment says the mob "keeps 6.0/4.0 forever" — and deleting the line left all 379
unit tests green. All four are pinned.

---

## What is not done

**The shipping gate ran at the wrong tier for its whole life.** `DESIGN.md` section 10 and
`ROADMAP.md` both required a game test over the `CANT_REACH_WALK_TARGET_SINCE` transition table
before this feature ships. That test existed and covered all four transitions, and it had **never
tested the feature**: the harness ran at `compatibilityTier=AUDITED`, where the dev classpath denies
all six movement families, so the brain sink could not dispatch. Every run recorded `dispatched=0`.
It was timing a vanilla villager, which is why it was flaky and why mutations of the feature left it
green.

The harness runs at `UNSAFE` now and the same gate records `dispatched=6`. It kills a mutation
removing the liveness bound. The residual flake is measured not to be the feature: 8 of 10 pass with
the sink on, against a control at 6 of 8 with it off, and doubling the tick budget did not move the
rate. The gate is waived as blocking and kept as an on-demand reproducer, with that reasoning in
DESIGN.md rather than dropped quietly.

**A correction I owe on that.** I reported a "reproducible, feature-attributed freeze" four times, on
a control of 0-in-8 that had been measured on a materially different version of the test. It did not
survive re-measuring. The recommendation I built on it — ship `brainSinkAsync` default off — is
withdrawn. `brainSinkAsync` ships **on**, as chosen.

The failing signature, for whoever picks this up: a live villager, on the floor, with `PATH` absent
and `WALK_TARGET` present — both entry conditions satisfied — whose `MoveToTargetSink` is never
evaluated across 800 ticks. `BrainSinkDiagnostics` records it; the instrument is sound, since other
runs record a full start/stop lifecycle.

## Verification

- Unit suite: 395 tests, green.
- Harnesses, four consecutive rounds each: default 2, unsafe 5, refused 2, breaker 2,
  auditedRouting 2 — all green every round.
- Roughly thirty mutations compiled and observed to fail across this work. Three survived their first
  defence and the tests were rewritten, not the contracts.
- Jar: `pathweaver-0.8.0+26.1.2.jar`.

Nothing pushed. Nothing published.
