"""Break the benchmark down on the axes the aggregate hides.

The per-family table answers "which evaluator", which is only one axis. Two others matter more for
deciding whether a cost is real:

  ROUTE  - brain (MoveToTargetSink, what brainSinkAsync gates) against everything else. The mod's
           0.8.0 feature only touches the brain route, so a cost that appears off it is not the
           feature's.
  PHASE  - the A* search proper (PathFinder.findPath and everything under it) against PathWeaver's
           own scaffolding (dispatch, hand-off, install). A* is roughly conserved work: it has to
           happen wherever it runs. Scaffolding is the price of moving it. If total CPU rises and
           the rise is all scaffolding, the cost is explained. If A* itself grew, something else is
           going on and the number needs a different explanation.

Markers are taken from frames observed in the profiles, not guessed, and any marker that never fires
is printed as such rather than dropped - a real zero and a broken marker look identical otherwise.

Usage: python bench/axes_report.py off sync async
"""
import statistics
import os
import sys
from pathlib import Path

# The generated spark protobuf bindings live outside this repository. Point SPARK_PROTO_PY
# at them; the default is only a convenience for the machine they were generated on.
sys.path.insert(0, os.environ.get(
    "SPARK_PROTO_PY",
    os.path.expanduser("~/AppData/Roaming/.minecraft-dev/benchmarks/spark-proto-py")))
from spark import spark_sampler_pb2  # noqa: E402

ASTAR = ("PathFinder.findPath",)
SCAFFOLD = ("pathweaver", "PathWorkerPool")
BRAIN = ("MoveToTargetSink",)
BRAIN_TICK = ("Brain.tick", "Brain.startEachNonRunningBehavior", "Brain.tickEachRunningBehavior")


def label(n):
    return f"{n.class_name}.{n.method_name}"


def read(path):
    data = spark_sampler_pb2.SamplerData()
    data.ParseFromString(Path(path).read_bytes())
    acc = {"astar": 0.0, "scaffold": 0.0, "brain_route": 0.0, "brain_tick": 0.0,
           "server_astar": 0.0, "worker_astar": 0.0, "ticks": data.metadata.number_of_ticks}
    for thread in data.threads:
        pool = list(thread.children)
        is_server = thread.name == "Server thread"

        def walk(i, enclosing):
            # EXCLUSIVE self-time, attributed to the INNERMOST enclosing marker.
            #
            # The first version credited a marker with its whole subtree, so buckets contained each
            # other: in the disabled arm every createPath still enters the mixin wrapper, and the
            # A* underneath it was counted as PathWeaver scaffolding. It reported 2031 ms of
            # scaffolding for a mod that was switched off. Self-time cannot overlap.
            n = pool[i]
            name = label(n)
            here = enclosing
            if any(m in name for m in ASTAR):
                here = "astar"
            elif any(m in name for m in SCAFFOLD):
                here = "scaffold"
            child_total = 0.0
            for c in n.children_refs:
                child_total += sum(pool[c].times)
                walk(c, here)
            self_t = sum(n.times) - child_total
            if here == "astar":
                acc["astar"] += self_t
                acc["server_astar" if is_server else "worker_astar"] += self_t
            elif here == "scaffold":
                acc["scaffold"] += self_t
            if any(m in name for m in BRAIN):
                acc["brain_route"] += sum(n.times)
            if any(m in name for m in BRAIN_TICK):
                acc["brain_tick"] += sum(n.times)

        for i in thread.children_refs:
            walk(i, None)
    return acc


arms = {}
for arm in sys.argv[1:]:
    runs = [read(f"bench/deep/{arm}-{r}.sparkprofile") for r in (1, 2, 3)]
    arms[arm] = {k: statistics.mean(r[k] for r in runs) for k in runs[0]}

print("PHASE: is the extra CPU the search itself, or the scaffolding around it?  (ms, mean of 3)\n")
print(f"{'':<26}" + "".join(f"{a:>12}" for a in arms))
for k, lbl in (("astar", "A* search (findPath)"), ("server_astar", "  ...on server thread"),
               ("worker_astar", "  ...on workers"), ("scaffold", "PathWeaver scaffolding")):
    print(f"{lbl:<26}" + "".join(f"{arms[a][k]:>12.0f}" for a in arms))

print("\nROUTE: brainSinkAsync only gates the brain route. Anything off it is not this feature.\n")
print(f"{'':<26}" + "".join(f"{a:>12}" for a in arms))
for k, lbl in (("brain_route", "brain route (MoveToTargetSink)"), ("brain_tick", "Brain.tick total")):
    row = "".join(f"{arms[a][k]:>12.0f}" for a in arms)
    fired = any(arms[a][k] > 0 for a in arms)
    print(f"{lbl:<26}{row}" + ("" if fired else "   <-- MARKER NEVER FIRED"))

if "off" in arms and "async" in arms:
    base, new = arms["off"], arms["async"]
    d_astar = new["astar"] - base["astar"]
    d_scaf = new["scaffold"] - base["scaffold"]
    print(f"\nasync vs off:  A* {d_astar:+.0f} ms   scaffolding {d_scaf:+.0f} ms")
    print("  If the A* delta is near zero, the search work is conserved and the extra CPU is the")
    print("  price of moving it. If A* itself grew, the cost needs a different explanation.")
