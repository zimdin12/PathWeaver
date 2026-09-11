#!/usr/bin/env python3
"""Re-run every 0.9.0 four-state witness, from the repository, on demand.

A commit message that says "reverting this made that test fail" is an attestation. This is the
executable version: each entry below names one production edit and the test it should break, and the
script applies exactly that edit, runs exactly that test, restores the file, and re-runs.

What it prints per entry is the whole witness:

    GREEN(before) -> RED(reverted, with the failing test names and messages) -> GREEN(restored)

Rules it enforces on itself, because a witness that can lie is worse than none:

  * The tree must be clean before it starts. A revert left behind by an earlier crash would otherwise
    be indistinguishable from the code under test.
  * Every substitution must match exactly once. A pattern that matches nothing would produce a green
    "revert" and read as the fix being unnecessary; a pattern that matches twice would revert more
    than the entry claims.
  * The file is restored from bytes captured before the edit and compared, so a restore that silently
    failed cannot be reported as a restore.
  * A revert that does not compile is reported as INVALID, never as RED. A compile error is not the
    failure the entry is claiming.

Usage:  python bench/witness.py [entry-name ...]      (no arguments runs all of them)
"""

import hashlib
import io
import os
import re
import subprocess
import sys
import glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The exact testcase each entry must break, AND the discriminating assertion text inside it. BOTH,
# on the SAME failure record.
#
# Two rounds of this were wrong. First the runner accepted any red at all, so a revert that broke an
# unrelated test in the same filter certified the entry. Then it matched the testcase name OR the
# message, which still accepts an unrelated assertion firing inside the named test: a test with four
# assertions has four ways to go red and only one of them is the defect. A pair, matched together on
# one failure, is the property.
#
# An entry with no row here is INVALID rather than skipped, so the table cannot fall behind the entry
# list.
EXPECTED_CAUSE = {
    "expiry-parking":
        ("aResultArrivingAtTheLastAllowedAgeIsCollected",
         "at exactly maxResultAgeTicks"),
    "expiry-sweep":
        ("theSweepLeavesASlotAliveAtTheFinalAllowedAge",
         "the sweep retired a slot"),
    "barrier-stored-entries":
        ("aRouteStoredBeforeTheCacheWasTurnedOffIsNotServedAfterItComesBack",
         "served across an unwatched change"),
    # A @ParameterizedTest is reported by its DISPLAY name, not its method name, so that is the
    # testcase identity here. The strict runner refused this entry when the table named the method,
    # which was a defect in the table and not in the product: both parameterised cases failed with
    # exactly the expected assertion.
    "barrier-pending-candidates":
        ("in-flight across",
         "candidate from before the gap was stored"),
    "gate-inverted":
        ("theObserverRecordsNothingWhileEitherSwitchIsOff",
         "did not stop the observer"),
    "gate-ignored":
        ("theObserverRecordsNothingWhileEitherSwitchIsOff",
         "did not stop the observer"),
    "caller-generation-constant":
        ("everyProductionCallerReadsTheGenerationAtTheCall",
         "does not read the generation at"),
    "park-restarts-the-budget":
        ("parkingCarriesTheDispatchDeadlineRatherThanRestartingIt",
         "restarted the budget from the arrival tick"),
    "collection-drops-the-lower-bound":
        ("aParkedResultDoesNotAnswerAQuestionFromBeforeItsDispatch",
         "before it was dispatched"),
    "status-prints-hardcoded-zeros":
        ("theMeasuredAndHypotheticalCountsAreNeverAddedTogether",
         "served and wouldServe were summed"),
    "serializer-checks-the-mode-only":
        ("everyPersistedSettingRefusesAWrongTypeAndAcceptsItsOwnDefault",
         "resultCacheMaxAgeTicks accepted a value of the wrong type"),
    "serializer-skips-max-age-only":
        ("everyPersistedSettingRefusesAWrongTypeAndAcceptsItsOwnDefault",
         "resultCacheMaxAgeTicks accepted a value of the wrong type"),
    "serializer-skips-max-entries-only":
        ("everyPersistedSettingRefusesAWrongTypeAndAcceptsItsOwnDefault",
         "resultCacheMaxEntries accepted a value of the wrong type"),
    "publication-shares-the-editors-object":
        ("editingTheSavedObjectAfterPublicationCannotReachTheLiveSettings",
         "changed the running settings"),
    "published-list-stays-mutable":
        ("aPublishedSnapshotsCollectionsCannotBeMutatedInPlace",
         "can be added to"),
    "pin-denial-is-discarded":
        ("aChangedPathFinderProducesADenialTheLithiumAuditReturns",
         "produced no returned denial"),
    "hook-stops-delegating":
        ("theBlockChangeHookDelegatesTheWholeDecision",
         "the hook branches"),
    "hook-swaps-the-two-long-arguments":
        ("theSectionKeyAndTheTickReachTheParametersTheyWereComputedFor",
         "the two long arguments are in the wrong order"),
    "serializer-derived-checks":
        ("everyPersistedSettingRefusesAWrongTypeAndAcceptsItsOwnDefault",
         "resultCacheMode accepted a value of the wrong type"),
    "status-sums-the-two-counts":
        ("hitsThatSavedNothingAreNotCountedAsSkippedSearches",
         "reported as savings"),
    "status-saved-capacity":
        ("capacityIsTheOneInForceAndAPendingChangeIsCalledOut",
         "printed as if it were in force"),
    "pathfinder-pin-value":
        ("thePinMatchesTheVanillaClassOnTheClasspath",
         "not the one the shipped PathFinder has"),
    "pathfinder-pin-unused":
        ("theLithiumAuditUsesThatConstantAndNotAnotherDigest",
         "does not use the PathFinder pin"),
}


