#!/usr/bin/env python3
"""Two drill-downs on the server thread of a client profile.

    python bench/client/drill.py <run folder> [top N]

1. SYNC SEARCH CALLERS. For every outermost PathFinder.findPath on the server thread, the path of
   frames from it up to the nearest AI caller (a Goal, a Behavior, a Sensor, or anything in a mod
   package), reported as "caller <- ... <- findPath", with the time under it. This says which callers
   PathWeaver left synchronous.
2. PATHWEAVER FRAMES. Every frame in a dev.pathweaver class or a pathweaver$ mixin method, by total
   time (outermost per path) and by self time, so the cost of our own code on the tick is attributed to
   a method.
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

AI_MARKERS = ("Goal", "Behavior", "Sensor", "Task", "Brain")


def short(node):
    return "%s.%s" % (node.class_name.rsplit(".", 1)[-1], node.method_name)


def is_ai(node):
    cls = node.class_name.rsplit(".", 1)[-1]
    return any(m in cls for m in AI_MARKERS) and "PathNavigation" not in cls


def is_pw(node):
    return node.class_name.startswith("dev.pathweaver") or "pathweaver$" in node.method_name


def main(argv):
    folder = Path(argv[1])
    top = int(argv[2]) if len(argv) > 2 else 15
    data = spark_sampler_pb2.SamplerData()
    data.ParseFromString((folder / "profile.sparkprofile").read_bytes())
    thread = [t for t in data.threads if t.name == ft.SERVER_THREAD][0]
    pool = list(thread.children)

    callers = collections.Counter()
    pw_total = collections.Counter()
    pw_self = collections.Counter()
    # (node index, ancestors as a tuple of node indexes, open pathweaver labels)
    stack = [(r, (), frozenset()) for r in thread.children_refs]
    while stack:
        i, anc, open_pw = stack.pop()
        node = pool[i]
        t = sum(node.times)
        if ft.matches(node, "PathFinder.findPath") and not any(ft.matches(pool[a], "PathFinder.findPath") for a in anc):
            chain = []
            for a in reversed(anc):
                chain.append(short(pool[a]))
                if is_ai(pool[a]):
                    break
            callers[" <- ".join(reversed(chain[-1:])) + "  [via " + " <- ".join(chain[:3]) + "]"] += t
            continue
        now_pw = open_pw
        if is_pw(node):
            label = short(node)
            if label not in open_pw:
                pw_total[label] += t
                now_pw = open_pw | {label}
            child_t = sum(sum(pool[c].times) for c in node.children_refs)
            pw_self[label] += max(0.0, t - child_t)
        for c in node.children_refs:
            stack.append((c, anc + (i,), now_pw))

    print("== %s" % folder.name)
    print("-- synchronous findPath on the server thread, by nearest AI caller (ms)")
    for k, v in callers.most_common(top):
        print("   %8.0f  %s" % (v, k))
    print("   %8.0f  TOTAL" % sum(callers.values()))
    print("-- PathWeaver frames on the server thread, total (outermost per path) and self (ms)")
    for k, v in pw_total.most_common(top):
        print("   %8.0f total %8.0f self  %s" % (v, pw_self[k], k))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
