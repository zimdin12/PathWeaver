#!/usr/bin/env python3
"""Where the server tick actually goes, read from a local .sparkprofile.

bench/spark_summary.py answers one question: how much of the tick is pathfinding, and how much of
that PathWeaver moved off the server thread. Having answered it (about 6% and about three quarters),
the useful question becomes the other 94%, which that tool cannot see because it only looks for
frames it already named.

This walks the server thread's tree under tickServer and reports the heaviest subtrees, so the next
piece of work is chosen from a measurement rather than from a guess about what is slow.

    python bench/profile_costs.py <file.sparkprofile> [more.sparkprofile ...]

ATTRIBUTION. spark's call tree is FLATTENED: a ThreadNode holds a pool in `children` and the tree
shape in `children_refs`, which index that pool. Walking `children` directly double counts the whole
tree, so the roots are summed against the thread total and the numbers are withheld if they disagree.
A frame's subtree time is counted once, at the outermost occurrence; a recursive frame nested inside
itself is not added twice.
"""

import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, r"C:\Users\Administrator\AppData\Roaming\.minecraft-dev\benchmarks\spark-proto-py")
from spark import spark_sampler_pb2  # noqa: E402

# The dedicated server overrides tickServer, so the frame that carries the tick is
# DedicatedServer.tickServer. Matching only the MinecraftServer name found nothing and divided by
# zero, which printed percentages in the millions rather than admitting it had missed.
TICK_SUFFIX = ".tickServer"


def label(node):
    return "%s.%s" % (node.class_name, node.method_name)


def costs(path, thread_name="Server thread", depth=40):
    data = spark_sampler_pb2.SamplerData()
    data.ParseFromString(Path(path).read_bytes())
    for thread in data.threads:
        if thread.name != thread_name:
            continue
        pool = list(thread.children)
        thread_total = sum(thread.times)

        # The self-check that makes the rest trustworthy: roots must account for the thread.
        root_sum = sum(sum(pool[i].times) for i in thread.children_refs)
        if thread_total and abs(root_sum - thread_total) / thread_total > 0.02:
            raise SystemExit("roots sum to %.0f against a thread total of %.0f; the walk is wrong"
                             % (root_sum, thread_total))

        totals = defaultdict(float)
        seen_depth = {}

        def walk(i, d, ancestors):
            node = pool[i]
            name = label(node)
            t = sum(node.times)
            # Counted once, at the outermost occurrence. A frame under itself is already inside the
            # number recorded for its ancestor.
            if name not in ancestors:
                totals[name] += t
                seen_depth.setdefault(name, d)
            if d < depth:
                nxt = ancestors | {name}
                for c in node.children_refs:
                    walk(c, d + 1, nxt)

        for i in thread.children_refs:
            walk(i, 0, frozenset())

        tick = max((t for name, t in totals.items() if name.endswith(TICK_SUFFIX)), default=0.0)
        if not tick:
            raise SystemExit("no tickServer frame found; the denominator would be a guess")
        return thread_total, tick, totals, seen_depth
    return None, None, None, None


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    for path in argv[1:]:
        thread_total, tick, totals, depths = costs(path)
        if totals is None:
            print("%s: no server thread" % path)
            continue
        print("=" * 96)
        print("%s   server thread %.0f ms, tickServer %.0f ms" % (Path(path).name, thread_total, tick))
        print("  %-72s %9s %7s" % ("subtree", "ms", "of tick"))
        rows = [(name, t) for name, t in totals.items()
                if t / tick > 0.012 and not name.endswith(TICK_SUFFIX)
                and depths.get(name, 0) > 0 and t < tick * 0.999]
        for name, t in sorted(rows, key=lambda r: -r[1])[:34]:
            print("  %-72s %9.0f %6.1f%%" % (name[-72:], t, 100.0 * t / tick))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