# (name, file, exact text to replace, replacement, test filter, the test that must go red)
WITNESSES = [
    (
        "expiry-parking",
        "src/main/java/dev/pathweaver/async/EntityInstallSink.java",
        "if (slot == null || slot.path() != null || currentTick > slot.expiryTick()) {",
        "if (slot == null || slot.path() != null || currentTick >= slot.expiryTick()) {",
        "*BrainSinkExpiryBoundaryTest*",
        "parking rejects a result the freshness policy accepts",
    ),
    (
        "expiry-sweep",
        "src/main/java/dev/pathweaver/async/EntityInstallSink.java",
        "brainSink.entrySet().removeIf(entry -> tick > entry.getValue().expiryTick());",
        "brainSink.entrySet().removeIf(entry -> tick >= entry.getValue().expiryTick());",
        "*BrainSinkExpiryBoundaryTest*",
        "the sweep retires a slot one tick early",
    ),
    (
        "barrier-stored-entries",
        "src/main/java/dev/pathweaver/cache/PathCache.java",
        "        entries.clear();\n        pending.clear();\n",
        "        pending.clear();\n",
        "*CachePolicyBarrierJoinTest*",
        "a route stored before a switch is served after it",
    ),
    (
        "barrier-pending-candidates",
        "src/main/java/dev/pathweaver/cache/PathCache.java",
        "        entries.clear();\n        pending.clear();\n",
        "        entries.clear();\n",
        "*CachePolicyBarrierJoinTest*",
        "a search in flight across a switch repopulates the cache",
    ),
    (
        "gate-inverted",
        "src/main/java/dev/pathweaver/cache/BlockChangeObserver.java",
        "        if (!config.recordsBlockChanges()) return;",
        "        if (config.recordsBlockChanges()) return;",
        "*CachePolicyBarrierJoinTest*",
        "the observer records exactly when it should not",
    ),
    (
        "gate-ignored",
        "src/main/java/dev/pathweaver/cache/BlockChangeObserver.java",
        "        if (!config.recordsBlockChanges()) return;",
        "        config.recordsBlockChanges();",
        "*CachePolicyBarrierJoinTest*",
        "the observer asks the gate and throws the answer away",
    ),
    (
        "caller-generation-constant",
        "src/main/java/dev/pathweaver/async/ResultInstaller.java",
        "cacheConfig.resultCacheServes(), cacheConfig.generation());",
        "cacheConfig.resultCacheServes(), 0L);",
        "*CachePolicyBarrierJoinTest*",
        "a caller passes a constant, so the barrier never fires",
    ),
    # ------------------------------------------------------------------ the review's predicted mutants
    #
    # Every entry below was named by the 0.9.0 review as a change that the tests AS WRITTEN would have
    # survived. They are here because a predicted survivor that is never run is still a prediction.
    (
        "park-restarts-the-budget",
        "src/main/java/dev/pathweaver/async/EntityInstallSink.java",
        "        brainSink.put(entityId, new BrainSinkSlot(slot.asked(), path,\n"
        "            slot.dispatchTick(), slot.expiryTick()));",
        "        brainSink.put(entityId, new BrainSinkSlot(slot.asked(), path,\n"
        "            slot.dispatchTick(), currentTick + PathWeaverConfig.get().maxResultAgeTicks));",
        "*BrainSinkExpiryBoundaryTest*",
        "parking restarts the age budget from the arrival tick",
    ),
    (
        "collection-drops-the-lower-bound",
        "src/main/java/dev/pathweaver/async/EntityInstallSink.java",
        "!slot.collectable(asked, currentTick)",
        "!slot.answers(asked, currentTick)",
        "*BrainSinkExpiryBoundaryTest*",
        "a parked result answers a question from before its own dispatch",
    ),
    (
        "status-prints-hardcoded-zeros",
        "src/main/java/dev/pathweaver/cache/CacheStatusLines.java",
        'out.add("    \u00a7a" + counters.served + "\u00a7r  searches skipped"',
        'out.add("    \u00a7a" + 0 + "\u00a7r  searches skipped"',
        "*CacheStatusLinesTest*",
        "the counts are hardcoded to zero while the shares stay correct",
    ),
    (
        "serializer-checks-the-mode-only",
        "src/main/java/dev/pathweaver/config/PathWeaverConfigSerializer.java",
        "            if (READ_ELSEWHERE.contains(key)) continue;",
        "            if (READ_ELSEWHERE.contains(key)) continue;\n"
        "            if (key.startsWith(\"resultCacheMax\")) continue;",
        "*StrictFieldTypeCoverageTest*",
        "only the cache MODE is checked, so neither numeric field is pinned",
    ),
    (
        "serializer-skips-max-age-only",
        "src/main/java/dev/pathweaver/config/PathWeaverConfigSerializer.java",
        "            if (READ_ELSEWHERE.contains(key)) continue;",
        "            if (READ_ELSEWHERE.contains(key)) continue;\n"
        "            if (key.equals(\"resultCacheMaxAgeTicks\")) continue;",
        "*StrictFieldTypeCoverageTest*",
        "resultCacheMaxAgeTicks alone goes unchecked",
    ),
    (
        "serializer-skips-max-entries-only",
        "src/main/java/dev/pathweaver/config/PathWeaverConfigSerializer.java",
        "            if (READ_ELSEWHERE.contains(key)) continue;",
        "            if (READ_ELSEWHERE.contains(key)) continue;\n"
        "            if (key.equals(\"resultCacheMaxEntries\")) continue;",
        "*StrictFieldTypeCoverageTest*",
        "resultCacheMaxEntries alone goes unchecked",
    ),
    (
        "published-list-stays-mutable",
        "src/main/java/dev/pathweaver/config/PathWeaverConfig.java",
        "                if (value instanceof List<?> list) {\n"
        "                    value = java.util.Collections.unmodifiableList(new ArrayList<>(list));\n"
        "                }",
        "                if (value instanceof List<?> list) value = new ArrayList<>(list);",
        "*PublishedConfigOwnershipTest*",
        "a published snapshot list can be added to, changing live settings silently",
    ),
    (
        "publication-shares-the-editors-object",
        "src/main/java/dev/pathweaver/config/PathWeaverConfig.java",
        "        PathWeaverConfig snapshot = snapshotOf(source);",
        "        PathWeaverConfig snapshot = source;",
        "*PathWeaverConfigTest*",
        "the object the settings screen keeps editing is the one that is published",
    ),
    (
        "pin-denial-is-discarded",
        "src/main/java/dev/pathweaver/gate/LithiumPathfindingCompatibility.java",
        '        AuditedMixinCompatibility.checkHash("vanilla PathFinder", bundle.vanillaPathFinder(),\n'
        "            AuditedMixinCompatibility.PATH_FINDER_SHA, diagnostics);",
        '        AuditedMixinCompatibility.checkHash("vanilla PathFinder", bundle.vanillaPathFinder(),\n'
        "            AuditedMixinCompatibility.PATH_FINDER_SHA, new ArrayList<>());",
        "*PathFinderPinTest*",
        "the pin is checked into a list nobody returns",
    ),
    (
        "hook-stops-delegating",
        "src/main/java/dev/pathweaver/mixin/ServerLevelBlockChangeMixin.java",
        "        dev.pathweaver.cache.BlockChangeObserver.observe(",
        "        if (runtime.isRunning()) dev.pathweaver.cache.BlockChangeObserver.observe(",
        "*CachePolicyBarrierJoinTest*",
        "the hook decides something of its own instead of delegating",
    ),
    (
        "hook-swaps-the-two-long-arguments",
        "src/main/java/dev/pathweaver/mixin/ServerLevelBlockChangeMixin.java",
        "            SectionPos.asLong(pos), level.getServer().getTickCount());",
        "            level.getServer().getTickCount(), SectionPos.asLong(pos));",
        "*CachePolicyBarrierJoinTest*",
        "the section key and the tick are passed to each other's parameters, which compiles",
    ),
    (
        "serializer-derived-checks",
        "src/main/java/dev/pathweaver/config/PathWeaverConfigSerializer.java",
        "            if (READ_ELSEWHERE.contains(key)) continue;",
        "            if (READ_ELSEWHERE.contains(key)) continue;\n"
        "            if (key.startsWith(\"resultCache\")) continue;",
        "*StrictFieldTypeCoverageTest*",
        "the three resultCache settings accept a wrong type, as in 0.8.0",
    ),
    (
        "status-sums-the-two-counts",
        "src/main/java/dev/pathweaver/cache/CacheStatusLines.java",
        'out.add("    §a" + counters.served + "§r  searches skipped" '
        '+ share(counters.served, counters));',
        'out.add("    §a" + counters.usableHits() + "§r  searches skipped" '
        '+ share(counters.usableHits(), counters));',
        "*CacheStatusLinesTest*",
        "hits that saved nothing are reported as savings",
    ),
    (
        "status-saved-capacity",
        "src/main/java/dev/pathweaver/cache/CacheStatusLines.java",
        '+ "   entries=" + cache.size() + "/" + cache.capacity()',
        '+ "   entries=" + cache.size() + "/" + config.resultCacheMaxEntries',
        "*CacheStatusLinesTest*",
        "the saved capacity is printed as if it were in force",
    ),
    (
        "pathfinder-pin-value",
        "src/main/java/dev/pathweaver/gate/AuditedMixinCompatibility.java",
        '"095d620eaac37aa71af017858682e89689039a3b999cf2a5fcfce3f1c3973b2c"',
        '"' + "0" * 64 + '"',
        "*PathFinderPinTest*",
        "the pin is not the digest of the shipped class",
    ),
    (
        "pathfinder-pin-unused",
        "src/main/java/dev/pathweaver/gate/LithiumPathfindingCompatibility.java",
        '        AuditedMixinCompatibility.checkHash("vanilla PathFinder", bundle.vanillaPathFinder(),\n            AuditedMixinCompatibility.PATH_FINDER_SHA, diagnostics);\n',
        "",
        "*PathFinderPinTest*",
        "the Lithium audit scans bytes it never pinned",
    ),
]


