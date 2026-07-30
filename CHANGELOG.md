# Changelog

## 0.3.0 — Compatibility tiers, audited exemptions, and the first shipped-config benchmark

_Not yet published. The audits below are version-exact; see COMPATIBILITY.md._

### Added

- Add `compatibilityTier` (`STRICT` / `AUDITED` / `ALL`), replacing the blunt
  `overrideCompatibilityScan` boolean. `STRICT` is the default and only runs off-thread where a
  worker provably cannot observe another mod's change. `AUDITED` additionally honours mods proven by
  bytecode analysis to perform no shared-state writes on the search path. `ALL` ignores the scan
  entirely. Configs carrying the retired boolean migrate to `ALL` when it was on and `STRICT`
  otherwise; an explicit tier always wins, and an unreadable tier fails closed.
- Audit Farmer's Delight `26.1-3.6.7+refabricated` so its stove no longer switches PathWeaver off.
  It registers a *dynamic* land path-type provider, which normally denies Walk for the whole process
  because such a provider receives the world. This one never loads it: the provider forwards to a
  method whose entire body reads the `LIT` property, so its answers are precomputable exactly like a
  static provider's. The audit pins the artifact, resolves the single provider lambda from its
  bootstrap handle, requires that lambda to forward and nothing more, proves the decider never
  references the `BlockGetter` or `BlockPos` locals, and rules out any other implementation in the
  jar, in a nested jar, or in the registered block's own class hierarchy. Honoured at `AUDITED`.
  Generalising this is deliberately not attempted: it needs a transitive escape analysis, and both
  cheap substitutes are unsound -- invoking with a null world misreads a provider that branches on
  null, and checking only the entry method's locals fails on Farmer's Delight itself.
- Audit and exempt Lithium `0.24.6+mc26.1.2` at the `AUDITED` tier. Lithium ships in most
  performance modpacks and previously kept PathWeaver switched off in exactly the packs that want
  it. All eight of its sensitive claims are pinned by artifact hash and verified at startup.

### Changed

- Enforce the Lithium audit structurally rather than by assertion: every field write in the audited
  classes must occur in a constructor, a static initializer, or one of the two eager cache
  initializers, and no other method may call those initializers, so a worker cannot trigger a lazy
  write. The region mixin must additionally write only from a constructor injection, which is what
  makes its writes safe given PathWeaver builds the region on the main thread at dispatch.
  Lithium's inactive-navigations hook does mutate shared state, and is exempt on non-reachability
  instead: `PathFinder.findPath` has no call edge into `PathNavigation`.
- Lithium's mixin plugin is not inert, so it is pinned by hash and every exempted claim carries its
  plugin identity. A pack with different Lithium options produces different claims and falls back to
  denial rather than reusing this audit.

### Fixed

- Apply the compatibility tier at dispatch rather than at provider registration. Mods register blocks
  from their own initializer, which for Farmer's Delight runs before PathWeaver has loaded its config,
  so a tier read at registration saw the fail-closed default and denied regardless of the operator's
  setting. The audit outcome is published to the registry latch and the policy applied where config
  is known to be loaded.
- Warn when almost no search result is being used. Sweeping `maxInFlight` on a 371-mod pack showed it
  is an admission bound rather than a buffer: widening it converts refusals into latency, and a result
  that lands after its mob has asked again is superseded and dropped. At 1024 mobs repathing every 6
  ticks, measured as the share of dispatches not installed within the capture window: 13.5% at the
  shipped 256, 90.7% at 1024, and nothing installed at all while observing at 4096 -- with no errors
  logged and the server still at 20 TPS, so the pool spent CPU on work that was not being used while
  looking healthy. The counters record dispatches and installs and the harness then halts, so these
  are shares of work unused in time rather than proven terminal discards. The install ratio is now
  sampled once a minute and a warning names `maxInFlight` and `poolThreads` when under a quarter of
  completed searches are used. The README previously advised raising `maxInFlight` when that share
  climbs, which was backwards; corrected.
