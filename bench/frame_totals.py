#!/usr/bin/env python3
"""Total sampled time under every occurrence of a named frame, on the server thread and on workers.

    python bench/frame_totals.py <file.sparkprofile> <frame> [frame ...]

WHY NOT profile_costs.py --under. That reports the HEAVIEST single occurrence of a frame, which is the
right answer to "what is this subtree made of" and the wrong answer to "how much time went here". A
method reached from two call sites appears twice in the tree. PathNavigation.recomputePath is exactly
that: ServerLevel.sendBlockUpdated calls it when a block changes on a route, and PathNavigation.tick
calls it again to retry a deferred one. Taking the heavier of the two would silently drop the other.

WHAT IS COUNTED. Every OUTERMOST occurrence of the frame, summed. A frame nested inside another
occurrence of itself is already inside that subtree's time and is not added again.

REFUSALS, because a total that looks plausible and is wrong is worse than no total:

  * The roots of each thread are summed and compared with the thread's own total; if they disagree
    the file is not being read the way this assumes, and nothing is printed for that thread.
  * A frame that is not present is printed as ABSENT, never as 0 ms. "The instrument found no time
    here" and "the frame was never sampled" are different claims, and only the second is a zero.
  * The tick frame must be found. A profile with no tickServer is not a server profile, and every
    share printed against it would be a division by something else.
"""

import os
import sys
from pathlib import Path

sys.path.insert(0, os.environ.get(
    "SPARK_PROTO_PY",
    os.path.expanduser("~/AppData/Roaming/.minecraft-dev/benchmarks/spark-proto-py")))
from spark import spark_sampler_pb2  # noqa: E402

# No leading dot: matches() already demands a "." or "$" boundary before the name, and a dot here
# made that boundary check compare against the character before the dot, so tickServer was never
# found. The refusal below caught it rather than printing shares of nothing.
TICK = "tickServer"
SERVER_THREAD = "Server thread"


def label(node):
    return "%s.%s" % (node.class_name, node.method_name)


def matches(node, frame):
    # Suffix match on "Class.method" so a short name like "PathNavigation.recomputePath" finds the
    # fully qualified frame, while "recomputePath" alone does not also swallow "shouldRecomputePath".
    return label(node).endswith(frame) and (
        len(label(node)) == len(frame) or label(node)[-len(frame) - 1] in ".$")


def thread_totals(thread, frames):
    pool = list(thread.children)
    roots_sum = sum(sum(pool[r].times) for r in thread.children_refs)
    thread_total = sum(thread.times) if len(thread.times) else roots_sum
    if thread_total and abs(roots_sum - thread_total) > max(1.0, 0.01 * thread_total):
        return None, roots_sum, thread_total

    totals = {f: 0.0 for f in frames}
    hits = {f: 0 for f in frames}

    # Iterative, carrying the set of frames already open on this path, so recursion depth in a deep
    # call tree cannot blow the Python stack and a nested repeat is not counted twice.
    stack = [(r, frozenset()) for r in thread.children_refs]
    while stack:
        i, open_frames = stack.pop()
        node = pool[i]
        now_open = set(open_frames)
        for f in frames:
            if f not in open_frames and matches(node, f):
                totals[f] += sum(node.times)
                hits[f] += 1
                now_open.add(f)
        frozen = frozenset(now_open)
        for c in node.children_refs:
            stack.append((c, frozen))
    return (totals, hits), roots_sum, thread_total


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    path, frames = argv[1], argv[2:]
    data = spark_sampler_pb2.SamplerData()
    data.ParseFromString(Path(path).read_bytes())

    server = [t for t in data.threads if t.name == SERVER_THREAD]
    if not server:
        print("REFUSED: no '%s' in %s" % (SERVER_THREAD, Path(path).name))
        return 3

    result, roots, total = thread_totals(server[0], [TICK] + frames)
    if result is None:
        print("REFUSED: server-thread roots sum to %.0f ms but the thread total is %.0f ms" % (roots, total))
        return 4
    (st, sh) = result
    if sh[TICK] == 0:
        print("REFUSED: no tickServer frame on the server thread; this is not a server tick profile")
        return 5
    tick_ms = st[TICK]

    print("file         %s" % Path(path).name)
    print("server tick  %.0f ms sampled under tickServer" % tick_ms)
    for f in frames:
        if sh[f] == 0:
            print("  server  %-44s ABSENT (never sampled)" % f)
        else:
            print("  server  %-44s %8.0f ms  %5.2f%% of tick  (%d occurrence(s))"
                  % (f, st[f], 100.0 * st[f] / tick_ms, sh[f]))

    # Everything that is not the server thread, summed, for work moved off it.
    off = {f: 0.0 for f in frames}
    off_hits = {f: 0 for f in frames}
    refused = []
    for thread in data.threads:
        if thread.name == SERVER_THREAD:
            continue
        r, roots, total = thread_totals(thread, frames)
        if r is None:
            refused.append(thread.name)
            continue
        for f in frames:
            off[f] += r[0][f]
            off_hits[f] += r[1][f]
    for f in frames:
        if off_hits[f] == 0:
            print("  other   %-44s ABSENT (never sampled)" % f)
        else:
            print("  other   %-44s %8.0f ms  (%d occurrence(s), all non-server threads)"
                  % (f, off[f], off_hits[f]))
    if refused:
        print("  REFUSED threads whose roots did not sum to their total: %s" % ", ".join(refused))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
