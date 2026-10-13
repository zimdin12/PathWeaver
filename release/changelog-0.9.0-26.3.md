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

---

# Everything in 0.9.0

Correctness and honesty, a per-search cost removed from searches that stay on the server thread, and two changes you will notice: Cloth Config is no longer required, and there is a new optional setting for distant mobs.

## Searches PathWeaver leaves on the server thread no longer pay for it

Some path searches stay on the server thread: villagers looking for a bed or a workplace, which use the
answer straight away, and any mob PathWeaver decides not to take. Those searches were slower than with no
PathWeaver at all. Two checks ran once for every node of every search, on every thread, including the
ones they had nothing to say about: a thread-local lookup, and a hook that created a small object on
every call. Both are in 0.8.0 too; only the 0.9.0 build was measured.

Both are gone. In a 317-mod client with a village, villager point-of-interest searches took 24 to 30% of
the server tick in every run with the old build, 20 to 24% with no PathWeaver, and 23 to 26% with this
one. It is a few percent of tick, not a lag spike, but it was a cost on exactly the searches this mod
cannot move off the thread.

**The lag report that led here was not PathWeaver.** In that pack, Enhanced Cats sends every villager
within 10 blocks of a cat towards it, every tick, on the client as well as the server, and the game
lagged with PathWeaver removed too. If your game lags when cats are near villagers, try without
that mod before blaming this one.

## Route sharing survives a settings change

Switching route sharing or the master switch off and on again could leave a stored route in play across changes made while it was off. PathWeaver stops watching for block changes when either switch is off, but stored routes were only discarded when the server stopped, so a route could be handed out after terrain nobody had been watching moved underneath it.

Both switches now discard what was learned under the previous settings, including searches that were already in flight when you changed them. This was reachable from the settings screen in 0.8.0 and 0.8.1 with no restart, and it is the reason to update if you had turned route sharing on.

## Villager paths at the age limit are no longer thrown away

A finished search that arrived at exactly the configured age limit was accepted by one part of the mod and rejected by another, so the work was done and discarded and the mob ran its own search anyway. At `maxResultAgeTicks=1`, which the settings allow, that was every result except the ones that finished within the same tick they were dispatched.

Default settings were affected far less than that, but the wasted searches were real.

## A villager path from the future

If server time moved backwards, on a world reload or a rollback, a finished path already set aside for
a villager could still be handed to it, answering a question asked before the search was dispatched.
The check that refuses a result from the future ran when the result arrived and not when it was
collected, and those are the same rule at two different moments.

## `/pathweaver status` stops overstating route sharing

The line reporting how many searches route sharing skipped was adding together searches that really did not run and cache hits that saved nothing. Hits recorded while the cache was measuring have no stored route to hand out, so they skip nothing. They are now on their own line, and only the first number is a saving.

The cache capacity shown was the one in your settings file rather than the one in use. Capacity is fixed when the server starts, so if you changed it since, the screen now says the change is waiting for a restart.

**If you decided against `SERVE` on the old number, the honest one is smaller.** If you decided for it, look again.

## The route sharing tooltip was too strong

The settings screen said a reused route "is never a stale one". It now says what actually holds: a route is dropped when a block changes along it, and a change away from the route is not noticed, exactly as vanilla does not notice it either. The old wording is in the 0.8.0 and 0.8.1 jars where no page edit could reach it.

## Settings files are checked properly

`resultCacheMode`, `resultCacheMaxAgeTicks` and `resultCacheMaxEntries` were not type-checked when the config was read, so a hand-edited mistake in one of them was quietly coerced instead of reported. Every persisted setting is now checked, and the check is derived from the settings themselves rather than a list that had already fallen behind three times.

## Compatibility checking

The Lithium audit now pins the vanilla pathfinding class it inspects by hash, like the other audits do. It previously inspected whatever was loaded, on the argument that re-deriving the check each time was stronger than pinning it. It is not: that check reads one class and does not follow what that class calls, so on a changed class it would have reported nothing while proving less than it looked like it proved.

No compatibility outcome changes as a result, on any version. Both downloads verify all seven audits on the Minecraft version they are built for; see the project page for what happens if you install the wrong one.

## New: is pathfinding even your problem?

[A ten-minute check](https://github.com/zimdin12/PathWeaver/blob/master/docs/IS-IT-YOUR-BOTTLENECK.md) you can run before installing this mod, using spark. PathWeaver only helps a server whose tick time is going into mob path searches, and plenty of struggling servers are slow for some other reason. It also says how to read the profile without the two mistakes that are easy to make with it.

## Cloth Config is no longer required

PathWeaver needed Fabric API and Cloth Config. Cloth Config draws the settings screen, which a
dedicated server never shows, so every server owner was installing a GUI library to run a server-side
mod.

It now needs Fabric API and nothing else. Install Cloth Config if you want the settings screen; without
it the mod reads and writes the same `config/pathweaver.json` and behaves identically. Nothing about
your existing config file changes and no setting is lost.

## New, and off by default: redo distant path searches less often

When a block changes on the route a mob is walking, the game redoes that mob's whole path search. It
will not do it more often than once a second for any one mob, but in a place where terrain keeps
changing, every mob whose route crosses the change pays for it, whether anyone is near enough to see
it or not.

`lodEnabled` widens that limit for mobs beyond 64 blocks from every player: one search every 40 ticks
instead of the game's once a second. Both numbers are settings. A mob picking a new destination is
never affected; this only delays redoing a route the mob already has.

**It ships off, and that is deliberate.** It is the only setting in this mod that keeps a mob walking a
route after the ground under it has changed. (An earlier version of this changelog said everything else
gives a mob the path it would have had anyway. That was too strong: by default a mob whose target moved
by a block or less keeps its current path, `repathToleranceBlocks=1`.) This lets a distant mob keep
walking an out-of-date route for longer after the ground under it changes. That is bounded and remote, but it is a real difference, so it is offered rather than
taken.

Worth turning on for mob farms, penned herds near redstone, or a high simulation distance. It does
nothing at all for mobs whose surroundings are not changing, because the game never redoes their
search in the first place.

## Not in this release

No change to the mod's core numbers. This exact jar was put back through the benchmark against 0.8.0
and against no mod: it is no slower than 0.8.0. Re-measured on 2026-10-13 with the zombies really
chasing a player (the September runs had lost their test player), it saves 4 to 5.5% of tick time at
2500 to 10000 zombies and 8 to 12% at 500 to 1000. This line used to say 5 to 9%.

For LOD there is only a best case. In a test built to favour it, 400 zombies at least 70 blocks from the
player with terrain changing among them all the time, turning it on cut the path searches PathWeaver
started by 13 to 19% and did not measurably change tick time. What it saves on your server depends on
how much terrain changes around mobs nobody is standing near, and in a world where it does not change,
it saves nothing.
