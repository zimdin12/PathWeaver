#!/usr/bin/env python3
"""Judge the distance-LOD campaign against its preregistration, mechanically.

    python bench/lod_report.py [bench/lod]

Every threshold below is copied from docs/evidence/lod-2026-09/PREREGISTRATION.md, which was committed
before any run. This script applies them and prints PASS or FAIL per prediction; it does not decide
what the result means. The point of writing the rules down first is that the numbers cannot be read
generously after they arrive, and the point of this file is that the reading is not done by hand.

A run is taken from its retry when the original voided. A run with no row at all is reported MISSING
and every prediction that needs it is reported UNDECIDABLE, never PASS.
"""

import importlib.util
import os
import re
import statistics
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("frame_totals", HERE / "frame_totals.py")
ft = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ft)
from spark import spark_sampler_pb2  # noqa: E402  (path set up by frame_totals)

RECOMPUTE = "PathNavigation.recomputePath"
BLOCK_LOOP = "ServerLevel.sendBlockUpdated"
FIND = "PathFinder.findPath"

# ---------------------------------------------------------------- thresholds, from the preregistration
P1_MIN_MS = 200.0
P1_MIN_FACTOR = 5.0
P2_MAX_DIFF_MS = 100.0
P3_LOW, P3_HIGH = 0.45, 0.85
MECHANISM_RATIO = 21.0 / 40.0


def read_run(folder, label):
    for name in (label, label + "-retry"):
        row = folder / (name + ".row.txt")
        prof = folder / (name + ".sparkprofile")
        if row.exists() and prof.exists():
            return name, row.read_text(encoding="utf-8"), prof
    return None, None, None


def measures(profile):
    data = spark_sampler_pb2.SamplerData()
    data.ParseFromString(profile.read_bytes())
    server = [t for t in data.threads if t.name == ft.SERVER_THREAD][0]
    (st, sh), _, _ = ft.thread_totals(server, [ft.TICK, RECOMPUTE, BLOCK_LOOP, FIND])
    worker_find = 0.0
    for t in data.threads:
        if t.name == ft.SERVER_THREAD:
            continue
        r, _, _ = ft.thread_totals(t, [FIND])
        if r is not None:
            worker_find += r[0][FIND]
    return {
        "tick": st[ft.TICK],
        "recompute": st[RECOMPUTE] if sh[RECOMPUTE] else None,   # None means ABSENT, not zero
        "loop": st[BLOCK_LOOP] if sh[BLOCK_LOOP] else None,
        "find_server": st[FIND] if sh[FIND] else None,
        "find_workers": worker_find,
    }


def row_fields(text):
    f = {}
    for key in ("zombies", "chainTicks", "carpetSeen", "dispatched"):
        m = re.search(key + r"=(\S+)", text)
        f[key] = m.group(1) if m else "?"
    m = re.search(r"ticks: (.*)", text)
    groups = [g.strip() for g in (m.group(1).split(";") if m else [])]
    # spark health prints min/median/95th/max for the last 10 s, then the last minute. The minute is
    # the one that spans most of the profile window.
    f["mspt_median_1m"] = float(groups[1].split("/")[1]) if len(groups) > 1 else None
    f["mspt_95_1m"] = float(groups[1].split("/")[2]) if len(groups) > 1 else None
    return f


def ms(v):
    return "ABSENT" if v is None else "%.0f" % v


