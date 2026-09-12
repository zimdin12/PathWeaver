#!/usr/bin/env python3
"""Judge the distance-LOD campaign against its preregistration, mechanically.

    python bench/lod_report.py [bench/lod]

Every threshold is copied from docs/evidence/lod-2026-09/PREREGISTRATION.md, committed before any run.
This applies them and prints PASS or FAIL; it does not decide what the result means. Writing the rules
down first stops the numbers being read generously, and this file stops the reading being done by hand.

EVERY ATTEMPT IS READ, voided ones included, from its log and profile rather than from the row the run
script writes on success, so a voided attempt is reported next to the one that replaced it.

TWO DEVIATIONS from the preregistration, both recorded in the results with their evidence:

  * Churn is gated on PathNavigation.recomputePath being PRESENT exactly when churn is on, applied to
    every attempt. The script's carpet-state probe voided three runs whose profiles show recomputePath,
    which cannot be called without a collision-changing block update. An attempt voided only by that
    probe, and valid under this gate, is reported as valid.
  * dispatched is read from the status command's reply, never from a periodic stats line that carries
    the same key.

WHICH ATTEMPT IS AN ARM'S VALUE. The attempt that completed under the gates in force when it ran: the
original label if it wrote a row, else its -retry, else its -rerun. P4 is then recomputed for every
combination of that round's valid attempts, so a verdict that depends on which attempt was picked is
visible as such.
"""

import importlib.util
import itertools
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("frame_totals", HERE / "frame_totals.py")
ft = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ft)
from spark import spark_sampler_pb2  # noqa: E402  (import path set up by frame_totals)

RECOMPUTE = "PathNavigation.recomputePath"
FIND = "PathFinder.findPath"

ARMS = {  # arm -> (async, lod, churn)
    "A1": ("false", "off", "on"), "A2": ("false", "on", "on"),
    "A3": ("false", "off", "off"), "A4": ("false", "on", "off"),
    "B1": ("true", "off", "on"), "B2": ("true", "on", "on"),
}
SUFFIXES = ("", "-retry", "-rerun")

# ---------------------------------------------------------------- thresholds, from the preregistration
P1_MIN_MS, P1_MIN_FACTOR = 200.0, 5.0
P2_MAX_DIFF_MS = 100.0
P3_LOW, P3_HIGH = 0.45, 0.85
MECHANISM_RATIO = 21.0 / 40.0
CARPET_VOID = "strip read as carpet"


def attempt(folder, label):
    log, prof = folder / (label + ".log"), folder / (label + ".sparkprofile")
    if not log.exists() or not prof.exists():
        return None
    text = log.read_text(encoding="utf-8", errors="replace")
    void_file = folder / (label + ".row.txt.VOID")
    void = void_file.read_text(encoding="utf-8").strip() if void_file.exists() else None

    data = spark_sampler_pb2.SamplerData()
    data.ParseFromString(prof.read_bytes())
    server = [t for t in data.threads if t.name == ft.SERVER_THREAD][0]
    (st, sh), _, _ = ft.thread_totals(server, [ft.TICK, RECOMPUTE, FIND])
    workers = 0.0
    for t in data.threads:
        if t.name != ft.SERVER_THREAD:
            r, _, _ = ft.thread_totals(t, [FIND])
            if r is not None:
                workers += r[0][FIND]

    replies = [int(x) for x in re.findall(r"since server start: dispatched=(\d+)", text)]
    ticks = re.findall(r"Tick durations[^\n]*\n[^0-9\n]*([0-9./; ]+)", text)
    mspt50 = mspt95 = None
    if ticks:
        groups = [g.strip() for g in ticks[-1].split(";")]
        if len(groups) > 1:
            parts = groups[1].split("/")
            mspt50, mspt95 = float(parts[1]), float(parts[2])
    return {
        "label": label, "void": void,
        "tick": st[ft.TICK],
        "recompute": st[RECOMPUTE] if sh[RECOMPUTE] else None,   # None is ABSENT, not zero
        "workers_find": workers,
        "dispatched": replies[1] - replies[0] if len(replies) >= 2 else None,
        "mspt50": mspt50, "mspt95": mspt95,
    }


def valid(a, churn):
    """(ok, reason) under the gates this report enforces."""
    if a["void"] and CARPET_VOID not in a["void"]:
        return False, "voided by the run script: " + a["void"]
    present = a["recompute"] is not None
    if churn == "on" and not present:
        return False, "churn on but recomputePath ABSENT, so no collision-changing update reached a route"
    if churn == "off" and present:
        return False, "churn off but recomputePath PRESENT, so something else is changing blocks"
    return True, "carpet probe void overridden by the recomputePath gate" if a["void"] else "ok"


def ms(v):
    return "ABSENT" if v is None else "%.0f" % v


