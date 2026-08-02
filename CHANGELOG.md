# Changelog

## 0.5.0 — Works on arrival

### Changed

- **The default `compatibilityTier` is now `Unsafe`, so out of the box PathWeaver runs other mods'
  uninspected pathfinding code on worker threads.** This is a risk decision and not a small one, so
  the reasoning is stated rather than buried. `Audited` honours individual bytecode audits and one
  bounded call sample; any mod outside that evidence denies every movement family. On a 222-mod pack
  that left **0 of 187** mob types eligible, and it has been 0 since 0.3.0 — no release has improved
  it, because the limit is other mods touching block state, not anything this mod can fix. Shipping
  `Audited` shipped something indistinguishable from broken.

  What is being accepted: the failure mode is a data race — a wrong path, a torn read — not a crash
  and not a corrupt region file. It is quiet, and a user who hits it will most likely never report
  it. Nothing in this release is evidence that it is safe. It is a trade of a silent risk for a mod
  that works on arrival.

  Unchanged and still one setting away: `compatibilityTier=Audited` for full checking, and
  `trustedMods` for naming individual mods rather than waiving everything. The startup `WARN` block
  still fires, so this is never silent — and it now names the mods in the block itself; see below.

### Fixed

- **A config carrying the retired `overrideCompatibilityScan: false` no longer migrates to
  `Unsafe`.** That key meant "do not bypass the compatibility scan", and the migration resolved it
  through the shipped default — so the moment the default became `Unsafe`, an explicit refusal to
  bypass the scan was answered by turning the scan off entirely, silently and with no log line. It
  now maps to a literal `Audited`, which is what the stored value asked for, and says so in the log
  (it is stricter than a fresh install, so an operator who sees PathWeaver refuse deserves to know
  why). Found in review; it is the one setting whose stored value is an explicit refusal of exactly
  what the new default does.
- **The test guarding that migration was a tautology.** It asserted the result against
  `new PathWeaverConfig().compatibilityTier` — the same expression the production code used — so it
  compared the code to itself, could not fail for any default, and passed unchanged through the flip
  while certifying the defect above as intended. Replaced with literal expectations plus
  `everyLegacyConfigShapeResolvesToALiteralTier`, which pins every accepted legacy config shape to a
  literal tier. The rule is now "no migration path may resolve through the shipped default", because
  a default is precisely the thing that changes under a migration written years earlier.
- Game-test harnesses now pin `compatibilityTier` on disk for **every** harness rather than only the
  audited one. The audited harness wrote that file into the shared run directory and nothing deleted
  it, so a later default run inherited it. That was invisible while the shipped default was also
  `Audited`; with the default changed it would have meant a default run silently exercising a tier
  the mod no longer ships, and only on machines that had run the audited harness at some point.
- The harness config no longer hardcodes `configVersion`; it is read from `PathWeaverConfig.java`. A
  stale literal would not have failed as "wrong version" — the serializer would reject the config,
  fall back to `enabled=false`, and every game test would fail at once pointing nowhere near the
  cause.
- The harness seeding now yields to a staged `StartupConfigMigrationProbe` fixture instead of
  overwriting it.

### Added

- **`-PunsafeTierHarness`, a live witness for the configuration this mod actually ships.** Every
  other harness pins `Audited`, because every other harness exists to watch the gate close — which
  left the shipped default, the state every new user starts in, as the only configuration with no
  end-to-end coverage. It runs at `Unsafe` at scan time, on a harness that deliberately keeps a mixin
  into sensitive pathfinding code so the scan genuinely denies something first, and asserts the chain
  an operator depends on: the denial happened, the waiver cleared it, the evaluator is admitted, a
  real mob dispatches, and a path installs. The non-vacuity assertion is the load-bearing one — at
  `Unsafe` nearly everything worth asserting is trivially true, so the test fails unless there was
  something to waive.

### Changed (also)

- The `Unsafe` startup block now names the unaudited mods **in the block**, rather than saying "the
  mods listed above" and leaving the reader to find per-config lines emitted hundreds of lines
  earlier during mixin scanning. That is the same argument that justified adding the world-start
  report in 0.4.0.
- `/pathweaver status` reports the tier **in force** rather than the field on disk. The field is what
  a settings save writes; the policy was frozen at scan time and does not follow it. Printing the
  field labelled "frozen at startup" told an operator who had just selected `Audited` that they were
  running checked, on a session still running unchecked — now the common direction of travel.
- Corrected three surviving statements that the default is the safe tier, in the startup log, in
  `COMPATIBILITY.md`, and in the Lithium exemption's javadoc.

### Fixed (thread safety)

