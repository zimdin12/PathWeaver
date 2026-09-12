#!/usr/bin/env python3
"""Why did LOD remove more recompute time than the refresh floor allows? Split the time and see.

    python bench/lod_split.py bench/lod/r1-A1.sparkprofile bench/lod/r1-A2.sparkprofile ...

Written after P3 of the LOD campaign failed low (A2/A1 of 0.37 and 0.29 against a 0.45 floor), to
tell apart three explanations that predict different splits:

  1. OVERHEAD. recomputePath time is searches plus per-call bookkeeping, and LOD cancels calls at
     HEAD, removing their bookkeeping too. Predicts: search-inside ratio near the floor's 0.525,
     overhead-inside ratio far lower.
  2. PATH LIFETIME. The 0.525 assumed routes that live forever. A wander route that ends between
     tick 21 and tick 40 after its last refresh is refreshed by vanilla and never by LOD. Predicts:
     search-inside ratio ALSO below 0.525, with the moveTo-driven search outside recomputePath
     unchanged.
  3. A DEFECT, where cancelling leaves navigations broken and routes die. Predicts: search outside
     recomputePath RISES with LOD on, as mobs that lost their route ask for a new one.

The explanations are not exclusive, and a split cannot prove 2 over an unmeasured cause; it can
rule 1 and 3 in or out. Server thread only: the A arms run every search there.
"""

import importlib.util
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("frame_totals", HERE / "frame_totals.py")
ft = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ft)
from spark import spark_sampler_pb2  # noqa: E402

RECOMPUTE = "PathNavigation.recomputePath"
FIND = "PathFinder.findPath"


def split(path):
    data = spark_sampler_pb2.SamplerData()
    data.ParseFromString(Path(path).read_bytes())
    thread = [t for t in data.threads if t.name == ft.SERVER_THREAD][0]
    pool = list(thread.children)
    out = {"recompute": 0.0, "find_inside": 0.0, "find_outside": 0.0, "tick": 0.0}
    # (node, inside recomputePath already, inside findPath already, inside tick already)
    stack = [(r, False, False, False) for r in thread.children_refs]
    while stack:
        i, in_rec, in_find, in_tick = stack.pop()
        node = pool[i]
        t = sum(node.times)
        is_rec = ft.matches(node, RECOMPUTE)
        is_find = ft.matches(node, FIND)
        is_tick = ft.matches(node, ft.TICK)
        if is_tick and not in_tick:
            out["tick"] += t
        if is_rec and not in_rec:
            out["recompute"] += t
        if is_find and not in_find:
            out["find_inside" if in_rec else "find_outside"] += t
        for c in node.children_refs:
            stack.append((c, in_rec or is_rec, in_find or is_find, in_tick or is_tick))
    out["overhead_inside"] = out["recompute"] - out["find_inside"]
    return out


def main(argv):
    rows = []
    for p in argv[1:]:
        s = split(p)
        rows.append((Path(p).stem, s))
        print("%-14s tick %6.0f  recompute %6.0f = search %6.0f + overhead %5.0f   search outside %6.0f" % (
            Path(p).stem, s["tick"], s["recompute"], s["find_inside"], s["overhead_inside"], s["find_outside"]))
    by = dict(rows)
    for r in ("r1", "r2"):
        a1, a2 = by.get(r + "-A1"), by.get(r + "-A2")
        if not (a1 and a2):
            continue

        def ratio(key):
            return (a2[key] / a1[key]) if a1[key] else float("nan")
        print("%s  A2/A1  recompute %.3f | search inside %.3f | overhead inside %.3f | search outside %.3f" % (
            r, ratio("recompute"), ratio("find_inside"), ratio("overhead_inside"), ratio("find_outside")))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
