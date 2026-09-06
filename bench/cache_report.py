"""What the route cache reported, read out of the server logs the benchmark produced.

The spark profiles say what the CPU did. They cannot say how often a mob asked for a route another
mob had already computed, because that is a decision rather than a stack frame. That number lives in
`/pathweaver status`, which the harness runs at both ends of the measured window, so this reads the
second one.

Every field prints even at zero, and the zeros are the point. A cache with a subtly wrong key and a
cache on a world where mobs genuinely never repeat a search both report no hits; `stored`,
`terrain changed` and `same block only` are what tell them apart.

Usage: python bench/cache_report.py shadow serve
"""
import re
import statistics
import sys
from pathlib import Path

STRIP = re.compile("§.")
FIELDS = [
    ("skipped", r"([0-9]+)\s+searches (?:that COULD have been )?skipped"),
    ("blockOnly", r"([0-9]+)\s+matched except for the mob's exact position"),
    ("stored", r"([0-9]+) stored,"),
    ("refusedGroundMoved", r"stored, ([0-9]+) refused because the ground moved"),
    ("expired", r"mid-search, ([0-9]+) expired,"),
    ("terrainChanged", r"expired, ([0-9]+) dropped when the terrain changed"),
    ("lookups", r"terrain changed, ([0-9]+) lookups"),
]
DISPATCHED = re.compile(r"dispatched=([0-9]+)")


def read(path):
    """The LAST status block in the log, which is the one at the end of the measured window."""
    text = STRIP.sub("", Path(path).read_text(encoding="utf-8", errors="replace"))
    out = {}
    for name, pattern in FIELDS:
        found = re.findall(pattern, text)
        out[name] = int(found[-1]) if found else None
    mode = re.findall(r"route cache: (off|measuring only|serving)", text)
    out["mode"] = mode[-1] if mode else None
    dispatched = DISPATCHED.findall(text)
    out["dispatched"] = int(dispatched[-1]) if dispatched else None
    return out


def main(arms):
    print("ROUTE CACHE, from /pathweaver status at the end of each measured window\n")
    for arm in arms:
        runs = []
        for round_number in (1, 2, 3):
            log = Path(f"bench/deep/{arm}-{round_number}.log")
            if log.exists():
                runs.append(read(log))
        if not runs:
            print(f"  {arm}: NO LOGS FOUND -- this arm did not run, which is not the same as zero")
            continue
        modes = {run["mode"] for run in runs}
        print(f"  {arm}   ({len(runs)} run(s), mode reported: {modes})")
        if modes == {None}:
            print("     the status block never appeared; every number below would be a broken probe")
            continue
        for name, _ in [("dispatched", None)] + FIELDS:
            values = [run[name] for run in runs if run[name] is not None]
            if not values:
                print(f"     {name:<20} NOT REPORTED  <-- the probe never fired")
                continue
            mean = statistics.mean(values)
            print(f"     {name:<20} {mean:>10.0f}   runs: {values}")
        hits = [run["skipped"] for run in runs if run["skipped"] is not None]
        looks = [run["lookups"] for run in runs if run["lookups"] is not None]
        if hits and looks and sum(looks):
            print(f"     {'hit rate':<20} {100.0 * sum(hits) / sum(looks):>9.1f}%")
        print()


if __name__ == "__main__":
    main(sys.argv[1:] or ["async", "shadow", "serve"])
