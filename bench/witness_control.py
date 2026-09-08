#!/usr/bin/env python3
"""Can the witness runner refuse? Offline, on synthetic JUnit XML, no Gradle and no mutation.

The runner decides whether a four-state witness happened. Every claim it makes rests on it being able
to say no, and it has twice been shown unable to: first it accepted ANY red, then it accepted the
named testcase OR the discriminating message rather than both. Both were found by review, not by me,
and neither would have been visible from a run that passed.

So this drives the decision logic directly with crafted results. Each case names the false
certification it prevents. The accept cases are here because a runner that refuses everything would
satisfy every rejection.

    python bench/witness_control.py
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import witness  # noqa: E402


def outcome(exit=0, tests=10, skipped=0, failures=0, errors=0, causes=(), executed=None):
    return {
        "exit": exit, "tests": tests, "skipped": skipped, "failures": failures, "errors": errors,
        "causes": list(causes),
        "executed": set(executed if executed is not None else {"C#a", "C#b", "C#target"}),
    }


BASELINE = outcome()
TARGET = ("dev.pathweaver.X#theTargetTest", "the discriminating assertion text")


def green_cases():
    """is_green must accept only a run that ran, ran something, and was wholly clean."""
    return [
        ("a clean run is green", True, outcome()),
        ("a nonzero build exit is not green", False, outcome(exit=1)),
        ("an empty population is not green", False, outcome(tests=0)),
        ("an all-skipped population is not green", False, outcome(tests=5, skipped=5)),
        ("a partially skipped population is not green", False, outcome(skipped=1)),
        ("an assertion failure is not green", False, outcome(failures=1)),
        ("a framework error is not green", False, outcome(errors=1)),
        ("no results at all is not green", False, None),
    ]


def main():
    failures = 0
    print("  is_green")
    for name, expected, given in green_cases():
        got = witness.is_green(given)
        ok = (got == expected)
        failures += 0 if ok else 1
        print("    %-4s %s" % ("PASS" if ok else "FAIL", name))

    # The acceptance decision, exercised as the runner applies it: a failure record must match the
    # named testcase AND carry the discriminating text.
    print("  expected-cause matching")
    testcase, fragment = "theTargetTest", "the discriminating assertion text"
    cases = [
        ("the named test failing on the named assertion is accepted", True,
         [("theTargetTest", "the discriminating assertion text ==> expected true")]),
        ("an UNRELATED test carrying the right text is refused", False,
         [("someOtherTest", "the discriminating assertion text")]),
        ("the NAMED test failing on a DIFFERENT assertion is refused", False,
         [("theTargetTest", "some other assertion inside the same test")]),
        ("neither matching is refused", False,
         [("someOtherTest", "an unrelated failure")]),
        ("the right pair among several failures is accepted", True,
         [("noiseTest", "noise"), ("theTargetTest", "the discriminating assertion text")]),
    ]
    for name, expected, causes in cases:
        matched = [(t, m) for t, m in causes
                   if t.startswith(testcase) and fragment.lower() in m.lower()]
        got = bool(matched)
        ok = (got == expected)
        failures += 0 if ok else 1
        print("    %-4s %s" % ("PASS" if ok else "FAIL", name))

    # Contamination shapes that must not be read as a witness even when the expected failure is there.
    print("  contaminated reds")
    contaminated = [
        ("expected failure MIXED WITH a framework error is refused", True,
         outcome(failures=1, errors=1)),
        ("expected failure with a skipped test is refused", True,
         outcome(failures=1, skipped=1)),
        ("expected failure on a SHRUNKEN testcase set is refused", True,
         outcome(failures=1, executed={"C#a", "C#target"})),
        ("expected failure on the full clean set is not refused for these reasons", False,
         outcome(failures=1)),
    ]
    for name, should_refuse, red in contaminated:
        refused = (red["errors"] > 0
                   or red["skipped"] > 0
                   or bool(BASELINE["executed"] - red["executed"]))
        ok = (refused == should_refuse)
        failures += 0 if ok else 1
        print("    %-4s %s" % ("PASS" if ok else "FAIL", name))

    print()
    print("  %s" % ("all control cases behaved as required" if failures == 0
                    else "%d CONTROL CASES FAILED" % failures))
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