- **`Mob.maxUpStep()` was being called from worker threads, and it is not a read.** It resolves to
  `AttributeInstance.getValue()`, which on 26.1.2 is
  `if (dirty) { cachedValue = calculateValue(); dirty = false; }` on plain non-volatile fields, while
  `calculateValue()` walks the modifier collections. `WalkNodeEvaluator` reaches it from
  `getNeighbors` and `getMobJumpHeight` — inside the A* loop, so hundreds of times per search. This
  is the one place the design's central claim (all live-mob writes live in `prepare()`/`done()`, the
  search between them only reads) was wrong: every *explicit* mutation in all six evaluators does obey
  it, and a write hidden behind an attribute getter did not.

  A dispatch-time pre-resolve was the previous mitigation and was insufficient — it clears `dirty` at
  that instant, and equipment, a potion effect or a mod re-dirties it while the request is still in
  flight. The worker is now given the value instead of the call, exactly as it already is for
  `Mob.getRandom()`. The quiet failure this removes was the dangerous one: a worker publishing
  `dirty = false` without `cachedValue` being visible to the main thread leaves a mob's step height
  wrong for the rest of the session, with nothing in any log connecting it to pathfinding.
- **Dispatch now also requires the navigation's `PathFinder` to be exactly vanilla's.** Dispatch
  builds its own `new PathFinder(...)`, ignoring whatever `createPathFinder(int)` returned, while the
  gate only checked the evaluator class. A mod shipping a `PathFinder` subclass paired with a stock
  `WalkNodeEvaluator` passed, and its mobs would run the mod's A* on every synchronous fallback and
  vanilla's on every async dispatch — routing that flips with worker-pool load.
- The startup report now warns when `Unsafe` has waived the land path-type latch and a mod has
  registered an uncertified "mobs should avoid this block" rule. Workers cannot read that rule, so
  off-thread searches treat those blocks as ordinary ground while synchronous ones avoid them. The
  tier waiving it was defensible as an explicit opt-in and is weaker as the shipped default, so it is
  at least no longer silent. Certification already covers the audited providers, so this fires only
  for genuinely unknown ones.
- **A hard server stop no longer abandons every owed epilogue.** `clear(false)` dropped all of them
  when the worker pool failed to quiesce, on the grounds that running `done()` against a live search
  is worse than leaking. True for searches a worker actually entered — and over-broad for the rest.
  The gate is now carried with the owed epilogue, so a request whose `SearchStartGate` was never
  opened (no worker was ever authorised to read that evaluator, so nothing can be raced) still runs
  its `done()`. The leak this closes is not cosmetic: `AmphibiousNodeEvaluator.prepare()` sets the
  mob's `WALKABLE` cost to 6.0 and `WATER_BORDER` to 4.0, and only `done()` restores them, so an
  abandoned epilogue left a drowned or axolotl carrying search-time costs for as long as it stayed
  loaded — outliving the shutdown the abandonment was protecting.
- Corrected a `SafetyGate` claim that the frog's and creaking's evaluators touch the mob solely
  through `getBoundingBox`. The creaking's reads `SynchedEntityData` via `getHomePos()`. It is still
  admitted — a stale home position only shifts the 1024-block cutoff against a slightly old anchor —
  but that is a judgement, and stating it as the absence of a read was wrong.

### Fixed (the safety gate)

- **A scan *failure* is no longer waivable by the tier.** `decide` converts any failure into a blanket
  denial of every family, and the `Unsafe` branch cleared the denial set unconditionally — so an
  unreadable `fabric.mod.json`, two mods claiming one config name, Mixin internals drifting, or a
  declared config that never prepared all terminated in ALLOW on a stock install. Thirteen
  fail-closed paths, every one of them ending in "run anyway". The reporting made it worse: the
  warning block names mods from configs that were read *successfully*, so a pack denied purely by a
  scanner malfunction would have been told nothing was responsible. The tier waives what the scan
  found; it does not waive the scan being unable to look.
- **`EvaluatorCloner` no longer guesses between constructors, and no longer prefers a no-arg
  constructor over the mob's actual configuration.** `getDeclaredConstructors()` has no specified
  order, so a third-party evaluator with two resolvable single-argument constructors could be rebuilt
  differently between JVM runs, with the loser's field left at its default and the result cached for
  the process lifetime. Ambiguity is now refused, the same way field ambiguity already was. Separately,
  a subclass keeping its own state *and* offering a convenience no-arg constructor was rebuilt with
  that constructor's defaults — so an async search used one configuration and every synchronous
  fallback another, for the same mob.
