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
    """Run one test filter and return (tests, failures, [(name, message)]) or None if it did not run."""
    for stale in glob.glob(os.path.join(ROOT, "build", "test-results", "test", "*.xml")):
        os.remove(stale)
    run([gradlew(), "test", "--tests", test_filter, "--rerun-tasks", "--console=plain"])
    files = glob.glob(os.path.join(ROOT, "build", "test-results", "test", "*.xml"))
    if not files:
        return None
    tests = failures = 0
    detail = []
    for path in files:
        raw = io.open(path, encoding="utf-8").read()
        head = re.search(r'tests="(\d+)" skipped="\d+" failures="(\d+)" errors="(\d+)"', raw)
        tests += int(head.group(1))
        failures += int(head.group(2)) + int(head.group(3))
        for hit in re.finditer(
                r'<testcase name="([^"]+)"[^>]*>\s*<(?:failure|error)[^>]*message="([^"]*)"', raw):
            message = hit.group(2).replace("&#10;", " ").replace("&quot;", '"').replace("&gt;", ">")
            detail.append((hit.group(1), " ".join(message.split())[:160]))
    return tests, failures, detail


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
    if green is None or green[1] != 0:
        print("  INVALID     baseline is not green: %s" % (green,))
        return False
    print("  1 GREEN     %d tests, 0 failures" % green[0])

    io.open(path, "w", encoding="utf-8", newline="\n").write(source.replace(old, new, 1))
    try:
        red = results_for(test_filter)
        if red is None:
            print("  INVALID     the reverted tree did not produce results; probably a compile error")
            return False
        if red[1] == 0:
            print("  NOT WITNESSED  reverting the fix broke nothing: %d tests, 0 failures" % red[0])
            return False
        print("  2 RED       %d tests, %d failing" % (red[0], red[1]))
        for failing, message in red[2]:
            print("              %s\n                %s" % (failing, message))
    finally:
        io.open(path, "wb").write(original)

    if digest(path) != before_digest:
        print("  INVALID     the file was not restored")
        return False

    restored = results_for(test_filter)
    if restored is None or restored[1] != 0:
        print("  INVALID     the restored tree is not green: %s" % (restored,))
        return False
    print("  3 RESTORED  %d tests, 0 failures, file byte-identical" % restored[0])
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
