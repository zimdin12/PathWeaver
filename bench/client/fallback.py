#!/usr/bin/env python3
"""Synchronous searches that went through PathWeaver's gate and fell back to the server thread.

    python bench/client/fallback.py <run folder> [...]

A request PathWeaver declines still runs vanilla's createPath body, so its findPath sits below one of
PathWeaver's wrapped call sites (pathweaver$arm*) or the brain-sink wrap, with no PathWeaver handler
between them. Reported: that time, split by which wrapped site, against all synchronous search.
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

WRAPS = ("pathweaver$armEntityMove", "pathweaver$armCoordinateMove", "pathweaver$armCoordinateMoveWithReach",
         "pathweaver$useAlreadyComputedPath", "pathweaver$recompute")


def main(argv):
    for folder in argv[1:]:
        data = spark_sampler_pb2.SamplerData()
        data.ParseFromString((Path(folder) / "profile.sparkprofile").read_bytes())
        th = [t for t in data.threads if t.name == ft.SERVER_THREAD][0]
        pool = list(th.children)
        total = 0.0
        by_wrap = collections.Counter()
        stack = [(r, None, False) for r in th.children_refs]
        while stack:
            i, wrap, in_find = stack.pop()
            n = pool[i]
            t = sum(n.times)
            hit = next((w for w in WRAPS if w in n.method_name), None)
            now_wrap = hit or wrap
            is_find = ft.matches(n, "PathFinder.findPath")
            if is_find and not in_find:
                total += t
                if now_wrap:
                    by_wrap[now_wrap] += t
            for c in n.children_refs:
                stack.append((c, now_wrap, in_find or is_find))
        fell = sum(by_wrap.values())
        print("== %s  synchronous findPath %.0f ms, of which through PathWeaver's gate %.0f ms (%.1f%%)" % (
            Path(folder).name, total, fell, 100 * fell / total if total else 0))
        for k, v in by_wrap.most_common():
            print("   %7.0f ms  %s" % (v, k))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