def main(argv):
    folder = Path(argv[1]) if len(argv) > 1 else HERE / "lod"
    labels = ["%s-%s" % (r, a) for r in ("r1", "r2") for a in ("A1", "A2", "A3", "A4", "B1", "B2")]
    runs = {}
    print("%-12s %-6s %8s %9s %8s %9s %8s %10s %7s %6s" % (
        "run", "used", "tick ms", "recomp ms", "loop ms", "findSrv", "findWrk", "dispatched", "mspt50", "mspt95"))
    for label in labels:
        used, text, prof = read_run(folder, label)
        if used is None:
            print("%-12s MISSING" % label)
            continue
        m = measures(prof)
        m.update(row_fields(text))
        runs[label] = m
        print("%-12s %-6s %8.0f %9s %8s %9s %8.0f %10s %7s %6s" % (
            label, "retry" if used.endswith("-retry") else "", m["tick"], ms(m["recompute"]), ms(m["loop"]),
            ms(m["find_server"]), m["find_workers"], m["dispatched"], m["mspt_median_1m"], m["mspt_95_1m"]))

    def get(r, arm, key):
        run = runs.get("%s-%s" % (r, arm))
        return None if run is None else run[key]

    def have(*pairs):
        return all(("%s-%s" % p) in runs for p in pairs)

    verdicts = []
    print()
    for r in ("r1", "r2"):
        # P1
        if have((r, "A1"), (r, "A3")):
            a1 = get(r, "A1", "recompute") or 0.0
            a3 = get(r, "A3", "recompute") or 0.0
            ok = a1 >= P1_MIN_MS and (a3 == 0.0 or a1 >= P1_MIN_FACTOR * a3)
            verdicts.append(("P1", r, ok, "A1 %.0f ms, A3 %s ms, factor %s" % (
                a1, ms(get(r, "A3", "recompute")), "inf" if a3 == 0 else "%.1f" % (a1 / a3))))
        else:
            verdicts.append(("P1", r, None, "a run is missing"))
        # P2
        if have((r, "A3"), (r, "A4")):
            a3, a4 = get(r, "A3", "recompute"), get(r, "A4", "recompute")
            ok = (a3 is None and a4 is None) or abs((a3 or 0.0) - (a4 or 0.0)) < P2_MAX_DIFF_MS
            verdicts.append(("P2", r, ok, "A3 %s ms, A4 %s ms" % (ms(a3), ms(a4))))
        else:
            verdicts.append(("P2", r, None, "a run is missing"))
        # P3
        if have((r, "A1"), (r, "A2")):
            a1, a2 = get(r, "A1", "recompute"), get(r, "A2", "recompute")
            if not a1:
                verdicts.append(("P3", r, None, "A1 has no recompute time to divide by"))
            else:
                ratio = (a2 or 0.0) / a1
                ok = P3_LOW <= ratio <= P3_HIGH
                verdicts.append(("P3", r, ok, "A2/A1 = %s/%.0f = %.3f (window %.2f-%.2f, mechanism %.3f)" % (
                    ms(a2), a1, ratio, P3_LOW, P3_HIGH, MECHANISM_RATIO)))
        else:
            verdicts.append(("P3", r, None, "a run is missing"))
        # P4, the first two halves
        if have((r, "B1"), (r, "B2")):
            d1, d2 = int(get(r, "B1", "dispatched")), int(get(r, "B2", "dispatched"))
            w1, w2 = get(r, "B1", "find_workers"), get(r, "B2", "find_workers")
            verdicts.append(("P4a", r, d2 < d1, "dispatched B1 %d, B2 %d (%.1f%% fewer)" % (
                d1, d2, 100.0 * (d1 - d2) / d1 if d1 else 0.0)))
            verdicts.append(("P4b", r, w2 < w1, "worker findPath B1 %.0f ms, B2 %.0f ms" % (w1, w2)))
        else:
            verdicts.append(("P4a", r, None, "a run is missing"))
            verdicts.append(("P4b", r, None, "a run is missing"))

    # P4, the tick half: does B1 vs B2 separate by more than the same arm moves between rounds?
    if all(("%s-%s" % (r, a)) in runs for r in ("r1", "r2") for a in ("B1", "B2")):
        b1 = [get(r, "B1", "mspt_median_1m") for r in ("r1", "r2")]
        b2 = [get(r, "B2", "mspt_median_1m") for r in ("r1", "r2")]
        if None in b1 + b2:
            verdicts.append(("P4c", "both", None, "a tick distribution is missing"))
        else:
            noise = max(abs(b1[0] - b1[1]), abs(b2[0] - b2[1]))
            gaps = [abs(b1[i] - b2[i]) for i in (0, 1)]
            separates = all(g > noise for g in gaps) and (b1[0] - b2[0]) * (b1[1] - b2[1]) > 0
            verdicts.append(("P4c", "both", not separates,
                             "median MSPT B1 %s, B2 %s; arm gap per round %s vs same-arm round-to-round %.2f; "
                             "predicted NOT to separate" % (b1, b2, ["%.2f" % g for g in gaps], noise)))
    else:
        verdicts.append(("P4c", "both", None, "a run is missing"))

    for name, r, ok, detail in verdicts:
        word = "UNDECIDABLE" if ok is None else ("PASS" if ok else "FAIL")
        print("%-4s %-4s %-11s %s" % (name, r, word, detail))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