- Make `compatibilityTier=ALL` waive the mob-origin gate as well. The origin gate refuses mob classes
  defined by a mod because their navigation overrides have not been inspected — a compatibility check
  like any other — so leaving it armed under "ignore every check" kept most of a heavily-modded
  pack's mobs synchronous while the startup log reported `moddedBypass=false` and nothing denied.
  `allowModdedMobAsync` remains the way to reach that bypass from `STRICT` or `AUDITED`. The startup
  warning now distinguishes the two causes. Verified on a dedicated server outside the dev classpath:
  `moddedBypass` is `false` at `AUDITED` and `true` at `ALL`.
- Correct the shipped-configuration benchmark headline from a 44.8% mean tick-time reduction to
  about 40% (median 40.1%, range 36.2–48.2% over four pairs). The original figure came from a single
  pair whose synchronous baseline was the heaviest of four such runs; the asynchronous arm is stable
  at 49–50 ms across two builds while the synchronous baseline varies 78–96 ms with ambient load.
  The unused-work caveat widens from 14.8% to a 13.6–20.6% range for the same reason, and is now
  stated as dispatches not installed within the capture window rather than as a discard rate.
- Fix the game-test harness self-check, which asserted the harness contributes no sensitive mixin
  claim using `allMatch` over a stream that is empty before any scan publishes a report. It
  therefore returned true and failed open. It also could not observe a harness config that no
  `fabric.mod.json` declares, because such a config never reaches the attributed list and is
  recorded as a failure instead. Both paths now fail closed.

### Master Enabled switch (schema v2)

### Changed

- Replace `asyncEnabled` plus the lower `syncFallbackOnly` panic switch with one first-listed,
  default-on `Enabled` master. OFF gates both new async dispatch and repath reuse while work accepted
  before the save drains through its existing exact registration.
- Add raw-JSON schema-v2 migration. Legacy state maps with
  `enabled = asyncEnabled && !syncFallbackOnly`; explicit save removes both legacy keys while
  preserving subordinate settings subject to existing clamps. Malformed or future schemas fail closed.

- Add one narrow Swim-only compatibility candidate for the exact audited tuple Minecraft `26.1.2`,
  `fabric-content-registries-v0` `11.2.1+76b0b6bb4c`, module/config/mixin/vanilla-class hashes,
  and prepared declaration shape. Runtime ASM verifies Fabric modifies only
  `PathfindingContext.getPathTypeFromState`, while exact `SwimNodeEvaluator` and its shared search
  route reach only `getBlockState`/`level` and contain no land-provider lookup. Any version, hash,
  class shape, declaration, plugin, parse, or route drift denies both families. The independent
  `WalkNodeEvaluatorMixin` claim continues to deny Walk; subclasses and Amphibious remain ineligible.

### Accepted review nonblockers

- The Phase-1 CFG test's small dominator helper does not model exception-handler edges. The three
  asserted sites occur before the later guarded setup block in this exact method, and independent
  `javap` inspection confirms the required branch/return shape; accepted without broadening the helper.
- The live GameTest mutates the already-published config instance in place. Its assertions deliberately
  exercise the current live-save boundary, and manager review accepted that test shape; no refactor was
  stacked onto the independently approved Phase-1 tree.

### Publish blocker

- Publication remains held pending Phase-2 replica validation and independent exact-tree review. Normal
  aggregate Fabric API retains Walk denial. Swim is permitted only when every exact runtime fingerprint
  and ASM proof passes; any mismatch falls back to the prior synchronous denial.

## 0.2.3 — ModMenu category cleanup (2026-07-19)

### Fixed

- Prevent Cloth AutoConfig from materializing an empty raw `catalog.default` tab. AutoConfig groups
  excluded static implementation fields before excluding their entries; those fields now share the
  translated, populated General category instead of implicitly creating `default`.
- Add a regression contract requiring every declared field to have a non-default category, every
  generated category to be translated, and every generated category to contain a visible option.

## 0.2.2 — Whole-mod quality pass (2026-07-18)

### Fixed

- Safely publish live config saves across render/server threads, and force synchronous panic defaults
  when Cloth registration or loading fails instead of silently leaving async enabled.
- Retain Cloth's swallowed JSON-deserialization failure signal so malformed or unreadable persisted
  config also forces synchronous runtime defaults until a valid config is saved.
- Reconcile active Mixin configs against metadata for the current client/server environment; integrated
  servers no longer fail closed merely because a client-only config has no recorded owner.
