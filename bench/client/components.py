#!/usr/bin/env python3
"""Where the time goes, by component, in a spark profile of the whole client JVM.

    python bench/client/components.py <run folder or profile> [...]

Server thread, outermost occurrences only, so a frame nested inside another counted frame is not
counted twice:
  tick           MinecraftServer.tickServer
  createPath     every PathNavigation.createPath entry (vanilla and PathWeaver's wrapped ones)
  findPath sync  PathFinder.findPath on the server thread: searches that ran synchronously
  pw code        any frame in a dev.pathweaver class or a mixin method carrying "pathweaver$"
  region         PathNavigationRegion.<init>
  prepare        NodeEvaluator subclass prepare(), where the async prologue runs it
  brain          Brain.tick

Workers (threads named PathWeaver-Worker-N):
  findPath       PathFinder.findPath: search work that ran
  busy           everything that is not parked waiting for work

A profile without a PathWeaver worker thread reports the worker rows as ABSENT, not zero.
"""
import importlib.util
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("frame_totals", HERE / "frame_totals.py")
ft = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ft)
from spark import spark_sampler_pb2  # noqa: E402

PARKED = ("Unsafe.park", "LockSupport.park", "Object.wait", "Thread.sleep", "LinkedBlockingQueue.take",
          "ForkJoinPool.awaitWork")


def is_pw(node):
    return node.class_name.startswith("dev.pathweaver") or "pathweaver$" in node.method_name


PREDICATES = {
    "tick": lambda n: ft.matches(n, "MinecraftServer.tickServer"),
    "createPath": lambda n: ft.matches(n, "PathNavigation.createPath"),
    "findPath sync": lambda n: ft.matches(n, "PathFinder.findPath"),
    "pw code": is_pw,
    "region": lambda n: n.class_name.endswith("PathNavigationRegion") and n.method_name == "<init>",
    "prepare": lambda n: n.class_name.endswith("NodeEvaluator") and n.method_name == "prepare",
    "brain": lambda n: ft.matches(n, "Brain.tick"),
}


def outermost(thread, predicates):
    pool = list(thread.children)
    totals = {k: 0.0 for k in predicates}
    stack = [(r, frozenset()) for r in thread.children_refs]
    while stack:
        i, open_ = stack.pop()
        node = pool[i]
        now = set(open_)
        for k, pred in predicates.items():
            if k not in open_ and pred(node):
                totals[k] += sum(node.times)
                now.add(k)
        fz = frozenset(now)
        for c in node.children_refs:
            stack.append((c, fz))
    return totals


def analyse(path):
    data = spark_sampler_pb2.SamplerData()
    data.ParseFromString(Path(path).read_bytes())
    server = [t for t in data.threads if t.name == ft.SERVER_THREAD]
    render = [t for t in data.threads if t.name == "Render thread"]
    workers = [t for t in data.threads if t.name.startswith("PathWeaver-Worker")]
    out = {"server": outermost(server[0], PREDICATES) if server else None}
    if render:
        pool = list(render[0].children)
        out["render total"] = sum(sum(pool[r].times) for r in render[0].children_refs)
    if workers:
        find = busy = 0.0
        parked_pred = {"parked": lambda n: any(ft.matches(n, p) for p in PARKED),
                       "findPath": lambda n: ft.matches(n, "PathFinder.findPath")}
        for w in workers:
            pool = list(w.children)
            total = sum(sum(pool[r].times) for r in w.children_refs)
            t = outermost(w, parked_pred)
            find += t["findPath"]
            busy += total - t["parked"]
        out["workers"] = {"threads": len(workers), "findPath": find, "busy": busy}
    return out


def main(argv):
    for arg in argv[1:]:
        p = Path(arg)
        prof = p / "profile.sparkprofile" if p.is_dir() else p
        if not prof.exists():
            print("== %s  NO PROFILE" % arg)
            continue
        r = analyse(prof)
        s = r["server"]
        print("== %s" % (p.name if p.is_dir() else p))
        if s:
            print("   server: tick %6.0f | createPath %6.0f | findPath sync %6.0f | pw code %6.0f | region %5.0f | "
                  "prepare %5.0f | brain %6.0f ms" % (s["tick"], s["createPath"], s["findPath sync"], s["pw code"],
                                                       s["region"], s["prepare"], s["brain"]))
        w = r.get("workers")
        print("   workers: %s" % ("ABSENT" if not w else "%d threads, findPath %.0f ms, busy %.0f ms" % (
            w["threads"], w["findPath"], w["busy"])))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