- **`canClone` can no longer throw out of the safety gate.** `getDeclaredConstructors()` and
  `getDeclaredFields()` raise `NoClassDefFoundError` — an `Error`, not an exception — when a parameter
  or field type comes from a soft dependency the user did not install. It propagated through
  `ClassValue`, past `SafetyGate.isAllowed`, and out of the entity tick, before the dispatch path's
  protective `try`. A gate whose failure mode is a server crash is worse than the risk it screens for.

### Known limitation (documented, not fixed)

- **`SHARED_PATHFINDING_TARGETS` is incomplete, and its javadoc no longer claims otherwise.** It
  listed `PathNavigationRegion` but not the tail of the block read — `LevelChunk`,
  `LevelChunkSection`, `PalettedContainer`, `BlockGetter`. On an ordinary performance pack that is not
  hypothetical: Lithium `@Overwrite`s `LevelChunk.getBlockState`, Lithium and FerriteCore both replace
  `PalettedContainer`'s threading detector, and ServerCore mixes into `BlockGetter` through a config
  whose bytes this project already pins — so the audit read the file and looked past the target.

  Adding those classes was tried and measured: it makes `AUDITED` deny every family on any pack
  containing Lithium, because Lithium's chunk mixins sit outside its pinned pathfinding exemption.
  That is the gate working correctly, and it is a product decision rather than a bug fix, so it is
  recorded rather than half-done. The shipped default waives this list wholesale, so the gap changes
  nothing out of the box; it matters to an operator who chose `AUDITED` believing it covered the whole
  read path, which is precisely why the completeness claim had to go.

### Notes

- **Reviewed and deliberately NOT changed:** the supersede at `recomputePath`'s `canUpdatePath()`
  call. It fires upstream of the branches where vanilla then recomputes nothing, which looks like an
  ordering bug and has now been raised twice. It is deliberate: what invalidates the pending search
  is the world change that caused `recomputePath` to be called, not whether vanilla can act on it
  this tick. Deferring the cancel would keep work computed against the pre-change world alive across
  every tick where `canUpdatePath()` is false and then install it. The cost is a wasted search on
  those ticks; the alternative is a wrong path. Reasoning now recorded at the injection site.
- Two things reported as defects during profiling were re-checked against the code and are **not**
  defects, recorded here so they are not "fixed" later by someone reading the same profile. Idle
  `PathWeaver-Worker` threads in a profile taken with the mod disabled are a pool decaying after the
  live master switch was flipped: the executor creates core threads on demand and
  `allowCoreThreadTimeOut(true)` retires them after 30s, so a disabled mod creates none. And
  `poolThreads` is a ceiling, not an allocation — observing eight live workers means burst demand
  actually reached eight, so lowering it would serialise those bursts.

## 0.4.0 — Every vanilla mob, and counters that mean something

_There are two tiers from this release: `Audited` (default) and `Unsafe`. The tier named `ALL` in
older entries is `Unsafe`, and `Strict` no longer exists. Entries for 0.3.0 and earlier keep the old
names deliberately: they describe what those versions actually shipped._

### Added

- **Flying and amphibious mobs now path off-thread, along with the frog's and creaking's evaluators.
  All six concrete vanilla evaluators are eligible.** The old exclusion said these evaluators mutate
  the live mob during a search. They mutate it — but only in `prepare()` and `done()`, never in the
  search between them: all five of the amphibious evaluator's `setPathfindingMalus` calls sit in
  those two methods, and the flying evaluator's single `Mob.getRandom()` read is in start-node
  selection. So the two ends now run on the main thread and the search keeps running on a worker. The
  frog's and creaking's evaluators came along unchanged, touching the mob only through
  `getBoundingBox`.
- `/pathweaver status` and `/pathweaver mobs`, answering in-game which mob types can path off-thread
  and, for each one that cannot, why. Diagnosing that previously meant building a throwaway probe
  against the mod's internals.
- **PathWeaver now says at world start whether it is going to do anything.** The scan already logged a
  line per offending mod, but those appear during early mixin scanning, hundreds of lines before a
  world loads, phrased as a warning about the other mod rather than a statement that this one is
  switched off. The result was a mod that installs, does nothing, and never mentions it. On a
  heavily-modded pack that is the normal outcome, not an edge case. When inert, the log now names the
  mods responsible, says plainly that every movement family is running on the server thread exactly as
  vanilla, and gives the one-line override. Measured on a 222-mod pack: nine mods named.

### Fixed

- **The unsafe tier's evaluator-subclass waiver had never dispatched a single search.** The safety
  gate admitted third-party subclasses; a table of callback counts then rejected them on exact class
  identity, and a third check — the evaluator rebuilder — rejected them again. Every admitted
  subclass built a region, cloned an evaluator, submitted to the pool, counted a dispatch, and
  unwound to a synchronous search, invisibly, because the fallback is correct. The table is gone: the
  epilogue is now the evaluator's own `done()`, and the rebuilder resolves a constructor shape rather
  than naming classes.
