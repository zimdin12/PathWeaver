#!/usr/bin/env python3
"""EXPLORATORY, not preregistered: synchronous search time per villager point-of-interest path call.

    python bench/client/poi_per_call.py <run folder> [...]

The lookup-cost reader counts self time in named frames, so it measures where the JIT drew frame
boundaries as much as what the lookup costs: in the h1 series it read 0% for 0.9.0 twice and 10% for a
candidate with no allocation left in that method. This avoids frame names below findPath entirely.

Numerator: spark time in synchronous PathFinder.findPath on the server thread under an AcquirePoi caller.
Denominator: AcquirePoi path calls counted by the probe (every 32nd call attributed, so an estimate)
between the same markers the profile spans, PWMARK one to PWMARK end.
"""
import importlib.util
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("frame_totals", HERE.parent / "frame_totals.py")
ft = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ft)
from spark import spark_sampler_pb2  # noqa: E402

CALLER = "AcquirePoi"
MARK = re.compile(r"PWMARK (\w+)")
ATTRIBUTED = re.compile(r"\| (\d+) \S+ <- (\S+)")


def poi_search_ms(profile):
    data = spark_sampler_pb2.SamplerData()
    data.ParseFromString(Path(profile).read_bytes())
    th = [t for t in data.threads if t.name == ft.SERVER_THREAD][0]
    pool = list(th.children)
    total = 0.0
    stack = [(r, False, False) for r in th.children_refs]
    while stack:
        i, under_caller, in_find = stack.pop()
        n = pool[i]
        now_caller = under_caller or CALLER in n.class_name
        is_find = ft.matches(n, "PathFinder.findPath")
        if is_find and not in_find and now_caller:
            total += sum(n.times)
        for c in n.children_refs:
            stack.append((c, now_caller, in_find or is_find))
    return total


def poi_calls(log):
    inside, calls = False, 0
    for line in Path(log).read_text(encoding="utf-8", errors="replace").splitlines():
        m = MARK.search(line)
        if m and "[Server thread/INFO]" in line:
            inside = {"one": True, "end": False}.get(m.group(1), inside)
            continue
        if inside and "[PWPROBE] paths" in line:
            calls += sum(int(k) for k, caller in ATTRIBUTED.findall(line) if CALLER in caller)
    return calls


def main(argv):
    for folder in map(Path, argv[1:]):
        ms, calls = poi_search_ms(folder / "profile.sparkprofile"), poi_calls(folder / "latest.log")
        print("%-12s AcquirePoi sync search %6.0f ms over ~%5d calls = %5.2f ms per call" % (
            folder.name, ms, calls, ms / calls if calls else float("nan")))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
