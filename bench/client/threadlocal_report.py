#!/usr/bin/env python3
"""Judge docs/evidence/client-lag-2026-09-14/THREADLOCAL-PREREGISTRATION.md, mechanically.

    python bench/client/threadlocal_report.py <folder holding t1-r*-{none,v090,cand} runs>

Thresholds are copied from the preregistration, committed before any run.
"""
import importlib.util
import statistics
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("frame_totals", HERE.parent / "frame_totals.py")
ft = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ft)
spec2 = importlib.util.spec_from_file_location("summarize", HERE / "summarize.py")
sm = importlib.util.module_from_spec(spec2)
spec2.loader.exec_module(sm)
from spark import spark_sampler_pb2  # noqa: E402

T1_MIN_SHARE = 0.10
T2_MAX_SHARE = 0.02
T3_POINTS = 3.0
POI_MARKERS = ("FindPointOfInterest", "NearestBedSensor")
THREADLOCAL = ("ThreadLocal$ThreadLocalMap.getEntryAfterMiss", "ThreadLocal$ThreadLocalMap.getEntry")


def profile_measures(path):
    data = spark_sampler_pb2.SamplerData()
    data.ParseFromString(Path(path).read_bytes())
    th = [t for t in data.threads if t.name == ft.SERVER_THREAD][0]
    pool = list(th.children)
    tick = sync = tl_self = poi = 0.0
    # (node, inside findPath, inside tick, nearest AI caller is a POI caller)
    stack = [(r, False, False, False) for r in th.children_refs]
    while stack:
        i, in_find, in_tick, poi_caller = stack.pop()
        n = pool[i]
        t = sum(n.times)
        label = "%s.%s" % (n.class_name.rsplit(".", 1)[-1], n.method_name)
        is_tick = ft.matches(n, "MinecraftServer.tickServer")
        if is_tick and not in_tick:
            tick += t
        cls = n.class_name.rsplit(".", 1)[-1]
        now_poi = poi_caller or any(m in cls for m in POI_MARKERS)
        is_find = ft.matches(n, "PathFinder.findPath")
        if is_find and not in_find:
            sync += t
            if now_poi:
                poi += t
        now_find = in_find or is_find
        if now_find and label in THREADLOCAL:
            child = sum(sum(pool[c].times) for c in n.children_refs)
            tl_self += max(0.0, t - child)
        for c in n.children_refs:
            stack.append((c, now_find, in_tick or is_tick, now_poi))
    return {"tick": tick, "sync": sync, "tl_share": tl_self / sync if sync else 0.0,
            "poi_share_pts": 100.0 * poi / tick if tick else 0.0}


def main(argv):
    folder = Path(argv[1]) if len(argv) > 1 else HERE.parent / "client-runs"
    arms = {"none": [], "v090": [], "cand": []}
    print("%-12s %8s %8s %9s %10s %10s %10s" % ("run", "tick ms", "sync ms", "TL share", "POI pts",
                                               "tick one", "tick horde"))
    for r in ("r1", "r2", "r3"):
        for arm in arms:
            run = folder / ("t1-%s-%s" % (r, arm))
            if not (run / "profile.sparkprofile").exists() or (run / "VOID").exists():
                print("%-12s MISSING or VOID" % run.name)
                continue
            m = profile_measures(run / "profile.sparkprofile")
            phases, _ = sm.run(run)
            m["tick_one"] = phases["one"]["tick_mean"] if phases["one"] else float("nan")
            m["tick_horde"] = phases["horde"]["tick_mean"] if phases["horde"] else float("nan")
            arms[arm].append(m)
            print("%-12s %8.0f %8.0f %8.1f%% %10.1f %10.1f %10.1f" % (
                run.name, m["tick"], m["sync"], 100 * m["tl_share"], m["poi_share_pts"],
                m["tick_one"], m["tick_horde"]))
    print()

    def verdict(name, ok, detail):
        print("%-3s %-11s %s" % (name, "UNDECIDABLE" if ok is None else ("PASS" if ok else "FAIL"), detail))

    v, c, n = arms["v090"], arms["cand"], arms["none"]
    if v:
        verdict("T1", all(x["tl_share"] >= T1_MIN_SHARE for x in v),
                "v090 ThreadLocal share of synchronous search: %s (each must be >= 10%%)"
                % ", ".join("%.1f%%" % (100 * x["tl_share"]) for x in v))
    else:
        verdict("T1", None, "no v090 runs")
    if c:
        verdict("T2", all(x["tl_share"] < T2_MAX_SHARE for x in c),
                "cand ThreadLocal share: %s (each must be < 2%%)" % ", ".join("%.1f%%" % (100 * x["tl_share"]) for x in c))
    else:
        verdict("T2", None, "no cand runs")
    if n and v and c:
        mn = statistics.median(x["poi_share_pts"] for x in n)
        mv = statistics.median(x["poi_share_pts"] for x in v)
        mc = statistics.median(x["poi_share_pts"] for x in c)
        verdict("T3", abs(mc - mn) <= T3_POINTS and mv - mn > T3_POINTS,
                "POI search share of tick, medians: none %.1f, v090 %.1f, cand %.1f points "
                "(cand within 3 of none, v090 more than 3 above)" % (mn, mv, mc))
        print("T4  reported    median tick mean, one / horde: none %.1f / %.1f, v090 %.1f / %.1f, cand %.1f / %.1f ms" % (
            statistics.median(x["tick_one"] for x in n), statistics.median(x["tick_horde"] for x in n),
            statistics.median(x["tick_one"] for x in v), statistics.median(x["tick_horde"] for x in v),
            statistics.median(x["tick_one"] for x in c), statistics.median(x["tick_horde"] for x in c)))
    else:
        verdict("T3", None, "an arm has no runs")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