- Normalize internal slash-form Mixin target names before every sensitive-target comparison, seed the
  safety gate denied until discovery completes, and replace final denial state atomically.
- Return safely to ModMenu when the generated config screen is unavailable.
- Report accepted deferred movement as successful so goals do not abandon pending work, while direct
  path queries remain synchronous and immediate.
- Gate each accepted worker search until its main-thread `onPathfindingStart` callback completes; every
  setup/rejection/exception path releases the gate, and callback effects happen-before worker reads.
- Bind each accepted movement's exact speed value through installation, including `0`, negative, and
  `NaN`, and refresh it when the same target is requested again while pending.
- Apply vanilla's cheap path-creation preconditions before tolerance reuse and clear recompute
  invalidation in a `finally` scope so exceptions cannot poison later navigation.
- Supersede accepted pre-change work before recompute's vanilla `canUpdatePath` guard and preserve the
  exact accepted movement speed across replacement dispatch.
- Keep mod-defined mob subclasses synchronous by default through a cached, fail-closed vanilla-origin
  gate; expose a clearly unsafe advanced override that bypasses only that gate.

### Compatibility boundary

- The origin gate closes direct and indirect mod-defined mob overrides. Remaining experimental surface:
  Mixins into vanilla `Entity`/`LivingEntity`/`Mob`, plus live vanilla mob/world/block reads without an
  immutable snapshot. Fabric content-registry hooks remain denied; no full-safety claim is made.

### Simplified

- Removed the permanently unused distance-throttle option, inert Fly evaluator mixin, production-dead
  annotation reader, test-only repath wrappers, and stale worklog comment prefixes.
- Restricted evaluator cloning to the only two async-eligible exact classes and changed routing-depth
  tests from compiler-opcode snapshots to normal/exceptional behavioral checks.

## 0.2.1 — Working ModMenu persistence and complete option help (2026-07-18)

### Fixed

- Excluded PathWeaver's static constants and runtime singleton from Cloth AutoConfig's generated entries. Cloth previously tried to write the first static-final field during `saveAll`, threw before serialization, and left `config/pathweaver.json` unchanged.
- Added short, honest tooltips for all nine persisted fields and grouped general, performance, and repath controls while keeping `asyncEnabled` first and the synchronous panic switch low in the general group. The unused `distanceThrottleEnabled` compatibility field is now explicitly labeled unavailable/no-op rather than implying behavior that does not exist.
- Saved values are normalized and republished to the live runtime configuration; `poolThreads` and `maxInFlight` are marked as restart-required because the worker generation is created at server start.

## 0.2.0 — Correctness, lifecycle isolation, and honest fail-closed final form (2026-07-18)

### Changed

- Added an explicit ModMenu entrypoint. `asyncEnabled` is the first persistent Cloth Config option with a short experimental warning; `syncFallbackOnly` remains the lower panic switch.
- Server start/stop advances an epoch; every async dispatch carries that epoch plus a process-unique request token and entity identity through worker completion and main-thread install.
- Every worker-pool generation owns its executor, in-flight capacity, and failure counters. Late interrupt-ignoring workers cannot mutate replacement-generation accounting.
- Install validation binds UUID/removal state, world/dimension, exact navigation/current-path identity, semantic target revision, movement, and bounded result age. Changed targets, recompute, stop, shutdown, and stale results terminally supersede or discard exact registrations.
- Worker completion is explicitly tagged `SUCCESS`, `NO_PATH`, or `FAILED`; ordinary vanilla no-path results do not trigger exception cooldown.
- Exact Walk searches replay one main-thread start/done callback pair; exact Swim searches replay none. All accepted terminal paths balance registration, including exceptional callbacks and diagnostics.
- Positive repath reuse requires a reached active path, exact reach agreement, valid endpoint, update-eligible navigation, and no recompute invalidation. Valid reuse advances target intent; block-change recomputation supersedes same-target pending work and uses fresh world facts. Default tolerance remains `0`.

### Honest compatibility and performance status

