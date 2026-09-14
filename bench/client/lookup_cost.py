#!/usr/bin/env python3
"""EXPLORATORY, not preregistered: the per-node lookup cost in synchronous search, in both the forms the
sampler reports it.

    python bench/client/lookup_cost.py <run folder> [...]

The ThreadLocal read PathWeaver put in the land path-type lookup shows up either as ThreadLocalMap
frames, when the JIT did not inline it, or as self time in the frames it was inlined into: Fabric's
LandPathTypeRegistry.getPathTypeProvider and its PathfindingContext onGetNodeType handler. The
preregistered T1 counted only the first form. This sums self time of all of them inside synchronous
findPath, as a share of that search.
"""
import importlib.util
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("frame_totals", HERE / "frame_totals.py")
ft = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ft)
from spark import spark_sampler_pb2  # noqa: E402

FORMS = {
    "threadlocal": lambda l: l.startswith("ThreadLocal$ThreadLocalMap."),
    "land lookup": lambda l: l == "LandPathTypeRegistry.getPathTypeProvider",
    "fabric node-type hook": lambda l: l.startswith("PathfindingContext.handler$") and "onGetNodeType" in l,
}


def measure(folder):
    """Synchronous findPath time on the server thread, and the self time of each lookup form inside it."""
    data = spark_sampler_pb2.SamplerData()
    data.ParseFromString((Path(folder) / "profile.sparkprofile").read_bytes())
    th = [t for t in data.threads if t.name == ft.SERVER_THREAD][0]
    pool = list(th.children)
    sync = 0.0
    forms = {k: 0.0 for k in FORMS}
    stack = [(r, False) for r in th.children_refs]
    while stack:
        i, under = stack.pop()
        n = pool[i]
        t = sum(n.times)
        is_find = ft.matches(n, "PathFinder.findPath")
        if is_find and not under:
            sync += t
        now = under or is_find
        if now:
            label = "%s.%s" % (n.class_name.rsplit(".", 1)[-1], n.method_name)
            self_t = max(0.0, t - sum(sum(pool[c].times) for c in n.children_refs))
            for k, pred in FORMS.items():
                if pred(label):
                    forms[k] += self_t
        for c in n.children_refs:
            stack.append((c, now))
    return sync, forms


def main(argv):
    for folder in argv[1:]:
        sync, forms = measure(folder)
        total = sum(forms.values())
        print("%-14s sync %6.0f ms | lookup cost %5.0f ms = %4.1f%% (threadlocal %5.0f, land lookup %5.0f, fabric hook %5.0f)" % (
            Path(folder).name, sync, total, 100 * total / sync if sync else 0, forms["threadlocal"],
            forms["land lookup"], forms["fabric node-type hook"]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
