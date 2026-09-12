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

import os
import sys
from collections import defaultdict
from pathlib import Path

# The generated spark protobuf bindings live outside this repository. Point SPARK_PROTO_PY
# at them; the default is only a convenience for the machine they were generated on.
sys.path.insert(0, os.environ.get(
    "SPARK_PROTO_PY",
    os.path.expanduser("~/AppData/Roaming/.minecraft-dev/benchmarks/spark-proto-py")))
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


def under(path, frame, thread_name="Server thread", floor=0.004):
    """Print the subtree beneath the first occurrence of a named frame.

    The flat ranking says WHAT costs; this says what it is made of, which is the difference between
    "GoalSelector.tick is 15%" and knowing whether that 15% is goal bookkeeping or the work goals
    trigger. A share is printed against the named frame's own total, not against the tick, so the
    children of a 15% frame read as fractions of that 15%.
    """
    data = spark_sampler_pb2.SamplerData()
    data.ParseFromString(Path(path).read_bytes())
    for thread in data.threads:
        if thread.name != thread_name:
            continue
        pool = list(thread.children)

        # The HEAVIEST match, not the first one depth-first search reaches. "GoalSelector.tick" is a
        # substring of "GoalSelector.tickRunningGoals", and the first hit was the 2552 ms inner frame
        # rather than the 14148 ms one asked for: a real subtree, of the wrong question.
        found = []

        def find(i):
            if frame in label(pool[i]):
                found.append(i)
            for c in pool[i].children_refs:
                find(c)

        for root in thread.children_refs:
            find(root)
        if found:
            hit = max(found, key=lambda j: sum(pool[j].times))
            exact = [j for j in found if label(pool[j]).endswith("." + frame.split(".")[-1])]
            if exact:
                hit = max(exact, key=lambda j: sum(pool[j].times))
            base = sum(pool[hit].times)
            print("%s  under %s: %.0f ms" % (Path(path).name, label(pool[hit]), base))

            def show(j, d):
                node = pool[j]
                t = sum(node.times)
                if t / base < floor or d > 9:
                    return
                print("  %s%-66s %7.0f %5.1f%%"
                      % ("  " * d, label(node)[-66:], t, 100.0 * t / base))
                for c in node.children_refs:
                    show(c, d + 1)
            for c in pool[hit].children_refs:
                show(c, 0)
            return
    print("%s: frame %r not found on %s" % (path, frame, thread_name))


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    if argv[1] == "--under":
        for p in argv[3:]:
            under(p, argv[2])
            print()
        return 0
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