- PathWeaver remains fail-closed and synchronous in standard Fabric content-registry packs because dynamic path-type providers do not declare worker purity/safety.
- No universal-speed, vanilla-equivalence, or blanket thread-safety claim is made. Retained Spark profiles prove isolated server-thread A* offload but no reliable net MSPT improvement.
- A private immutable snapshot evaluator/A* was designed and cost-measured. Its simplified lower-bound capture failed the agreed relative-cost gate; correct cave/detour/provider coverage would cost more. The private engine and its load/scaling matrix were cancelled rather than forced through.
- An upstream immutable-chunk/provider-purity API is the only identified future path and is not part of 0.2.0.

## 0.1.2 — Default-on routing and fail-closed compatibility (2026-07-15)

### Changed

- Async search defaults **on** for newly generated configs. Existing files retain their explicit value;
  set `asyncEnabled=false` or `syncFallbackOnly=true` to opt out.
- Async routing is armed only by genuine navigation/recompute operations; direct and query-only
  `createPath` calls remain synchronous and do not dispatch or mutate navigation path/speed state.
- Foreign-mixin discovery now fails closed, reads Fabric-declared configs for every Loader-resolved
  container (including JiJ mods), inspects Mixin's prepared target sets including plugin contributions,
  and covers `NodeEvaluator`, concrete evaluators, `PathfindingContext`, `PathNavigation`,
  `GroundPathNavigation`, and `PathFinder`.
- Prefix and whole-mod trust rules were removed. Exemptions are exact mod-version/config/mixin-class/
  target audit tuples; none are currently granted.
- Scanner diagnostics report scanned, failed, and denied counts. The standard Fabric API stack is
  intentionally forced synchronous because its content-registry module exposes dynamic path-type
  providers through sensitive pathfinding mixins that are not proven worker-safe.

### Still unresolved

The worker still reads live chunk and mob state. True immutability required the then-approved but later
cancelled benchmark-gated private snapshot evaluator/A* port. Epoch/token/staleness, callback
accounting, tagged outcomes, and positive-tolerance repath validity remain separate slices. Default-on
does not imply proven safety, vanilla equivalence, or a net MSPT improvement.

## 0.1.1 — Honesty and default-off patch (2026-07-15)

### Changed

- Async search defaults off for newly generated configs; users must opt in explicitly.
- Repath tolerance defaults to `0`.
- Invalid pool-thread, in-flight, tolerance and staleness values are clamped before startup.
- Async eligibility is restricted to exact vanilla `WalkNodeEvaluator` and `SwimNodeEvaluator`.
- `FlyNodeEvaluator` is synchronous because its start-node selection consumes the live mob RNG off-thread.
- Documentation now calls `PathNavigationRegion` a read-only view backed by live chunks, not an immutable copy.
- Safety/equivalence/universal-speedup claims were removed. Real Spark evidence supports server-thread A* offload for the tested isolated Walk workload, but did not show a reliable net MSPT improvement.

### Known limitations

The current worker still reads live chunk and mob state. General `createPath` interception can alter query-only caller semantics. Lifecycle/staleness identity, callback accounting, result typing, repath invalidation and foreign-mixin discovery require the v0.2 rework. Dispatch rejection leaves the invocation synchronous; a worker exception only forces later requests synchronous during a cooldown and does not recompute the failed request.

Existing config files are preserved; users upgrading from 0.1.0 should inspect `asyncEnabled` explicitly.

## 0.1.0 — Initial release (2026-07-10)

First public alpha release of asynchronous A* search and conservative repath elision for Minecraft 26.1.x (Fabric).

The 0.1.0 release text described the design as “safe by construction” and the region as an immutable snapshot. The 0.1.1 review found those claims were not supported: the region is backed by live chunks, evaluators read live mob state, and additional API/lifecycle/scanner defects remain. Those earlier statements are historical claims, not current guarantees.

### Features

- Bounded-worker A* dispatch behind an exact evaluator-class gate.
- Configurable repath tolerance.
- Fresh finder/evaluator and per-thread path-type cache per async request.
- Main-thread completion/install path, synchronous fall-through on dispatch rejection, and a later-request sync cooldown after worker failure.
- Startup foreign-mixin scan that reduces eligible coverage for recognized evaluator targets.

### Requirements

Fabric API, Cloth Config, Minecraft 26.1.x and Java 25.
