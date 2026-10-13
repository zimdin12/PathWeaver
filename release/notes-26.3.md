Minecraft **26.3** build of 0.9.0. Everything the 26.1.2 changelog describes applies here, with one
exception that matters on a performance pack, and one change to the navigation hook that 26.3 forced.

### The hook moved, because vanilla moved

26.3 added `PathNavigation.moveTo(Entity, int, double)`. The old `moveTo(Entity, double)` now does
nothing but delegate to it, and `MeleeAttackGoal`, which is what a hostile mob chasing a player runs,
calls the new overload directly. PathWeaver's three hooks for entity-target movement therefore sit on
the inner overload on this branch: the speed capture, the deferred-acceptance return, and the wrapper
around `createPath`. On 26.1.2 and 26.2 they sit on the two-argument method.

Nothing about which path a mob gets changes. The request is claimed one level further down the same
call, and the delegate returns the inner result unchanged.

Left alone, the wrapper would have had no call site to bind to on 26.3. It is declared `require = 1`,
so that is a refusal to load rather than a mod that runs with a hole in it.

### `AUDITED` does not cover Lithium on 26.3

Lithium 0.26.2 is the build a 26.3 pack installs, and this release does not audit it. Five of the
eight audited classes are byte-identical to the 0.25.3 build audited for 26.2. Three are not: two
gained a field writer, and one differs only because 26.3's Mixin declares an injection point as an
array. The exemption allows field writes only in constructors, static initializers and one named
initializer per class, and the two new writers are not in that set, so certifying 0.26.2 needs an
argument about when they run and whether a worker can reach them. Nobody has written that argument.
The audit is a statement about exact bytes, so it refuses 0.26.2 by version and says so in the log
rather than being re-pinned on a proof nobody ran.

What this means in practice. At `compatibilityTier=AUDITED` on 26.3, any pack containing Lithium has
every movement family denied, so PathWeaver does nothing. The shipped default is `UNSAFE`, which reads
none of this and is unaffected. `LithiumPathfindingCompatibility` states what a 26.3 re-pin has to
prove, for whoever picks it up.

Diagonal Blocks 26.3.0, ServerCore 1.5.20+26.3, rabbit-pathfinding-fix 1.5.0 and both Fabric API
modules are audited on 26.3, all re-derived against the artifacts a 26.3 install resolves.

### Verified on 26.3

- 69 suites, 515 unit tests, 0 failures
- 9 of 9 game-test harnesses, one attempt each and no reruns, every row checked against the
  manifest it actually consumed and the providers on its classpath at launch: 21 tests
  (`docs/evidence/port-26.3/HARNESS-26.3.txt`)
- a real 26.3 client with an integrated server: searches dispatch and install, and the settings
  screen draws (`docs/evidence/port-26.3/CLIENT-26.3.txt`)
- a clean 26.3 server without Cloth Config starts with PathWeaver active, and a control build with
  the old Cloth dependency put back fails to start (`docs/evidence/port-26.3/no-cloth/`)
- all 36 of the 0.9.0 four-state witnesses re-run here, each fix reverted and its test seen to fail
  for the expected reason (`docs/evidence/port-26.3/WITNESS-26.3.txt`), plus two more for the two
  things 26.3 broke: the navigation hook and the manifest range
- the jar was built from a fresh clone, and its declared versions were read back out of the built
  jar (`docs/evidence/port-26.3/RELEASE-JAR.txt`)

### Also worth knowing

26.3's Mixin declares `@Redirect.at()` as an `At[]` rather than a single `At`. That is invisible in
production code and broke three of our own annotation readers, which now reject a redirect declaring
more than one injection point instead of reading the first of several.

Two dependency traps, in case they bite you too. ServerCore publishes `1.5.20+26.3` twice on Modrinth,
once for Fabric and once for NeoForge, and a Maven coordinate on the version number can resolve the
NeoForge jar: it carries the same mixin class and no `fabric.mod.json`. And both Fabric API modules
this mod audits now differ byte-for-byte between the standalone artifact and the copy nested inside
the aggregate `fabric-api` jar, so the audit accepts either packaging and pins the mixin bytes exactly.

If you run ServerCore, make sure it is the 26.3 Fabric build.
