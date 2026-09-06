Villagers now path off the tick, mobs can reuse each other's routes, and two settings that were switched off by accident are switched on.

## Villagers path off-thread

Villagers, piglins, axolotls, frogs, allays, camels and about twenty other brain-driven mobs never called the method PathWeaver was watching. Every route they walked went through a different one, so the mod could not see them at all. They were not being refused; they were invisible. `brainSinkAsync` covers that route and is on by default.

**It costs one tick.** A brain mob sets off one tick after it otherwise would, because on the tick its search is dispatched its behaviour is told "no path yet". Nothing else differs. That is why it is a setting: `brainSinkAsync=false` turns it off.

Measured on a 231-jar server with 200 mobs, three rounds each way. Server-thread pathfinding went from `4244, 3536, 7328 ms` to `2160, 1984, 2140`, and tick time from `9.05, 6.86, 15.83 ms` to `6.82, 5.68, 6.62`. Every run with it on beat every run without. The spread is the point: without it the arena ran between 6.9 and 15.8 ms per tick, with it between 5.7 and 6.8.

The warden is not covered. Its navigation builds a custom pathfinder, and PathWeaver only dispatches for the stock one.

## Mobs can reuse each other's routes

When two searches agree on every input they produce the same route, so the second one is repetition. A finished route is now kept and an identical later request is answered from it.

Identical is strict. Same starting position, same target, same mob size, same terrain costs, same movement flags. A stored route is dropped the moment a block changes along it. What a mob gets back is what its own search would have returned.

**It ships measuring rather than serving.** How often mobs repeat a search depends on your world, so the default counts what sharing would have saved and hands out nothing. Read `/pathweaver status`, then set `resultCacheMode` to Serve if the number justifies it. No restart. On the benchmark the hit rate was 12 to 16%, and sharing dispatched 11% fewer searches.

Against not having the mod installed at all, sharing gives 46% less server-thread pathfinding and 10% lower tick time, at 6% more total CPU across every thread. Against the cache being switched off rather than absent it bought about 3%, and those run ranges overlapped. The test arena was 200 mobs walking corridors without stopping, which is close to the worst case for it.

## Two settings that were off by accident

**Path reuse was never running.** `repathToleranceBlocks` shipped at 0 and 0 means the reuse never fires. It is 1 now. Upgrading migrates an existing config rather than leaving the fix for new installs, because a saved settings file wins over a default. Any value other than 0 is left alone, and the migration says what it did in the log.

**Automatic worker sizing could produce one worker.** A quarter of your CPU threads is a single worker on a four-thread machine, and one worker serialises every search behind the one in front. The floor is two.

## Compatibility

`compatibilityTier=AUDITED` did nothing at all on 26.2 before this release, because every audit was pinned to 26.1.2 builds. Four are re-derived and five of the seven now verify there.

**Lithium and Diagonal Blocks do not.** On 26.2 they are different builds from the ones the audits were derived from. Lithium alone puts all six movement families back on the server thread, and most performance packs ship Lithium, so treat the checked tier as unusable on 26.2 for now. The shipped default is unaffected, and the world-start report names the mods responsible.

## Also

`/pathweaver status` now reports what route sharing is finding. Startup is quieter on a healthy install: the scan used to warn about movement families it then waived a moment later, which was never a real warning.
