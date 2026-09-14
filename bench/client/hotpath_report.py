#!/usr/bin/env python3
"""Judge docs/evidence/client-lag-2026-09-14/HOTPATH-PREREGISTRATION.md, mechanically.

    python bench/client/hotpath_report.py <folder holding h1-r*-{none,v090,cand} and h1-e-* runs>

Thresholds are copied from the preregistration, committed before any run.
"""
import importlib.util
import statistics
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


tl = load("threadlocal_report", HERE / "threadlocal_report.py")
lc = load("lookup_cost", HERE / "lookup_cost.py")
sm = load("summarize", HERE / "summarize.py")

TAG = "h1"
ARMS = ("none", "v090", "cand")
ROUNDS = ("r1", "r2", "r3")
H0_MIN_LOOKUP = 0.08
H1_MAX_LOOKUP = 0.02
H2_POINTS = 3.0
H3_MAX_THREADLOCAL = 0.02


def measures(run):
    m = tl.profile_measures(run / "profile.sparkprofile")
    sync, forms = lc.measure(run)
    m["lookup_share"] = sum(forms.values()) / sync if sync else 0.0
    phases, _ = sm.run(run)
    for phase in ("one", "horde"):
        r = phases[phase]
        m["tick_" + phase] = r["tick_mean"] if r else float("nan")
        m["over50_" + phase] = r["frames_over50_pct"] if r else float("nan")
    return m


def usable(run):
    return (run / "profile.sparkprofile").exists() and not (run / "VOID").exists()


def verdict(name, ok, detail):
    print("%-3s %-11s %s" % (name, "UNDECIDABLE" if ok is None else ("PASS" if ok else "FAIL"), detail))


def pct(xs, key):
    return ", ".join("%.1f%%" % (100 * x[key]) for x in xs)


def main(argv):
    folder = Path(argv[1]) if len(argv) > 1 else HERE.parent / "client-runs"
    arms = {a: [] for a in ARMS}
    header = "%-14s %8s %8s %8s %8s %8s %9s %9s %8s %8s"
    row = "%-14s %8.0f %8.0f %7.1f%% %7.1f%% %8.1f %9.1f %9.1f %7.1f%% %7.1f%%"
    print(header % ("run", "tick ms", "sync ms", "lookup", "TL", "POI pts", "tick one", "tick horde",
                    ">50 one", ">50 hrd"))
    labels = [("%s-%s-%s" % (TAG, r, a), a) for r in ROUNDS for a in ARMS]
    labels += [("%s-e-v090" % TAG, None), ("%s-e-cand" % TAG, None)]
    for label, arm in labels:
        run = folder / label
        if not usable(run):
            print("%-14s MISSING or VOID" % label)
            continue
        m = measures(run)
        if arm:
            arms[arm].append(m)
        print(row % (label, m["tick"], m["sync"], 100 * m["lookup_share"], 100 * m["tl_share"],
                     m["poi_share_pts"], m["tick_one"], m["tick_horde"], m["over50_one"], m["over50_horde"]))
    print()

    n, v, c = arms["none"], arms["v090"], arms["cand"]
    full = all(len(arms[a]) == len(ROUNDS) for a in ARMS)
    if not full:
        print("an arm is missing rounds; verdicts need all %d rounds of every arm" % len(ROUNDS))

    h0 = sum(x["lookup_share"] >= H0_MIN_LOOKUP for x in v) >= 2 if full else None
    verdict("H0", h0, "v090 lookup cost share of synchronous search: %s (at least two must be >= 8%%)"
            % pct(v, "lookup_share"))
    h1 = all(x["lookup_share"] < H1_MAX_LOOKUP for x in c) if full and h0 else None
    verdict("H1", h1, "cand lookup cost share: %s (each must be < 2%%)" % pct(c, "lookup_share"))
    if full:
        mn, mv, mc = (statistics.median(x["poi_share_pts"] for x in xs) for xs in (n, v, c))
        h2 = abs(mc - mn) <= H2_POINTS if mv - mn > H2_POINTS else None
        verdict("H2", h2, "POI search share of tick, medians: none %.1f, v090 %.1f, cand %.1f points "
                "(cand within 3 of none; decidable only if v090 is more than 3 above)" % (mn, mv, mc))
    else:
        verdict("H2", None, "an arm is missing rounds")
    h3 = all(x["tl_share"] < H3_MAX_THREADLOCAL for x in c) if full else None
    verdict("H3", h3, "cand ThreadLocal share: %s (each must be < 2%%)" % pct(c, "tl_share"))
    if full:
        med = lambda xs, k: statistics.median(x[k] for x in xs)  # noqa: E731
        print("H4  reported    median tick mean one / horde: none %.1f / %.1f, v090 %.1f / %.1f, cand %.1f / %.1f ms"
              % (med(n, "tick_one"), med(n, "tick_horde"), med(v, "tick_one"), med(v, "tick_horde"),
                 med(c, "tick_one"), med(c, "tick_horde")))
        print("                median frames over 50 ms one / horde: none %.1f / %.1f, v090 %.1f / %.1f, "
              "cand %.1f / %.1f %%" % (med(n, "over50_one"), med(n, "over50_horde"), med(v, "over50_one"),
                                      med(v, "over50_horde"), med(c, "over50_one"), med(c, "over50_horde")))
    print("H5  reported    the h1-e-* rows above; render-thread path requests: bench/client/threads.py")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
