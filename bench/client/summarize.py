#!/usr/bin/env python3
"""Summarise one or more client runs by phase, from the frame probe's lines in each run's log.

    python bench/client/summarize.py bench/client-runs/<label> [more runs...]

Phases are bounded by the driver's markers: base (PWMARK base .. one), one (one .. horde),
horde (horde .. end). A probe window belongs to the phase its report time falls in, and the first
window of each phase is dropped, because it straddles the marker.

For each phase: the median across windows of frame p50, frame p95, frames per second (n / 5 s),
the share of frames over 50 ms, and tick p50 / p95 / mean, plus the entity count. Also the PathWeaver
counters from the status reply, when there is one.
"""

import re
import statistics
import sys
from pathlib import Path

PROBE = re.compile(r"\[PWPROBE\] (frames|ticks)\s+wall=(\d+)(?: entities=(\d+))? n=(\d+)(?: mean=([\d.]+) "
                   r"p50=([\d.]+) p95=([\d.]+) p99=([\d.]+) max=([\d.]+) ms over>50ms=(\d+))?")
MARK = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\] \[Server thread/INFO\]: .*PWMARK (\w+)")
TIME = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\]")
PHASES = ("base", "one", "horde")


def secs(h, m, s):
    return int(h) * 3600 + int(m) * 60 + int(s)


def run(folder):
    lines = (Path(folder) / "latest.log").read_text(encoding="utf-8", errors="replace").splitlines()
    marks, rows = {}, []
    for line in lines:
        m = MARK.match(line)
        if m:
            marks.setdefault(m.group(4), secs(*m.groups()[:3]))
            continue
        p = PROBE.search(line)
        t = TIME.match(line)
        if p and t and p.group(5):
            rows.append((secs(*t.groups()), p))
    bounds = {"base": ("base", "one"), "one": ("one", "horde"), "horde": ("horde", "end")}
    out = {}
    for phase, (a, b) in bounds.items():
        if a not in marks or b not in marks:
            out[phase] = None
            continue
        frames = [p for t, p in rows if marks[a] <= t < marks[b] and p.group(1) == "frames"][1:]
        ticks = [p for t, p in rows if marks[a] <= t < marks[b] and p.group(1) == "ticks"][1:]
        med = lambda xs: statistics.median(xs) if xs else float("nan")  # noqa: E731
        out[phase] = {
            "windows": (len(frames), len(ticks)),
            "fps": med([int(p.group(4)) / 5.0 for p in frames]),
            "frame_p50": med([float(p.group(6)) for p in frames]),
            "frame_p95": med([float(p.group(7)) for p in frames]),
            "frames_over50_pct": 100.0 * sum(int(p.group(10)) for p in frames) / max(1, sum(int(p.group(4)) for p in frames)),
            "tick_mean": med([float(p.group(5)) for p in ticks]),
            "tick_p50": med([float(p.group(6)) for p in ticks]),
            "tick_p95": med([float(p.group(7)) for p in ticks]),
            "entities": med([int(p.group(3)) for p in ticks if p.group(3)]),
        }
    # The status reply reaches the log as chat when a player ran it and as plain server lines when the
    # scenario ran it as the server; take whichever form is there, once.
    wanted = ("since server start", "workers:", "target changed", "nobody wanted", "installed§")
    status = []
    for l in lines:
        if not any(w in l for w in wanted) or "PathWeaver stats" in l:
            continue
        text = l.split("[CHAT]", 1)[1] if "[CHAT]" in l else l.split("]: ", 1)[-1]
        if text.strip() not in status:
            status.append(text.strip())
    return out, status


def main(argv):
    for folder in argv[1:]:
        out, status = run(folder)
        jars = (Path(folder) / "jars.txt").read_text(encoding="utf-8").strip().splitlines()
        void = " VOID" if (Path(folder) / "VOID").exists() else ""
        print("== %s  %s%s" % (Path(folder).name, jars[-1], void))
        print("   %-6s %7s %9s %9s %8s %9s %8s %8s %8s" % ("phase", "fps", "frame50", "frame95", ">50ms%",
                                                        "tickmean", "tick50", "tick95", "entities"))
        for phase in PHASES:
            r = out[phase]
            if r is None:
                print("   %-6s missing markers" % phase)
                continue
            print("   %-6s %7.1f %9.1f %9.1f %8.1f %9.1f %8.1f %8.1f %8.0f" % (
                phase, r["fps"], r["frame_p50"], r["frame_p95"], r["frames_over50_pct"], r["tick_mean"],
                r["tick_p50"], r["tick_p95"], r["entities"]))
        for s in status:
            print("   status:", re.sub(r"§.", "", s))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
