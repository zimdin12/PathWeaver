#!/usr/bin/env python3
"""CPU time by thread family in a spark profile of the whole client JVM.

    python bench/client/threads.py <profile.sparkprofile> [...]

A family is a thread name with its trailing number removed ("PathWeaver-worker-3" -> "PathWeaver-worker-").
Time is the sum of the thread's root nodes, which is spark's sampled time for that thread.
"""
import collections
import importlib.util
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("frame_totals", HERE / "frame_totals.py")
ft = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ft)
from spark import spark_sampler_pb2  # noqa: E402


def families(path):
    data = spark_sampler_pb2.SamplerData()
    data.ParseFromString(Path(path).read_bytes())
    out = collections.Counter()
    for t in data.threads:
        pool = list(t.children)
        total = sum(sum(pool[r].times) for r in t.children_refs)
        out[re.sub(r"[#\s-]*\d+$", "", t.name)] += total
    return out


def main(argv):
    for path in argv[1:]:
        fam = families(path)
        print("== %s" % path)
        for name, ms in fam.most_common(18):
            print("   %-40s %9.0f ms" % (name, ms))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