- **A foreign mixin into `WalkNodeEvaluator` denied only walking mobs.** Flying, amphibious, frog and
  creaking evaluators all execute Walk's code, so they kept dispatching through exactly the code the
  scan had objected to. Denials now match by inheritance.
- **`discarded` was counting successful searches as waste.** It conflated at least eight outcomes,
  including searches that ran to completion and correctly proved no route exists. Each outcome is now
  counted separately, and the shutdown line and `/pathweaver status` report the breakdown. This also
  explains why the adaptive admission controller attempted during 0.3.0 could never converge: it
  steered on a ratio that mobs stopping normally held above target regardless of the admission bound.
- Documentation claims falsified by the 0.3.0 tier rename, including a README line stating that
  evaluator subclasses always stay synchronous and a compatibility-matrix line calling `STRICT` the
  default. Historical measurement records keep the tier name they were measured under.

### Changed

- The modded-mob toggle is labelled unsafe again and no longer refers to a tier name that does not
  exist. It waives exactly one gate, the mob-origin check; the unsafe tier waives three and includes
  this one.
- Game tests read the runtime's counters through public accessors instead of reflecting into a
  private field, which is why renaming that field broke three of them at once.

### Removed

- **The `Strict` tier is gone.** It honoured only structural proofs, and the exemption covering Fabric
  API's own interaction module is a bounded call sample rather than a proof — so it denied every
  install containing Fabric API, which this mod requires. It could not do anything on any pack that
  has ever existed. A setting that is inert everywhere is not a safety option. Configs holding it
  migrate to `Audited`, which is now the most conservative tier, and the migration says so in the log
  because it is still a loosening.

### Introduced and fixed during this release (never shipped)

Recorded because each one looked correct and did nothing, which is the failure mode this project keeps
producing and the only reason to write them down.

- **Hoisting the prologue silently defeated `PathfindingContextMixin`.** That mixin swaps the level's
  shared `PathTypeCache` for a thread-confined one, keyed on whether the *calling thread* is a worker.
  Once `prepare()` ran on the main thread that condition was false, so every async search was built
  around the shared cache and handed to a worker that writes through it — the exact unsynchronised
  shared write the mixin exists to prevent. It stayed present, mandatory and passing its own contract
  test throughout. It now keys on whether the *search* runs off-thread.
- **The epilogue raced the worker that still owned the evaluator.** `done()` clears two caches and
  nulls the pathfinding context, and `supersede()`/`stop()` invoked it while the search could still be
  running. The epilogue is now owed and released on the drain path, keyed by request rather than by
  navigation.
- **The prologue scope was set and cleared rather than saved and restored**, so a mod override that
  started another mob navigating would have cleared it for the outer search.

### Known gaps

- **The four newly eligible families have no path-equivalence evidence.** The static-world oracle
  comparison covers Walk and Swim only and has not been re-run. Their safety is argued from bytecode.
- **Flying searches deliberately do not reproduce vanilla's start node**, because a worker draws from
  thread-confined randomness rather than the mob's. Vanilla picks that candidate arbitrarily, so both
  are valid, but they are not identical.
- **Amphibious mobs carry their search costs for roughly one tick** rather than only during the
  search, because the malus is applied at dispatch and restored at install.

## 0.3.0 — Compatibility tiers, audited exemptions, and the first shipped-config benchmark

_Published to Modrinth as `YT7oDxzQ`. The audits below are version-exact; see COMPATIBILITY.md._

### Added

- Add `compatibilityTier` (`STRICT` / `AUDITED` / `ALL`), replacing the blunt
  `overrideCompatibilityScan` boolean. `STRICT` only runs off-thread where a worker provably cannot
  observe another mod's change. `AUDITED` additionally honours exemptions resting on bounded evidence
  rather than proof, and the four mechanisms behind it are not equally strong: a field-write-opcode
  check for Lithium and Diagonal Blocks, a direct-call inventory over six pinned classes for Fabric's
  interaction module, a purity assumption for generic block-danger providers, and one artifact's
  bytecode for Farmer's Delight. `ALL` ignores the scan entirely. **`AUDITED` is the shipped default**, because `STRICT` admits only structural proofs and
  the Fabric interaction exemption is a bounded call-sample, so `STRICT` denies any pack containing
  Fabric API and would ship a mod that does nothing on install. Configs carrying the retired boolean
  migrate to `ALL` when it was on and to the shipped default otherwise — that boolean selected the
  then-current scanner rather than a tier that existed, so mapping it to `STRICT` would take working
  installs inert on upgrade. An explicit tier always wins, and an unreadable tier fails closed.
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