def run(args):
    return subprocess.run(args, cwd=ROOT, capture_output=True, text=True, shell=False)


def gradlew():
    return os.path.join(ROOT, "gradlew.bat" if os.name == "nt" else "gradlew")


def results_for(test_filter):
    """Run one filter and describe what happened, in enough detail to refuse a false positive.

    Returns a dict, or None when nothing ran at all. The fields exist because each one was a way the
    old version could certify a witness it had not earned:

      exit          the Gradle exit status, which used to be discarded entirely
      tests         executed test count, so an empty or vanished population cannot pass as a run
      skipped       counted separately: an all-skipped suite is not a green suite
      failures      assertion failures only
      errors        framework and infrastructure errors, kept apart from assertion failures
      causes        (test name, message) for every failure AND error, so a caller can require the one
                    it named rather than accepting any red
    """
    for stale in glob.glob(os.path.join(ROOT, "build", "test-results", "test", "*.xml")):
        os.remove(stale)
    completed = run([gradlew(), "test", "--tests", test_filter, "--rerun-tasks", "--console=plain"])
    files = glob.glob(os.path.join(ROOT, "build", "test-results", "test", "*.xml"))
    if not files:
        return None
    outcome = {"exit": completed.returncode, "tests": 0, "skipped": 0,
               "failures": 0, "errors": 0, "causes": [], "executed": set(), "invocations": []}
    for path in files:
        raw = io.open(path, encoding="utf-8").read()
        head = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', raw)
        outcome["tests"] += int(head.group(1))
        outcome["skipped"] += int(head.group(2))
        outcome["failures"] += int(head.group(3))
        outcome["errors"] += int(head.group(4))
        # Class-qualified, and counted rather than set-collapsed. A parameterized test can report
        # several invocations under one display name, and folding them into a set would hide a run
        # that executed one of three. The set is used for membership, the list for population.
        for hit in re.finditer(r'<testcase name="([^"]+)" classname="([^"]+)"', raw):
            identity = hit.group(2) + "#" + hit.group(1)
            outcome["executed"].add(identity)
            outcome["invocations"].append(identity)
        for hit in re.finditer(
                r'<testcase name="([^"]+)" classname="([^"]+)"[^>]*>\s*'
                r'<(failure|error)[^>]*message="([^"]*)"', raw):
            message = hit.group(4).replace("&#10;", " ").replace("&quot;", '"').replace("&gt;", ">")
            outcome["causes"].append((hit.group(2) + "#" + hit.group(1),
                                      " ".join(message.split())))
    return outcome


