# Is pathfinding actually your bottleneck?

Ten minutes, no restart, no test world. Answer this before installing PathWeaver, because the mod
only helps a server whose tick time is going into mob path searches, and plenty of struggling servers
are slow for some other reason entirely.

PathWeaver does not make pathfinding cheaper. It moves the same A* work to another thread and adds a
little of its own on the way. If searches are not what is eating your tick, that trade is a small
loss, not a small win.

## 1. Sample the server thread while it is actually busy

Install [spark](https://modrinth.com/mod/spark) and profile during your worst hour: peak players,
farms running, mobs loaded. Five minutes is enough.

```
/spark profiler start --timeout 300
```

It stops on its own and gives you a link. `/spark profiler --help` lists the flags your version
takes. Profiling an empty server at 3am tells you nothing; the whole point is to catch the tick that
hurts.

## 2. Find the pathfinding cost

In the viewer, open the server thread and look for these, in the call tree under `tickServer`:

- `PathFinder.findPath`
- `PathNavigation.createPath` / `moveTo`
- `WalkNodeEvaluator.getNeighbors` and friends

Add up their share. Their percentage is the number that decides this.

## 3. Read it against `tickServer`, not against the whole thread

Two things will mislead you here, and they pull in opposite directions.

**Spark samples stacks, it does not measure CPU.** It records which method each thread was in when it
looked, on a fixed interval. A thread that is blocked or waiting still counts as occupying whatever
it is stopped in.

**Most of the sampled thread is usually idle.** On the modpack profiled in
[the README](../README.md#profiled-on-a-real-modpack-with-spark), the server thread was 67-74% idle
at that load, so a share taken against the whole sample is roughly four times smaller than the share
of real work. That is the trap: 12% of the thread sounds ignorable and was not.

So take the percentage **inside the `tickServer` node**, where the denominator is work rather than
waiting. On that pack, pathfinding was:

| | Share of `tickServer` | Share of the whole sampled thread |
|---|---|---|
| PathWeaver off | **40.3%** | 12.38% |
| PathWeaver on | **19.8%** | 4.92% |

Tick time went from 15.47 ms to 12.52 ms, about 19% off the tick. Both arms held 20 TPS, so what that
pack bought was headroom, not throughput.

One more rule, whichever number you use: two profiles are only comparable if the server was doing the
same thing in both. A quiet run and a busy run give different percentages from identical code.

## 4. Decide

The bands are judgement. The measured points are the 40.3% and 19.8% above; where to draw a line
between them is my opinion and you may put it elsewhere.

| Pathfinding share of `tickServer` | What it means |
|---|---|
| Under 5% | Not your problem. PathWeaver would move almost nothing and still cost you the overhead. Do not install it. |
| 5-15% | Marginal. Worth trying if what you see is tick spikes rather than a low average, because spikes are what this mod cuts. |
| Over 15% | Pathfinding is a real cost on your server. The pack this mod was built for sat at 40%. |

One condition on top of the number: PathWeaver needs a spare core. On two cores or fewer it tells you
at world start to leave it off, and on four or fewer it warns the benefit will be small. Moving work
to a thread with nowhere to run is not moving it.

## If it is not pathfinding

Then say so and look at what the profile actually shows. Common answers that are not pathfinding, and
that this mod will not touch:

- **Chunk generation and loading.** Usually the biggest single cost on a young or heavily explored
  world. Pre-generate.
- **Entity ticking and collision**, as distinct from pathfinding. Large mob farms and item floods
  land here.
- **Block entities.** Hopper chains and big storage or automation setups.
- **One mod.** A single addon doing something expensive every tick shows up plainly in the call tree,
  and removing it beats optimising around it.

PathWeaver moves path searches and nothing else. It does not move entity ticking, collision, AI goals
or chunk work.

## After installing, check that it was right

`/pathweaver status` separates what actually happened from what could have:

- The dispatch rows say how many searches went to a worker and how many were installed. Dispatched
  but never installed is work that was paid for and thrown away.
- The route cache prints **searches skipped** (searches that did not run) separately from **hits that
  saved nothing** (real cache hits that could not be spent). Only the first is a saving. Before
  0.9.0 they were added together under the first label, which overstated it.

Then profile again, the same way, at the same time of day, under the same load. One profile before
and one after is the only comparison worth making, and it is only worth making if the two runs were
doing the same thing.