def main(argv):
    folder = Path(argv[1]) if len(argv) > 1 else HERE / "lod"
    chosen, all_valid = {}, {}

    print("%-15s %-6s %7s %9s %9s %10s %6s %6s  %s" % (
        "attempt", "valid", "tick", "recompute", "workerFind", "dispatched", "mspt50", "mspt95", "gate"))
    for r in ("r1", "r2"):
        for arm, (_, _, churn) in ARMS.items():
            key = "%s-%s" % (r, arm)
            all_valid[key] = []
            for suf in SUFFIXES:
                a = attempt(folder, key + suf)
                if a is None:
                    continue
                ok, why = valid(a, churn)
                print("%-15s %-6s %7.0f %9s %9.0f %10s %6s %6s  %s" % (
                    a["label"], "yes" if ok else "NO", a["tick"], ms(a["recompute"]), a["workers_find"],
                    a["dispatched"], a["mspt50"], a["mspt95"], why))
                if ok:
                    all_valid[key].append(a)
                    # The attempt that completed under the gates in force when it ran.
                    if key not in chosen and not a["void"]:
                        chosen[key] = a
            if key not in chosen and all_valid[key]:
                chosen[key] = all_valid[key][-1]

    def v(key, field):
        return chosen[key][field] if key in chosen else None

    out = []
    print()
    for r in ("r1", "r2"):
        k = lambda arm: "%s-%s" % (r, arm)  # noqa: E731
        if k("A1") in chosen and k("A3") in chosen:
            a1, a3 = v(k("A1"), "recompute") or 0.0, v(k("A3"), "recompute") or 0.0
            out.append(("P1", r, a1 >= P1_MIN_MS and (a3 == 0.0 or a1 >= P1_MIN_FACTOR * a3),
                        "A1 %.0f ms, A3 %s ms" % (a1, ms(v(k("A3"), "recompute")))))
        else:
            out.append(("P1", r, None, "an arm has no valid attempt"))
        if k("A3") in chosen and k("A4") in chosen:
            a3, a4 = v(k("A3"), "recompute"), v(k("A4"), "recompute")
            out.append(("P2", r, (a3 is None and a4 is None) or abs((a3 or 0) - (a4 or 0)) < P2_MAX_DIFF_MS,
                        "A3 %s ms, A4 %s ms" % (ms(a3), ms(a4))))
        else:
            out.append(("P2", r, None, "an arm has no valid attempt"))
        if k("A1") in chosen and k("A2") in chosen and v(k("A1"), "recompute"):
            ratio = (v(k("A2"), "recompute") or 0.0) / v(k("A1"), "recompute")
            out.append(("P3", r, P3_LOW <= ratio <= P3_HIGH,
                        "A2/A1 = %s/%s = %.3f  (window %.2f-%.2f; mechanism predicts %.3f)" % (
                            ms(v(k("A2"), "recompute")), ms(v(k("A1"), "recompute")), ratio,
                            P3_LOW, P3_HIGH, MECHANISM_RATIO)))
        else:
            out.append(("P3", r, None, "an arm has no valid attempt"))
        if k("B1") in chosen and k("B2") in chosen:
            d1, d2 = v(k("B1"), "dispatched"), v(k("B2"), "dispatched")
            w1, w2 = v(k("B1"), "workers_find"), v(k("B2"), "workers_find")
            out.append(("P4a", r, d2 < d1, "dispatched B1 %d (%s), B2 %d (%s): %.1f%% fewer" % (
                d1, chosen[k("B1")]["label"], d2, chosen[k("B2")]["label"], 100.0 * (d1 - d2) / d1)))
            out.append(("P4b", r, w2 < w1, "worker findPath B1 %.0f ms, B2 %.0f ms: %.1f%% less" % (
                w1, w2, 100.0 * (w1 - w2) / w1 if w1 else 0.0)))
            combos = list(itertools.product(all_valid[k("B1")], all_valid[k("B2")]))
            if len(combos) > 1:
                a_ok = sum(1 for b1, b2 in combos if b2["dispatched"] < b1["dispatched"])
                w_ok = sum(1 for b1, b2 in combos if b2["workers_find"] < b1["workers_find"])
                out.append(("P4*", r, None, "sensitivity over every valid attempt pair: P4a holds in %d of %d, "
                            "P4b in %d of %d" % (a_ok, len(combos), w_ok, len(combos))))
        else:
            out.append(("P4a", r, None, "an arm has no valid attempt"))
            out.append(("P4b", r, None, "an arm has no valid attempt"))

    bs = [("%s-%s" % (r, a)) for r in ("r1", "r2") for a in ("B1", "B2")]
    if all(b in chosen and chosen[b]["mspt50"] is not None for b in bs):
        b1 = [v("r1-B1", "mspt50"), v("r2-B1", "mspt50")]
        b2 = [v("r1-B2", "mspt50"), v("r2-B2", "mspt50")]
        noise = max(abs(b1[0] - b1[1]), abs(b2[0] - b2[1]))
        gaps = [abs(b1[i] - b2[i]) for i in (0, 1)]
        separates = all(g > noise for g in gaps) and (b1[0] - b2[0]) * (b1[1] - b2[1]) > 0
        out.append(("P4c", "both", not separates,
                    "median MSPT B1 %s vs B2 %s; gap per round %s against same-arm drift %.2f; predicted NOT to "
                    "separate" % (b1, b2, ["%.2f" % g for g in gaps], noise)))
    else:
        out.append(("P4c", "both", None, "a tick distribution is missing"))

    for name, r, ok, detail in out:
        print("%-4s %-4s %-11s %s" % (name, r, "UNDECIDABLE" if ok is None else ("PASS" if ok else "FAIL"),
                                       detail) if name != "P4*" else "%-4s %-4s %-11s %s" % (name, r, "", detail))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
