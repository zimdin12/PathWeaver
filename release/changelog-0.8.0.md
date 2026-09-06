Villagers path off-thread, mobs can reuse each other's routes, and two settings that were switched off by accident are switched on.

## Player-visible

- **Villager brains no longer path on the tick.** Brain mobs, which is villagers, piglins, axolotls, frogs, allays, camels and about twenty other AI packages, never call `moveTo`. Every route they walked went through one method that asks and reads the answer on the next line, so the mod could not see them at all. They were not refused; they were invisible. `brainSinkAsync` covers that route and is on by default.
- **That costs one tick.** A brain mob sets off one tick after it otherwise would, because on the tick its search is dispatched the behaviour is told "no path yet". Nothing else differs. It is a setting rather than unconditional behaviour for exactly this reason; set `brainSinkAsync=false` if you would rather not have it. The warden is not covered: its navigation builds a custom pathfinder and dispatch requires the stock one.
- **Mobs can reuse a route another mob already computed**, when every input to the search is identical: the terrain cost for each path type, the exact position, the bounding box, step height, fall distance, whether the mob is on the ground or in water, and its own class. A route is dropped the moment a block changes along it. What a mob gets back is exactly what its own search would have returned.
- **Route sharing ships measuring, not serving.** How often mobs repeat a search depends on your world rather than on this code, so the default fills the cache, runs every check, and reports what serving would have saved while every mob still searches for itself. Read `/pathweaver status` and set `resultCacheMode` to Serve if your number justifies it. No restart.
- **Path reuse was switched off and is now on.** `repathToleranceBlocks` shipped at 0, and 0 means the reuse never runs. It was inherited from a retired flag, so the feature was advertised as working and did nothing. It is 1 now, and upgrading migrates an existing config rather than leaving the fix for new installs only, because a saved settings file wins over a code default. Any value other than 0 is left alone and the migration says what it did in the log.
- **Automatic worker sizing has a floor of two.** One quarter of your CPU threads gave a single worker on a four-thread machine, and one worker serialises every search behind the one in front. Results expire, so that queue became discarded work rather than slower work.

## Measured

Twenty-one runs on a 231-jar dedicated server, 200 mobs covering every movement family, three rounds per arm, arms interleaved. All 21 passed their controls, and the fault scan found zero exceptions, zero mixin failures and zero worker failures across the 11 mods that modify pathfinding.

- **Villager brains off the tick:** server-thread pathfinding `4244, 3536, 7328 ms` becomes `2160, 1984, 2140`; tick time `9.05, 6.86, 15.83 ms` becomes `6.82, 5.68, 6.62`. Every run with it on beats every run without. The spread is the point: fewer spikes is the claim, and an unstable column beside a tight one is what that looks like.
- **Route sharing**, against not having the mod installed at all: server-thread pathfinding down 46.4%, tick time down 9.8%, total pathfinding CPU across every thread up 5.7%, because moving work is not removing it.
- **And the part that is smaller than it sounds.** Against the cache being off rather than absent, sharing bought 2.9% of total pathfinding CPU and the run ranges overlap. That arena is 200 mobs walking corridors without stopping, close to the worst case for a feature aimed at mobs that stand still and re-ask. This is why it ships measuring.

## The checked tier on 26.2

`compatibilityTier=AUDITED` did nothing at all on 26.2 before this release: every audit pinned 26.1.2 artifacts, so all four refused and all six movement families ran on the server thread. All four are re-derived. One, `rabbit-pathfinding-fix` 1.4.0, had changed mechanism rather than drifted.

**Lithium and Diagonal Blocks were not re-derived.** On 26.2 they resolve to builds their audits do not cover, so either one present makes `AUDITED` refuse everything, and most performance packs ship Lithium. If you run 26.2 and want the checked tier, it is not there yet. The shipped default is unaffected and the world-start report names the mods responsible. This was found by running every test harness rather than the five that were on record.

## Defects found and fixed in this work

- The audit enumerators silently skipped any handler whose annotation they did not recognise, so an artifact could carry an extra modification that the "modifies exactly two methods" count never saw.
- The scanner kept its own copy of an audited artifact's identity, so moving a pin and not the copy denied every family on a build that declares that artifact audited. It failed closed and was still wrong.
- Measuring route reuse copied every route it was never going to hand out, costing 6.6% more total pathfinding CPU than having the cache off, on three runs against three with no overlap. Caught by the benchmark, fixed, and re-measured at +0.6% with the ranges overlapping.
- Nothing computed on the first tick of a world could be cached, because the section clock's "never changed" value was zero and zero is also a real tick.
- A section cover written as a loop that steps by its own reach never terminated at a reach of zero. Unreachable at the shipped constant, which is why review missed it, and a thread dump is what found it.

## Testing

428 unit tests, eight in-game server harnesses, a client harness driving a real singleplayer world, and 21 measured runs across six configurations. Mutation testing is the release gate: 12 mutations against the route cache and 5 against the config migration, every one of them caught bar a single guard that was proved to be dead code and removed.
