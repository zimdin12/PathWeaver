PathWeaver had two modes: one that did nothing, and one that checked nothing.

The checked tier, `AUDITED`, asks *"did any mod touch this class?"* On a real modpack the answer is
always yes — 20 mods on the reference pack claim a watched pathfinding target — so it has left **0 of
187 mob types eligible since 0.3.0**, across five releases, and the shipped default is `UNSAFE`, which
performs no check at all. That tier is now **frozen**: it stays as a conservative escape hatch, and it
is no longer the answer. The reasoning, and the four independent ways the one attempt to fix it was
wrong, are in [ROADMAP.md](https://github.com/zimdin12/PathWeaver/blob/master/ROADMAP.md).

This release replaces prediction with detection.

### Added

- **A worker failure breaker.** A search that throws on a worker is counted against the movement
  family whose code ran. Past a windowed threshold that family stops dispatching for the rest of the
  session, and every mob in it paths on the server thread exactly as it would without this mod
  installed. Falling back to vanilla is the safe direction by construction, so the breaker cannot
  destabilise anything it fires on.
- **Failure attribution, which is the half that pays on every install.** The breaker may never fire —
  the reference pack ran 731 dispatches with zero search failures. The attribution fires on the
  *first* failure, needs no threshold, and turns a single rate-limited `WARN` into a block naming the
  family, the exception and, where it can, the mod. The honest limit is stated in the log itself: a
  mixin handler is merged into the class it targets, so an `@Overwrite` or an inlined `@Redirect`
  leaves nothing to attribute, and the block says so rather than printing an empty list.
- **Two settings**, `workerFailureLimit` (default 3) and `workerFailureWindowTicks` (default 1200,
  one minute). `workerFailureLimit=0` keeps dispatching no matter what and still counts and logs.
  **No `configVersion` bump**: the serializer throws on any version but 2, 1 or 0 and a load failure
  installs fail-closed defaults, so bumping it would have switched the mod off for every existing
  install. Both fields default in on an untouched config, verified on the real pack.
- **`/pathweaver status` now shows failures before they trip anything**, because the log block told
  operators the running total was there and it was not.

### Why the threshold is windowed

A counter that never decays converges on a certain trip given enough uptime, and this project's own
Lithium audit describes a concurrent-resize exception as an expected, contained event on the most
widely installed performance mod there is. Three of those over a fortnight is not an incompatibility;
three in a minute is. **A false trip is not a free no-op** — being vanilla is the thing the user
installed this mod to stop — so the window is what makes the mechanism affordable. A cumulative
backstop still catches a genuine slow leak.

### What this is not

A breaker sees **throws**. The corruption a user actually fears comes from a silent torn read that
returns the wrong block and never throws, and nothing here catches that. It is a smoke detector: it
does not stop the fire starting, it stops the house burning down and tells you which room it started
in.

### Fixed, found by reviewing the running code

- **A `workerFailureLimit` above 25 was silently capped.** The cumulative backstop was an
  unconditional `||` rather than a floor, so a setting that accepts up to 1000 tripped at 25 anyway,
  with nothing in the tooltip, the status line or the log admitting it.
- **The trip block named the threshold that had not fired.** A backstop trip printed the *window*
  rule, and the cumulative count underneath it, sending an operator to raise a setting that changes
  nothing.
- **A `VirtualMachineError` counted as evidence of an unsafe read.** On a 200-mod server an
  `OutOfMemoryError` is the likeliest throwable a worker will ever produce; three would have switched
  five families off and blamed whichever mods were on the frame.
- **A trip could outlive its server.** `shutdownNow()` does not wait, so a straggler worker past the
  threshold check installed its verdict into the *next* world — an inert movement family in a fresh
  world with no log line, because the one-shot report had already fired in the world before.
- **A cyclic cause chain spun a worker forever** during attribution.
- The two new config keys skipped the type validation every other integer key gets.

### Also

- `/pathweaver status` and `/pathweaver mobs` were `private static void (CommandSourceStack)` with no
  seam, so eight mutations inside them were invisible to every test. They now produce a `List<String>`
  the tests assert against.

358 unit tests (zero skipped), three game tests plus a dedicated end-to-end breaker harness, four
server harnesses, the client harness, and verification on the real 221-jar server pack: 184 of 187
eligible, 731 dispatched / 724 installed / 7 discarded, all six evaluator families node-for-node
identical to a synchronous oracle, and **no breaker output at all** — which is the correct result on a
healthy pack and the honest measure of this feature. Benchmarked against published 0.6.0 over ten
interleaved runs: mean tick 50.001 ms vs 50.004 ms, p99 150.9 ms vs 150.5 ms. No regression.

Twenty-two mutations were compiled and observed to fail during development. Eight of them survived
their first defence, including both log blocks — the entire user-visible output of this feature was
deletable without a test noticing.
