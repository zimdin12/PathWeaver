#!/usr/bin/env python3
"""Time under given frames, per thread, outermost per path. For locating work on the wrong thread.

    python bench/client/frame_by_thread.py <profile> <Class.method> [...]
"""
import collections
import importlib.util
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("frame_totals", HERE / "frame_totals.py")
ft = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ft)
from spark import spark_sampler_pb2  # noqa: E402


def main(argv):
    data = spark_sampler_pb2.SamplerData()
    data.ParseFromString(Path(argv[1]).read_bytes())
    frames = argv[2:]
    rows = collections.defaultdict(dict)
    for t in data.threads:
        r, _, _ = ft.thread_totals(t, frames)
        if r is None:
            continue
        totals, hits = r
        for f in frames:
            if hits[f]:
                rows[f][t.name] = rows[f].get(t.name, 0.0) + totals[f]
    for f in frames:
        print("-- %s" % f)
        for name, ms in sorted(rows[f].items(), key=lambda kv: -kv[1])[:6]:
            print("   %8.0f ms  %s" % (ms, name))
        if not rows[f]:
            print("   ABSENT")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
