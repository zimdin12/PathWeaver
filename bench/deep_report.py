"""Compare deep-bench arms: per thread, per evaluator family, and what regressed.

Answers three questions the aggregate number cannot:
  - WHERE the work went (server thread against workers, per family)
  - WHAT it cost (total CPU across all threads, not just the one that looks better)
  - WHAT REGRESSED (any family or thread that got worse, named)

Usage: python bench/deep_report.py off=bench/deep/off.sparkprofile sync=... async=...
"""
import os
import sys
from pathlib import Path

# The generated spark protobuf bindings live outside this repository. Point SPARK_PROTO_PY
# at them; the default is only a convenience for the machine they were generated on.
sys.path.insert(0, os.environ.get(
    "SPARK_PROTO_PY",
    os.path.expanduser("~/AppData/Roaming/.minecraft-dev/benchmarks/spark-proto-py")))
from spark import spark_sampler_pb2  # noqa: E402

# One entry per evaluator family the mod claims to touch, so a regression can be attributed rather
# than just observed. Derived from the mod's own family list; keep them in step.
FAMILIES = {
    "walk": ("WalkNodeEvaluator",),
    "swim": ("SwimNodeEvaluator",),
    "fly": ("FlyNodeEvaluator",),
    "amphibious": ("AmphibiousNodeEvaluator",),
    "frog": ("FrogNodeEvaluator",),
    "climber": ("WallClimberNavigation",),
}
PATHFINDING = ("net.minecraft.world.level.pathfinder", "net.minecraft.world.entity.ai.navigation",
               "PathNavigation", "PathNavigator", "NodeEvaluator", "PathFinder", "PathfindingContext")
PATHWEAVER = ("dev.pathweaver", "pathweaver$")
TICK = "net.minecraft.server.MinecraftServer.tickServer"


def label(n):
    return f"{n.class_name}.{n.method_name}"


def read(path):
    data = spark_sampler_pb2.SamplerData()
    data.ParseFromString(Path(path).read_bytes())
    out = {"ticks": data.metadata.number_of_ticks, "threads": {}, "families": {}, "total_path": 0.0}
    for thread in data.threads:
        pool = list(thread.children)
        total = sum(thread.times)
        if total <= 0:
            continue

        def walk(i, in_path, in_pw, in_family):
            node = pool[i]
            name = label(node)
            t = sum(node.times)
            is_path = in_path or any(m in name for m in PATHFINDING)
            is_pw = in_pw or any(m in name for m in PATHWEAVER)
            fam = in_family
            if fam is None:
                for f, markers in FAMILIES.items():
                    if any(m in name for m in markers):
                        fam = f
                        out["families"][f] = out["families"].get(f, 0.0) + t
                        break
            res = [t if (is_path and not in_path) else 0.0,
                   t if (is_pw and not in_pw) else 0.0]
            for c in node.children_refs:
                sub = walk(c, is_path, is_pw, fam)
                if not is_path:
                    res[0] += sub[0]
                if not is_pw:
                    res[1] += sub[1]
            return res

        path_ms = pw_ms = 0.0
        for i in thread.children_refs:
            a, b = walk(i, False, False, None)
            path_ms += a
            pw_ms += b
        tick_ms = sum(sum(n.times) for n in pool if label(n) == TICK)
        out["threads"][thread.name] = (total, path_ms, pw_ms, tick_ms)
        out["total_path"] += path_ms
    return out


def server(d):
    for name, v in d["threads"].items():
        if name == "Server thread":
            return v
    return (0, 0, 0, 0)


arms = {}
for arg in sys.argv[1:]:
    tag, _, p = arg.partition("=")
    arms[tag] = read(p)

print(f"{'arm':<8}{'srv path':>10}{'worker path':>13}{'TOTAL path':>12}"
      f"{'tickServer':>12}{'MSPT':>8}{'pathweaver':>12}")
for tag, d in arms.items():
    total, path_ms, pw_ms, tick_ms = server(d)
    worker = d["total_path"] - path_ms
    mspt = tick_ms / d["ticks"] if d["ticks"] else float("nan")
    print(f"{tag:<8}{path_ms:>10.0f}{worker:>13.0f}{d['total_path']:>12.0f}"
          f"{tick_ms:>12.0f}{mspt:>8.2f}{pw_ms:>12.0f}")

print("\nper family, total across every thread (ms):")
# EVERY declared family is printed, including ones that matched nothing. The first version
# listed only families that appeared, so a marker that never fired was dropped from the table.
# Two were: climber and swim showed nothing across nine runs while 17 spiders and 2 squid were
# alive, and the result was presented as 'per family' with four rows. A real zero and a broken
# marker look identical unless the probe is made to say which it is.
fams = sorted(FAMILIES)
print(f"  {'family':<12}" + "".join(f"{t:>10}" for t in arms))
for f in fams:
    row = "".join(f"{arms[t]['families'].get(f, 0.0):>10.0f}" for t in arms)
    fired = any(arms[t]["families"].get(f, 0.0) > 0 for t in arms)
    note = "" if fired else "   <-- MARKER NEVER FIRED: absent or mismatched, unknown which"
    print(f"  {f:<12}{row}{note}")

if "off" in arms:
    print("\nagainst the mod being disabled (off):")
    base_total, base_path, _, base_tick = server(arms["off"])
    for tag, d in arms.items():
        if tag == "off":
            continue
        _, path_ms, _, tick_ms = server(d)
        dp = 100 * (path_ms - base_path) / base_path if base_path else float("nan")
        dt = 100 * (tick_ms - base_tick) / base_tick if base_tick else float("nan")
        cpu = 100 * (d["total_path"] - arms["off"]["total_path"]) / arms["off"]["total_path"] \
            if arms["off"]["total_path"] else float("nan")
        verdict = "REGRESSION" if dt > 5 else "ok"
        print(f"  {tag:<8} server-thread pathfinding {dp:+7.1f}%   tickServer {dt:+7.1f}%   "
              f"total pathfinding CPU {cpu:+7.1f}%   {verdict}")
