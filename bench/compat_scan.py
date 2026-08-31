"""Does the rest of the pack still work with this mod running?

A benchmark says what the mod costs. It says nothing about whether the other 221 jars still behave,
and that is the question an operator actually has. This reads a server log from a bench run and
reports what the mod DID to the pack, what it REFUSED, and whether anything broke.

Usage: python bench/compat_scan.py bench/deep/async.log [bench/deep/off.log ...]

Every count is printed even when zero, and the zeros are the point: "no exceptions" is only
meaningful next to evidence that the scanner can see exceptions at all, so the instrument is
positive-controlled against the log's own total line count and its known-present WARN lines.
"""
import re
import sys
from collections import Counter
from pathlib import Path

# What the mod says about other mods. These are the lines that answer "does it work with my pack".
PATTERNS = {
    "mods that touch pathfinding": re.compile(r"Mod '([^']+)' config '[^']+' targets sensitive"),
    "families denied by the scan": re.compile(r"deniedFamilies=(\d+)"),
    "scan failures (fail-closed)": re.compile(r"Foreign-mixin scan failure \(fail-closed\): (.+)"),
    "audits that certified": re.compile(r"Verified exact ([A-Za-z -]+)"),
    "breaker tripped": re.compile(r"(switch\w* off|breaker|failure limit)", re.I),
}
# Anything that looks like the mod breaking, or the pack breaking near it.
FAULTS = {
    "exceptions naming pathweaver": re.compile(r"(Exception|Error|Throwable).*pathweaver", re.I),
    "pathweaver stack frames in a trace": re.compile(r"^\s+at .*dev\.pathweaver"),
    "mixin apply failures": re.compile(r"(Mixin apply failed|InvalidInjectionException|"
                                       r"InjectionError|CrashReport)"),
    # Must be attributable to THIS mod. The first version matched any line with "Worker" and
    # "Failed" in it, and duly reported a tapir texture variant and a C2ME aquifer warning as
    # PathWeaver faults. A scanner that cries wolf teaches you to ignore it.
    "worker search failures": re.compile(
        r"(pathweaver|PathWeaver).{0,80}(failed|threw|exception)"
        r"|(failed|threw).{0,80}pathweaver", re.I),
}


def scan(path):
    text = Path(path).read_text(encoding="utf-8", errors="replace")
    lines = text.split("\n")
    print(f"===== {Path(path).name}   {len(lines)} log lines")

    # Positive control: the instrument must be able to see the WARN lines this log certainly has.
    warns = sum(1 for l in lines if "/WARN]" in l)
    print(f"  instrument check: {warns} WARN lines visible"
          + ("" if warns else "   <-- NO WARN LINES, a zero below proves nothing"))

    touching = sorted(set(PATTERNS["mods that touch pathfinding"].findall(text)))
    print(f"\n  mods that modify pathfinding code: {len(touching)}")
    for m in touching:
        print(f"      {m}")

    certified = sorted(set(PATTERNS["audits that certified"].findall(text)))
    print(f"  audits that certified: {len(certified)}")
    for c in certified:
        print(f"      {c.strip()}")

    refusals = sorted(set(PATTERNS["scan failures (fail-closed)"].findall(text)))
    print(f"  audits that refused: {len(refusals)}")
    for r in refusals:
        print(f"      {r.strip()[:110]}")

    denied = PATTERNS["families denied by the scan"].findall(text)
    print(f"  families denied by the scan: {denied[-1] if denied else 'not reported'}")

    stats = re.findall(r"PathWeaver stats: (.+)", text)
    if stats:
        print(f"  final counters: {stats[-1].strip()[:150]}")

    print("\n  faults:")
    total_faults = 0
    for name, rx in FAULTS.items():
        hits = [l for l in lines if rx.search(l)]
        total_faults += len(hits)
        print(f"      {name:<38} {len(hits)}")
        for h in hits[:3]:
            print(f"          {h.strip()[:120]}")
    print(f"      {'TOTAL':<38} {total_faults}")

    # Which mods appear in any stack trace at all, so a fault can be attributed to a neighbour.
    traces = Counter(re.findall(r"^\s+at (?:knot//)?([a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*){1,2})\.",
                                text, re.M))
    if traces:
        print("\n  packages appearing in stack traces (top 6):")
        for pkg, n in traces.most_common(6):
            print(f"      {pkg:<44} {n}")
    return total_faults


if __name__ == "__main__":
    worst = 0
    for arg in sys.argv[1:]:
        worst = max(worst, scan(arg))
        print()
    print("VERDICT:", "clean" if worst == 0 else f"{worst} fault line(s) need reading")
