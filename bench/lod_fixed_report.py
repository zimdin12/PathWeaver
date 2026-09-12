#!/usr/bin/env python3
"""Judge the predictions the fixed-hook LOD campaign added: P3b, P4d, P5.

    python bench/lod_fixed_report.py <campaign folder>

Thresholds are copied from docs/evidence/lod-fixed-2026-09-13/PREREGISTRATION.md, committed before any
run. P1 to P4c are judged by bench/lod_report.py, unchanged; this reads the same attempts, validates them
with the same gate, and picks each arm's attempt the same way, by importing those functions rather than
copying them.
"""

import importlib.util
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load(name):
    spec = importlib.util.spec_from_file_location(name, HERE / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


lr = load("lod_report")
ls = load("lod_split")

# ---------------------------------------------------------------- thresholds, from the preregistration
P3B_ABOVE = 0.370      # the higher A2/A1 ratio the dropped-refresh hook produced
P4D_MAX_FEWER = 0.30   # 27.0% measured on the dropped hook, plus 3 points
P5_MAX_OVERHEAD_MS = 150.0


def chosen_attempts(folder):
    chosen = {}
    for r in ("r1", "r2"):
        for arm, (_, _, churn) in lr.ARMS.items():
            key = "%s-%s" % (r, arm)
            valid = []
            for suf in lr.SUFFIXES:
                a = lr.attempt(folder, key + suf)
                if a is None:
                    continue
                ok, _ = lr.valid(a, churn)
                if ok:
                    valid.append(a)
                    if key not in chosen and not a["void"]:
                        chosen[key] = a
            if key not in chosen and valid:
                chosen[key] = valid[-1]
    return chosen


def verdicts(folder):
    chosen = chosen_attempts(folder)
    out = []
    for r in ("r1", "r2"):
        a1, a2 = chosen.get(r + "-A1"), chosen.get(r + "-A2")
        if a1 and a2 and a1["recompute"]:
            ratio = (a2["recompute"] or 0.0) / a1["recompute"]
            out.append(("P3b", r, ratio > P3B_ABOVE, "A2/A1 = %.3f, must be above %.3f" % (ratio, P3B_ABOVE)))
        else:
            out.append(("P3b", r, None, "an arm has no valid attempt"))

        b1, b2 = chosen.get(r + "-B1"), chosen.get(r + "-B2")
        if b1 and b2 and b1["dispatched"] and b2["dispatched"] is not None:
            fewer = (b1["dispatched"] - b2["dispatched"]) / b1["dispatched"]
            out.append(("P4d", r, fewer <= P4D_MAX_FEWER, "B2 dispatched %.1f%% fewer than B1, at most %.0f%%" % (
                100.0 * fewer, 100.0 * P4D_MAX_FEWER)))
        else:
            out.append(("P4d", r, None, "an arm has no valid attempt or no dispatched count"))

        if a2:
            s = ls.split(folder / (a2["label"] + ".sparkprofile"))
            out.append(("P5", r, s["overhead_inside"] < P5_MAX_OVERHEAD_MS,
                        "A2 (%s) recompute %.0f ms = search %.0f + overhead %.0f; overhead must be under %.0f" % (
                            a2["label"], s["recompute"], s["find_inside"], s["overhead_inside"], P5_MAX_OVERHEAD_MS)))
        else:
            out.append(("P5", r, None, "A2 has no valid attempt"))
    return out


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    for name, r, ok, detail in verdicts(Path(argv[1])):
        print("%-4s %-4s %-11s %s" % (name, r, "UNDECIDABLE" if ok is None else ("PASS" if ok else "FAIL"), detail))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
