#!/usr/bin/env python3
"""Judge docs/evidence/client-lag-2026-09-14/PREREGISTRATION.md (series c1), by its written rules.

    python bench/client/lag_report.py <folder holding the c1 runs>

A run named <label>-VOID-* was voided when it ran; its rerun carries a "b" suffix and stands in for it.
"""
import importlib.util
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("summarize", HERE / "summarize.py")
sm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sm)

WORSE = 0.10
K0_AGREE = 0.15


def phases(folder, rnd, arm):
    for name in ("c1-%s-%s" % (rnd, arm), "c1-%s-%sb" % (rnd, arm)):
        run = folder / name
        if (run / "latest.log").exists() and not (run / "VOID").exists():
            return name, sm.run(run)[0]
    return None, None


def worse(a, b):
    """a worse than b in one phase: tick mean at least 10% higher or FPS at least 10% lower."""
    return a["tick_mean"] >= b["tick_mean"] * (1 + WORSE) or a["fps"] <= b["fps"] * (1 - WORSE)


def main(argv):
    folder = Path(argv[1])
    data = {}
    for rnd in ("r1", "r2"):
        for arm in ("none", "v080", "v090", "v090def"):
            name, ph = phases(folder, rnd, arm)
            data[rnd, arm] = ph
            if ph is None:
                print("%s-%s MISSING" % (rnd, arm)); continue
            print("%-16s " % name + "  ".join("%s tick %.1f fps %.1f" % (p, ph[p]["tick_mean"], ph[p]["fps"])
                                             for p in ("base", "one", "horde")))
    b1, b2 = data["r1", "none"]["base"]["tick_mean"], data["r2", "none"]["base"]["tick_mean"]
    k0 = abs(b1 - b2) / min(b1, b2) <= K0_AGREE
    print("\nK0  %s  none base tick mean %.1f vs %.1f ms (within 15%%)" % ("PASS" if k0 else "FAIL", b1, b2))
    for name, a, b in (("C1", "v090", "none"), ("C2", "v090", "v080"), ("C3", "v090", "v090def")):
        hits = [p for p in ("one", "horde") if all(worse(data[r, a][p], data[r, b][p]) for r in ("r1", "r2"))]
        if not k0:
            print("%s  WITHHELD (K0)" % name); continue
        print("%s  %s is %s than %s%s" % (name, a, "WORSE" if hits else "not worse", b,
                                          " in " + ", ".join(hits) if hits else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
