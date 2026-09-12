#!/usr/bin/env python3
"""Which benchmark runs had the machine to themselves, and which shared it?

    python bench/machine_load.py <machine-load.csv> <campaign console log> [threshold points, default 8]
    python bench/machine_load.py --self-test

The CSV is bench/machine-load.ps1's. The console log is a campaign script's output, whose run headers
read "=== <label> ... HH:MM:SS" and whose last line reads "=== ... complete HH:MM:SS". A run spans from its
header to the next header.

Background load per sample = total CPU percent - (all java cores / logical cores x 100). It counts
everything that is not a JVM, which includes a stuck desktop window manager and a game someone started.
A run is FLAGGED when its median background differs from the median of all runs' medians by more than the
threshold. The first regression attempt had no such record and could not explain why three of its runs
were 20-30% slower.

--self-test runs the judge on synthetic data where the answer is known both ways, and exits non-zero if
it cannot tell a quiet campaign from one with a busy run.
"""

import csv
import re
import statistics
import sys

CORES = 32
HEADER = re.compile(r"^=== (\S+)\s.*?(\d\d):(\d\d):(\d\d)\s*$")


def seconds(h, m, s):
    return int(h) * 3600 + int(m) * 60 + int(s)


def unwrap(times):
    """UTC clock seconds, made monotonic across midnight."""
    out, offset, last = [], 0, None
    for t in times:
        if last is not None and t + offset < last - 43200:
            offset += 86400
        out.append(t + offset)
        last = t + offset
    return out


def windows(console_lines):
    marks = []
    for line in console_lines:
        m = HEADER.match(line.strip())
        if m:
            marks.append((m.group(1), seconds(*m.groups()[1:])))
    times = unwrap([t for _, t in marks])
    runs = []
    for i, (label, _) in enumerate(marks):
        if label == "..." or i + 1 >= len(marks):
            continue
        if "complete" in label:
            continue
        runs.append((label, times[i], times[i + 1]))
    return runs


def samples(csv_rows):
    rows = [r for r in csv_rows if r and r[0] != "utc"]
    times = unwrap([seconds(*r[0].split(":")) for r in rows])
    return [(t, float(r[1]) - float(r[2]) / CORES * 100.0) for t, r in zip(times, rows)]


def judge(console_lines, csv_rows, threshold):
    runs = windows(console_lines)
    pts = samples(csv_rows)
    per_run = []
    for label, start, end in runs:
        inside = [b for t, b in pts if start <= t < end]
        per_run.append((label, statistics.median(inside) if inside else None, len(inside)))
    medians = [m for _, m, _ in per_run if m is not None]
    centre = statistics.median(medians) if medians else None
    verdicts = []
    for label, med, n in per_run:
        if med is None:
            verdicts.append((label, None, n, "NO SAMPLES", True))
        else:
            flagged = abs(med - centre) > threshold
            verdicts.append((label, med, n, "FLAGGED" if flagged else "ok", flagged))
    return centre, verdicts


def report(centre, verdicts, threshold):
    print("background load = total CPU %% - java share; flag when a run's median is more than %.0f points "
          "from the campaign median (%s)" % (threshold, "n/a" if centre is None else "%.1f%%" % centre))
    for label, med, n, word, _ in verdicts:
        print("  %-16s %6s  over %3d samples  %s" % (label, "-" if med is None else "%.1f%%" % med, n, word))
    flagged = [v[0] for v in verdicts if v[4]]
    print("Q5 %s" % ("FAIL: " + ", ".join(flagged) if flagged else "PASS: no run's background moved"))
    return flagged


def self_test():
    console = ["=== a  x 10:00:00", "=== b  x 10:05:00", "=== c  x 10:10:00", "=== campaign complete 10:15:00"]

    def csv_for(busy_run_extra):
        rows = [["utc", "total_pct", "java_cores"]]
        for i in range(0, 900, 10):
            t = 36000 + i
            extra = busy_run_extra if 300 <= i < 600 else 0.0
            rows.append(["%02d:%02d:%02d" % (t // 3600, t // 60 % 60, t % 60), str(30.0 + extra), "3.2"])
        return rows

    ok = True
    _, quiet = judge(console, csv_for(0.0), 8.0)
    if any(v[4] for v in quiet):
        print("SELF-TEST FAILED: a flat machine was flagged"); ok = False
    _, busy = judge(console, csv_for(20.0), 8.0)
    if [v[0] for v in busy if v[4]] != ["b"]:
        print("SELF-TEST FAILED: the busy run b was not the one flagged: %s" % busy); ok = False
    _, java_only = judge(console, [r if i == 0 or not (300 <= (i - 1) * 10 < 600) else [r[0], "40.0", "6.4"]
                                   for i, r in enumerate(csv_for(0.0))], 8.0)
    if any(v[4] for v in java_only):
        print("SELF-TEST FAILED: the benchmark's own java load was counted as background"); ok = False
    _, midnight = judge(["=== a  x 23:58:00", "=== b  x 23:59:00", "=== campaign complete 00:01:00"],
                        [["utc", "total_pct", "java_cores"], ["23:58:30", "30", "0"], ["23:59:30", "30", "0"],
                         ["00:00:30", "30", "0"]], 8.0)
    if [v[2] for v in midnight] != [1, 2]:
        print("SELF-TEST FAILED: samples across midnight were misassigned: %s" % midnight); ok = False
    print("self-test %s" % ("passed: quiet not flagged, busy run flagged, java not background, midnight"
                            if ok else "FAILED"))
    return 0 if ok else 1


def main(argv):
    if len(argv) > 1 and argv[1] == "--self-test":
        return self_test()
    threshold = float(argv[3]) if len(argv) > 3 else 8.0
    with open(argv[1], newline="", encoding="utf-8") as f:
        rows = list(csv.reader(f))
    with open(argv[2], encoding="utf-8", errors="replace") as f:
        console = f.read().splitlines()
    centre, verdicts = judge(console, rows, threshold)
    report(centre, verdicts, threshold)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