def describe(outcome):
    if outcome is None:
        return "no results produced"
    return ("exit=%(exit)s tests=%(tests)d skipped=%(skipped)d failures=%(failures)d "
            "errors=%(errors)d" % outcome)


def is_green(outcome):
    """A green run: it ran, it ran something, nothing failed, nothing errored, nothing was skipped.

    Every clause earns its place. The old version accepted a nonzero build exit with plausible XML, an
    empty population, an all-skipped population, and framework errors counted as ordinary failures.
    """
    return (outcome is not None
            and outcome["exit"] == 0
            and outcome["tests"] > 0
            and outcome["skipped"] == 0
            and outcome["failures"] == 0
            and outcome["errors"] == 0)


def digest(path):
    return hashlib.sha256(io.open(path, "rb").read()).hexdigest()


def witness(entry):
    name, relative, old, new, test_filter, expectation = entry
    path = os.path.join(ROOT, relative)
    original = io.open(path, "rb").read()
    before_digest = hashlib.sha256(original).hexdigest()

    print("=" * 100)
    print("%s\n  file        %s\n  expectation %s" % (name, relative, expectation))

    source = original.decode("utf-8")
    occurrences = source.count(old)
    if occurrences != 1:
        print("  INVALID     the revert pattern matches %d times, so it is not an exact revert"
              % occurrences)
        return False

    green = results_for(test_filter)
    if not is_green(green):
        print("  INVALID     baseline is not green: %s" % describe(green))
        for failing, message in (green or {}).get("causes", [])[:3]:
            print("                %s: %s" % (failing, message[:110]))
        return False
    print("  1 GREEN     %s" % describe(green))
    baseline_population = green["tests"]

    # PRE-FLIGHT: the testcase this entry expects to break must exist in the baseline, BEFORE the
    # mutation is applied. Without this, an expectation naming an identity that cannot occur comes
    # back as NOT WITNESSED, which reads as "the mutant survived" and is a claim about the product.
    # It is a defect in the table. That is exactly what happened once: an expectation named a method
    # name for a parameterized test, which JUnit reports by display name, so it could never match.
    wanted = EXPECTED_CAUSE.get(name)
    if wanted is None:
        print("  INVALID     this entry names no expected cause, so any red would satisfy it")
        return False
    testcase, fragment = wanted
    present = [i for i in green["executed"] if testcase in i]
    if not present:
        print("  INVALID     the expected testcase %r does not exist in the baseline population; "
              "this is an apparatus fault, not a surviving mutant" % testcase)
        return False
    print("  0 EXPECT    %s :: %r  (%d matching invocation(s) in the baseline)"
          % (testcase, fragment, len(present)))
    # The SET of testcases, not the count. Equal counts can hide a swapped population, and a count
    # comparison says nothing about which testcase actually ran.
    baseline_executed = green["executed"]
    baseline_invocations = len(green["invocations"])

    ok = False
    try:
        io.open(path, "w", encoding="utf-8", newline="\n").write(source.replace(old, new, 1))
        red = results_for(test_filter)
        if red is None:
            print("  INVALID     the reverted tree produced no results; probably a compile error, "
                  "which is not the failure this entry claims")
        elif red["errors"] > 0 and red["failures"] == 0:
            # A framework error is an infrastructure failure wearing a red hat.
            print("  INVALID     the revert produced only framework ERRORS, not assertion failures: %s"
                  % describe(red))
        elif red["tests"] < baseline_population:
            print("  INVALID     the reverted run executed %d tests against a %d-test baseline"
                  % (red["tests"], baseline_population))
        elif red["failures"] == 0:
            print("  NOT WITNESSED  reverting the fix broke nothing: %s" % describe(red))
        else:
            # THE CHECK THIS RUNNER EXISTED FOR AND DID NOT DO. Any red used to count. An unrelated
            # test failing in the same filter certified the entry just as well as the right one.
            print("  2 RED       %s" % describe(red))
            for failing, message in red["causes"]:
                print("              %s\n                %s" % (failing, message[:140]))
            if red["errors"] > 0:
                # A run carrying framework errors alongside the expected failure is a contaminated
                # run. The expected failure may be real, or may be a second symptom of whatever broke
                # the framework, and this cannot tell those apart.
                print("  INVALID     the reverted run also produced %d framework error(s); an "
                      "expected failure mixed with infrastructure failure is not a clean witness"
                      % red["errors"])
            elif red["skipped"] > 0:
                print("  INVALID     the reverted run skipped %d test(s); a partial population cannot "
                      "establish which testcase failed" % red["skipped"])
            elif red["executed"] - baseline_executed:
                print("  INVALID     the reverted run did not execute the same testcases as the "
                      "baseline; missing: %s"
                      % sorted(baseline_executed - red["executed"])[:4])
            elif len(red["invocations"]) != baseline_invocations:
                print("  INVALID     the reverted run made %d test invocations against %d in the "
                      "baseline; a changed invocation count is not a witnessed failure"
                      % (len(red["invocations"]), baseline_invocations))
            else:
                matched = [(t, m) for t, m in red["causes"]
                           if testcase in t and fragment.lower() in m.lower()]
                if not matched:
                    print("  NOT WITNESSED  no single failure is both %r and %r; the revert broke "
                          "something else" % (testcase, fragment))
                else:
                    print("  MATCHED     %s :: %s" % (matched[0][0], fragment))
                    ok = True
    finally:
        io.open(path, "wb").write(original)

    if not ok:
        return False
    if digest(path) != before_digest:
        print("  INVALID     the file was not restored")
        return False

    restored = results_for(test_filter)
    if not is_green(restored):
        print("  INVALID     the restored tree is not green: %s" % describe(restored))
        return False
    if len(restored["invocations"]) != baseline_invocations:
        print("  INVALID     restored run made %d invocations against %d in the baseline"
              % (len(restored["invocations"]), baseline_invocations))
        return False
    if restored["executed"] != baseline_executed:
        print("  INVALID     the restored run did not execute the same testcases as the baseline; "
              "missing: %s" % sorted(baseline_executed - restored["executed"])[:4])
        return False
    print("  3 RESTORED  %s, file byte-identical, population matches baseline" % describe(restored))
    return True


def main():
    dirty = run(["git", "status", "--porcelain", "--untracked-files=no"]).stdout.strip()
    if dirty:
        print("Refusing to run: tracked files are modified. A revert left behind by an earlier run "
              "would be indistinguishable from the code under test.\n" + dirty)
        return 2

    wanted = sys.argv[1:]
    entries = [e for e in WITNESSES if not wanted or e[0] in wanted]
    if wanted and len(entries) != len(wanted):
        print("Unknown entry. Known: " + ", ".join(e[0] for e in WITNESSES))
        return 2

    head = run(["git", "rev-parse", "HEAD"]).stdout.strip()
    print("PathWeaver 0.9.0 witnesses, %d entries, HEAD %s\n" % (len(entries), head))
    outcomes = [(e[0], witness(e)) for e in entries]

    print("=" * 100)
    for name, ok in outcomes:
        print("  %-30s %s" % (name, "witnessed" if ok else "NOT WITNESSED"))
    return 0 if all(ok for _, ok in outcomes) else 1


if __name__ == "__main__":
    sys.exit(main())
