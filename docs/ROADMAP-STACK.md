# Where this goes next

Written 2026-09-12, after the first measurements taken under load
(`docs/PERFORMANCE-2026-09.md`) and a survey of what already exists. Every "we could build X" below is
checked against whether someone already built X, because the answer was yes more often than not.

## The thesis, stated so it can be argued with

Taking a fixed cost off the server thread is worth more than the percentage suggests, because tick
time returned is budget that can be spent. PathWeaver's 6% is not interesting as 6%; it is interesting
because path searches no longer have to be cheap, which is what makes more expensive pathfinding, or
more mobs, affordable later.

A set of mods that each remove one cost this way compounds. The discipline that makes it credible is
that each one publishes what it is worth and how that was measured, including when the answer is
small.

## What already exists, checked

| mod | does | versions | state | verdict for us |
|---|---|---|---|---|
| [Pathwright](https://modrinth.com/mod/pathwright) | async path updates, path cache, distance LOD | 26.1.x, Fabric + NeoForge | 665 downloads, updated ~4 months ago | **direct competitor** |
| [Bellows](https://modrinth.com/mod/smithed-bellows) | caches `@e` selectors by type and tag, trims NBT reads | 26.2 | active, updated days ago | **taken, do not build** |
| [Mobtimizations](https://modrinth.com/mod/mobtimizations) | throttles target scans and hazard scans | 1.18.2 – 1.20.1 only | stale on modern versions | **gap** |
| [Flowing Fluids](https://modrinth.com/mod/flowing-fluids) | finite water, evaporation, rain refill, pressure | 1.20 – 26.2, all loaders | 208K downloads, "early development" | feature taken, **performance not** |
| [LazyFluids](https://modrinth.com/mod/lazyfluids) | stops fluids ticking away from players | — | — | blunt instrument, distance cutoff |
| Lithium | broad, includes collision and AI | current | the baseline everyone runs | assume it is already there |

### The uncomfortable one: Pathwright

It does what PathWeaver does, plus distance-based LOD that we do not have, on Fabric **and NeoForge**
where we are Fabric only. It has more downloads than we do.

What it does not have: any published benchmark, any stated methodology, or any thread-safety
documentation. Its claims are "+1-3 TPS" with nothing behind them.

So our defensible position is not "we are faster", which we have not shown. It is that we say what we
are worth, show the profile it came from, publish the harness, and gate ourselves on seven audits that
refuse to run when the mods we were proved against are not the mods present. That is a real difference
and it is the one to lead with.

Two things looked worth doing regardless. **Distance LOD** was a real feature gap and shipped in
0.9.0, off by default because it changes behaviour. **NeoForge** was a distribution gap, and it was
measured and dropped; the reasoning is in `NEOFORGE-0.9.0.md`.

## Decision: what goes in 0.9.0

**Two of the three things a competitor was ahead on, and not the third.** This section originally read
"nothing new", on the argument that a correctness release should stay a correctness release. Steven
overruled it after the competitor comparison, and he was right to: two of those gaps are cheap and
neither touches what paths mobs take at the shipped defaults.

In: **distance LOD**, off by default, and **Cloth Config demoted to optional** so the mod needs Fabric
API alone. LOD ships off precisely so the sentence below stays true for anyone who does not opt in.

Out: **NeoForge**, abandoned with the measurement written down in `NEOFORGE-0.9.0.md`. It is not
blocked on the loader, which exists for both targets. It is blocked on the compatibility gate being a
1107-line reader of `fabric.mod.json` and on nine Fabric GameTest harnesses being where every published
number comes from. A NeoForge build needs a second gate, not a port.

Also out: **goal-selector work**, which is what this section was originally written to refuse, and the
refusal still holds:

- **Diagnostic separability.** Throttling target scans changes when mobs notice a player. That
  produces reports that are working-as-intended. Shipping it alongside a stale-route correctness fix
  means that when a report arrives, nobody can tell which change caused it.
- **The fix is live-affecting and waiting.** 0.8.0 is published now and carries the route-sharing bug.
  Every day 0.9.0 waits for a feature is a day that bug is live on other people's servers.
- **The changelog would become false.** It states "nothing here changes what paths your mobs take."
  AI throttling breaks that sentence, and that sentence is load-bearing for anyone deciding whether
  this is a safe update.
- **It deserves better than a footnote.** Mobtimizations is dead above 1.20.1, so a modern
  target-scan optimisation is a headline, not a bullet in a bugfix release.

That last argument applied to LOD as well, and it was paid rather than waived: adding production code
invalidated the evidence, so all of it was re-run and the new code carries its own witnesses. What was
23 of 23 witnessed and 476 tests is now 32 of 32 and 508.

It was also paid twice. A review of the finished feature found that LOD had been built on a mechanism
vanilla does not have, and that its shipped interval sat under vanilla's own refresh floor where it
could remove nothing. The fix, the four witnesses that now cover it and the corrected copy are in
`CHANGELOG.md` under 0.9.0.

## 0.10.0 — the AI scheduling release

The measured gap. `GoalSelector.tick` is 15.6% of tick; inside it `canUse` is 5.6% and the concrete
waste is every hostile mob running its own `getEntitiesOfClass` target scan every tick, thousands of
overlapping spatial queries computing nearly the same answer.

Two builds, and the easy one is the wrong one:

- **Stagger** — scan every N ticks, offset by entity id. Trivial to write. Mobs notice you up to N
  ticks later; at N=4 that is 200 ms of reaction time, perceptible to someone looking for it. This is
  what Mobtimizations did.
- **Share** — one spatial query per section per tick, reused by every mob asking a compatible
  question. No behaviour change if the returned set is identical, which is the hard part, because
  target conditions differ per mob: follow range, line of sight, invisibility, team.

Share is the one worth building, because "no behaviour change" is the claim this project can make and
others do not. Expected 1 to 3% of tick. Distance LOD was also scoped here and was pulled forward into
0.9.0 instead.

## The one after that: fluids, and why it is the interesting one

This is the piece that serves the physics work rather than only shaving a percentage.

**The feature is taken.** Flowing Fluids already does finite water, evaporation and rain refill, has
208K downloads and is on 26.2 across four loaders. Building a competing finite-water mod means
fighting a popular incumbent on its own ground, and we would lose.

**The performance is not taken.** Flowing Fluids is "early development", acknowledges that large flows
lag, publishes no numbers, and the existing performance answer in this space is LazyFluids simply not
ticking fluids away from players. Nobody has made fluid simulation cheap; they have made it optional.

That is exactly PathWeaver's shape applied to a second domain. Make the simulation cheap enough that
finite water does not need a distance cutoff to be affordable, and the feature everyone wants stops
being a performance decision. The end state is either a substrate other fluid mods sit on, or our own
feature layer once the cost is under control.

**It is also the honest answer to "optimise something now so a feature is possible later."** Material
interactions, rain-fed water and evaporation are all more fluid updates, more block-state writes and
more neighbour queries. Every one of them is unaffordable at the current per-update cost and becomes
affordable if that cost falls.

**Measured 2026-09-12, and the answer is no.** `bench/fluid-ladder.sh`, a verified stone basin with
water released into it and the tick sampled while it spreads:

| water blocks | median | 95th | worst tick |
|---:|---:|---:|---:|
| 768 | 5.4 ms | 9.9 | 14.5 |
| 3,072 | 3.3 ms | 6.1 | 12.1 |
| 6,912 | 3.7 ms | 7.5 | 22.6 |
| 12,288 | 3.5 ms | 7.9 | 36.6 |

Median tick does not move. Vanilla fluid spreading produces occasional spikes, worst single tick
36.6 ms against a 50 ms budget, and no sustained cost at all. Against 5000 zombies at 77 ms median,
there is nothing here worth a mod.

**So the fluid substrate idea is dead in the form it was proposed**, and it is recorded rather than
quietly dropped, because the whole point of gating it on a measurement was to be willing to lose.

Two honest qualifications, neither of which revives it:

- This measures VANILLA fluids, which settle and then stop ticking. A finite-fluid mod changes that
  rule and its cost would live inside its own simulation, not in vanilla's. So the work would be
  *writing a better finite-water mod*, not optimising the game underneath one, and that means
  competing with Flowing Fluids' 208K downloads on features rather than on performance.
- The first attempt at this measurement was invalid and is worth remembering: the arena fills were
  40,401 blocks against vanilla's 32,768 limit, so they failed silently, no basin was built, the water
  fell on natural terrain, and the run still printed a tidy table describing an arena that did not
  exist. The script now verifies the floor is there before it measures anything.

## Ranking, and what is deliberately not on it

1. **Measure fluid cost.** One afternoon. Decides whether item 3 exists at all.
2. **0.10.0, shared target scans.** Known gap, known size, 1-3%. LOD shipped in 0.9.0; NeoForge is
   off the list entirely, see `NEOFORGE-0.9.0.md`.
3. **Fluid substrate**, if and only if step 1 says the cost is there.

Not on the list, with reasons:

- **Datapack selector indexing.** Bellows does it, on 26.2, updated this week. It was the best
  prize-to-risk item in the profile and someone competent already owns it. Install it instead.
- **Entity collision**, 17.5% and the largest item on the board. It already runs entirely through
  Lithium. Beating the ecosystem's best-known optimisation mod on its own ground is a different
  proposition from beating unoptimised vanilla, and we should not pretend otherwise.
- **More pathfinding work.** About 1.5 points of tick remain after what PathWeaver already takes. This
  direction is close to finished, and that is a good outcome, not a sad one.

## Naming

`PathWeaver` is the anchor. No mod on Modrinth currently uses FlowWeaver, MindWeaver, TickWeaver or
MobWeaver; WorldWeaver exists and is unrelated, so the family reads as ours without colliding.

- AI scheduling: **MindWeaver**
- Fluids: **FlowWeaver**

Shared icon language and a shared line on each page saying which stack it belongs to, so someone who
installed one recognises the next. The family promise is the discipline, not the prefix: each one says
what it is worth, shows the profile, and ships the harness that produced it.
