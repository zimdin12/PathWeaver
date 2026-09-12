#!/usr/bin/env python3
"""Judge the 2026-09-13 regression check against its preregistration, mechanically.

    python bench/regression_report.py [bench/regression]

Thresholds are copied from docs/evidence/regression-2026-09-13/PREREGISTRATION.md, committed before any
run. Reads the rungs files bench/ladder.sh writes, and takes the MEDIAN of spark health's 10 second
window, the column the earlier campaign reported, so the two can be compared.
"""

import re
import sys
from pathlib import Path

ARMS = ("off", "v080", "v090")
ROUNDS = ("r1", "r2")
BUSY = (2500, 5000, 10000)
Q2_REGRESSION = 0.03
Q3_LOW, Q3_HIGH = 0.04, 0.12
Q4_DRIFT = 0.05
PAGE = {500: 0.10, 2500: 0.09, 5000: 0.06, 10000: 0.06}   # the published savings, for reference


def read(folder, rnd, arm):
    p = folder / ("reg-%s-%s.rungs.txt" % (rnd, arm))
    if not p.exists():
        return None
    rungs = {}
    for line in p.read_text(encoding="utf-8").splitlines():
        m = re.match(r"\s*(\d+)\s+zombies=.*?at=\s*\d+s\s+([0-9.]+)/([0-9.]+)/([0-9.]+)/([0-9.]+)", line)
        if m:
            rungs[int(m.group(1))] = float(m.group(3))
    return rungs


def pct(x):
    return "%+.1f%%" % (100.0 * x)


def main(argv):
    folder = Path(argv[1]) if len(argv) > 1 else Path(__file__).resolve().parent / "regression"
    data = {(r, a): read(folder, r, a) for r in ROUNDS for a in ARMS}
    all_rungs = sorted({k for v in data.values() if v for k in v})

    print("median ms, spark health 10 s window")
    print("%-6s" % "rung" + "".join("%10s" % ("%s-%s" % (r, a)) for r in ROUNDS for a in ARMS))
    for rung in all_rungs:
        print("%-6d" % rung + "".join(
            "%10s" % (("%.1f" % data[(r, a)][rung]) if data[(r, a)] and rung in data[(r, a)] else "-")
            for r in ROUNDS for a in ARMS))
    print()

    verdicts = []
    # Q1: every run rises 1000 -> 2500 -> 5000 -> 10000
    for (r, a), v in data.items():
        if v is None:
            verdicts.append(("Q1", "%s-%s" % (r, a), None, "run missing"))
            continue
        seq = [v.get(k) for k in (1000, 2500, 5000, 10000)]
        if None in seq:
            verdicts.append(("Q1", "%s-%s" % (r, a), None, "ladder did not reach every rung: %s" % seq))
        else:
            verdicts.append(("Q1", "%s-%s" % (r, a), all(x < y for x, y in zip(seq, seq[1:])), str(seq)))

    def have(rung, arm):
        return all(data[(r, arm)] and rung in data[(r, arm)] for r in ROUNDS)

    # Q4 first, because Q2 and Q3 are withheld at a rung it fails
    withheld = set()
    for rung in BUSY:
        for arm in ARMS:
            if not have(rung, arm):
                continue
            a, b = data[("r1", arm)][rung], data[("r2", arm)][rung]
            drift = abs(a - b) / min(a, b)
            ok = drift <= Q4_DRIFT
            if not ok:
                withheld.add(rung)
            verdicts.append(("Q4", "%s@%d" % (arm, rung), ok, "rounds %.1f and %.1f, %s apart" % (a, b, pct(drift))))

    # Q2: regression only if 0.9.0 is >3% worse than 0.8.0 in the same direction in both rounds
    for rung in BUSY:
        if not (have(rung, "v080") and have(rung, "v090")):
            verdicts.append(("Q2", str(rung), None, "missing"))
            continue
        if rung in withheld:
            verdicts.append(("Q2", str(rung), None, "withheld: an arm's rounds disagree by more than 5%"))
            continue
        worse = [(data[(r, "v090")][rung] - data[(r, "v080")][rung]) / data[(r, "v080")][rung] for r in ROUNDS]
        regression = all(w > Q2_REGRESSION for w in worse)
        verdicts.append(("Q2", str(rung), not regression,
                         "0.9.0 vs 0.8.0: %s, %s (a regression needs both above +3%%)" % tuple(pct(w) for w in worse)))

    # Q3: saving against off on the mean of the two rounds
    for rung in BUSY:
        if not (have(rung, "off") and have(rung, "v090")):
            verdicts.append(("Q3", str(rung), None, "missing"))
            continue
        if rung in withheld:
            verdicts.append(("Q3", str(rung), None, "withheld: an arm's rounds disagree by more than 5%"))
            continue
        off = sum(data[(r, "off")][rung] for r in ROUNDS) / 2
        on = sum(data[(r, "v090")][rung] for r in ROUNDS) / 2
        saving = (off - on) / off
        verdicts.append(("Q3", str(rung), Q3_LOW <= saving <= Q3_HIGH,
                         "saving %s (off %.1f, 0.9.0 %.1f); page says %s; window %s to %s" % (
                             pct(saving), off, on, pct(PAGE.get(rung, float("nan"))), pct(Q3_LOW), pct(Q3_HIGH))))

    for name, where, ok, detail in verdicts:
        print("%-3s %-12s %-11s %s" % (name, where, "UNDECIDABLE" if ok is None else ("PASS" if ok else "FAIL"), detail))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
