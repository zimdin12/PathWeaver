#!/usr/bin/env python3
"""PathWeaver frames INSIDE synchronous PathFinder.findPath on the server thread, by self time.

    python bench/client/inside_sync.py <run folder> [...]

A mixin in the A* inner loop costs every search, including the ones PathWeaver leaves synchronous.
drill.py stops at findPath, so it cannot see this; this looks only below it.
"""
import collections
import importlib.util
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("frame_totals", HERE / "frame_totals.py")
ft = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ft)
from spark import spark_sampler_pb2  # noqa: E402


def main(argv):
    for folder in argv[1:]:
        data = spark_sampler_pb2.SamplerData()
        data.ParseFromString((Path(folder) / "profile.sparkprofile").read_bytes())
        th = [t for t in data.threads if t.name == ft.SERVER_THREAD][0]
        pool = list(th.children)
        inside, selfs = 0.0, collections.Counter()
        methods = collections.Counter()
        stack = [(r, False) for r in th.children_refs]
        while stack:
            i, under = stack.pop()
            n = pool[i]
            t = sum(n.times)
            is_find = ft.matches(n, "PathFinder.findPath")
            if is_find and not under:
                inside += t
            now = under or is_find
            if now:
                child = sum(sum(pool[c].times) for c in n.children_refs)
                label = "%s.%s" % (n.class_name.rsplit(".", 1)[-1], n.method_name)
                methods[label] += max(0.0, t - child)
                if n.class_name.startswith("dev.pathweaver") or "pathweaver$" in n.method_name:
                    selfs[label] += max(0.0, t - child)
            for c in n.children_refs:
                stack.append((c, now))
        print("== %s  sync findPath %.0f ms, PathWeaver self time inside it %.0f ms" % (
            Path(folder).name, inside, sum(selfs.values())))
        for k, v in selfs.most_common(6):
            print("   pw   %6.0f  %s" % (v, k))
        for k, v in methods.most_common(8):
            print("   self %6.0f  %s" % (v, k))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
