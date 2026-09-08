#!/usr/bin/env python3
"""Can the manifest classifier say no? Offline, on crafted inputs, no server.

The roster reports SETUP-INVALID when a harness ran the wrong manifest. That claim is worth nothing
until the classifier has been shown to produce it. The one attempt I made to demonstrate it by
planting a stale file failed: gradle regenerated the file, which refuted the mechanism I proposed and
said nothing about the classifier. So this exercises the classifier directly.

Every case names the mistake it is guarding against, and the accept case is here because a classifier
that rejects everything would satisfy all the reject cases.

    python bench/manifest_verdict_control.py
"""

import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from manifest_verdict import verdict  # noqa: E402


REAL = {
    "schemaVersion": 1,
    "id": "pathweaver_gametest",
    "version": "1.0.0",
    "entrypoints": {"fabric-gametest": ["dev.pathweaver.gametest.RoutingGameTest"]},
    "mixins": ["pathweaver-gametest.mixins.json"],
    "depends": {"fabric": "*"},
}


def written(directory, name, document):
    path = os.path.join(directory, name)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(document, handle, indent=2)
    return path


def altered(**changes):
    copy = json.loads(json.dumps(REAL))
    copy.update(changes)
    return copy


def main():
    cases = []
    with tempfile.TemporaryDirectory() as directory:
        expected = written(directory, "expected.json", REAL)

        # Accept. Without this every rejection below is satisfied by a classifier that says no to
        # everything, which is the same silence and useless.
        cases.append(("the same manifest is accepted", True,
                      written(directory, "same.json", json.loads(json.dumps(REAL))), None))

        # A generated manifest differing only in an expanded version string is still the right one.
        # A byte comparison would call this wrong, which is why the classifier compares fields.
        cases.append(("a different version string is still the same harness", True,
                      written(directory, "version.json", altered(version="0.9.0+26.1.2")), None))

        cases.append(("a different mod id is rejected", False,
                      written(directory, "id.json", altered(id="pathweaver_gametest_unsafe")), None))

        # The case an id comparison cannot see, and the reason breaker exists in the roster: two
        # harnesses share a mod id and differ in what they run.
        cases.append(("the SAME id with different entrypoints is rejected", False,
                      written(directory, "entry.json", altered(entrypoints={
                          "fabric-gametest": ["dev.pathweaver.gametest.BreakerGameTest"]})), None))

        cases.append(("the SAME id with a different mixin config is rejected", False,
                      written(directory, "mixins.json", altered(
                          mixins=["pathweaver-gametest-other.mixins.json"])), None))

        cases.append(("the SAME id with no mixins at all is rejected", False,
                      written(directory, "nomixins.json", altered(mixins=[])), None))

        cases.append(("an unreadable consumed manifest is rejected", False,
                      os.path.join(directory, "does-not-exist.json"), None))

        # The loader cross-check: the file on disk must be the mod the process reported loading.
        cases.append(("a loader id agreeing with the manifest is accepted", True,
                      written(directory, "agree.json", json.loads(json.dumps(REAL))),
                      "pathweaver_gametest"))
        cases.append(("a loader id disagreeing with the manifest is rejected", False,
                      written(directory, "disagree.json", json.loads(json.dumps(REAL))),
                      "pathweaver_gametest_aggregate"))

        failures = 0
        for name, should_accept, consumed, loaded in cases:
            ok, reason = verdict(consumed, expected, loaded)
            good = (ok == should_accept)
            failures += 0 if good else 1
            print("  %-4s %-58s %s" % ("PASS" if good else "FAIL", name,
                                       ("OK: " if ok else "SETUP-INVALID: ") + reason))

    print()
    print("  %d of %d control cases behaved as required" % (len(cases) - failures, len(cases)))
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
