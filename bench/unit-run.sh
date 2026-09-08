#!/usr/bin/env bash
# Run the unit suite into a FRESH directory and keep the raw results, whatever they say.
#
# WHY THIS EXISTS
#
# Twice in this release I destroyed diagnostics to get a clean run. The first time I deleted a harness
# series directory and lost the only copies of two failure logs. The second time I deleted
# build/test-results before re-running the unit suite and overwrote the transcript, losing the raw XML
# for a 31-failure run I had just diagnosed. In both cases the deletion was casual, the loss was total,
# and no later run could recover it.
#
# So: a new run gets a new directory, the raw XML is copied out before anything can overwrite it, and
# a failing run is preserved exactly like a passing one. Refusing to start is recoverable. Deleting
# evidence is not.
set -u
cd "$(dirname "$0")/.."

OUT="${1:-build/unit-$(git rev-parse --short HEAD)-$(date -u +%H%M%S)}"
if [ -e "$OUT" ]; then
  echo "REFUSING TO START: $OUT already exists. Name a new directory." >&2
  exit 2
fi
mkdir -p "$OUT/xml"

git rev-parse HEAD > "$OUT/commit.txt"
echo "./gradlew test --rerun-tasks" > "$OUT/command.txt"

rm -rf build/test-results/test
./gradlew test --rerun-tasks --console=plain > "$OUT/gradle.log" 2>&1
echo "$?" > "$OUT/gradle-exit.txt"

# Copied out BEFORE anything else can touch build/. This is the whole point of the script.
cp build/test-results/test/*.xml "$OUT/xml/" 2>/dev/null

python - "$OUT" <<'PY'
import glob, io, os, re, sys
out = sys.argv[1]
rows, t, f, e, sk = [], 0, 0, 0, 0
for p in sorted(glob.glob(os.path.join(out, "xml", "*.xml"))):
    raw = io.open(p, encoding="utf-8").read()
    h = re.search(r'name="([^"]+)" tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', raw)
    if not h:
        continue
    rows.append((h.group(1), int(h.group(2)), int(h.group(3)), int(h.group(4)), int(h.group(5))))
    t += int(h.group(2)); sk += int(h.group(3)); f += int(h.group(4)); e += int(h.group(5))
exit_code = io.open(os.path.join(out, "gradle-exit.txt")).read().strip()
report = io.open(os.path.join(out, "summary.txt"), "w", encoding="utf-8", newline="\n")
report.write("PathWeaver unit suite\n")
report.write("commit       %s\n" % io.open(os.path.join(out, "commit.txt")).read().strip())
report.write("command      %s\n" % io.open(os.path.join(out, "command.txt")).read().strip())
report.write("gradle exit  %s\n" % exit_code)
report.write("raw XML      %s/xml/ (kept, pass or fail)\n" % out)
report.write("totals       suites=%d tests=%d failures=%d errors=%d skipped=%d\n\n"
             % (len(rows), t, f, e, sk))
for name, tests, skipped, fails, errs in rows:
    report.write("  %-62s tests=%-4d fail=%d err=%d skip=%d\n" % (name, tests, fails, errs, skipped))
if f or e:
    report.write("\nFAILURES AND ERRORS, kept in full:\n")
    for p in sorted(glob.glob(os.path.join(out, "xml", "*.xml"))):
        raw = io.open(p, encoding="utf-8").read()
        for hit in re.finditer(
                r'<testcase name="([^"]+)" classname="([^"]+)"[^>]*>\s*<(failure|error)[^>]*message="([^"]*)"',
                raw):
            message = " ".join(hit.group(4).replace("&#10;", " ").split())
            report.write("  %-10s %s#%s\n      %s\n"
                         % (hit.group(3).upper(), hit.group(2), hit.group(1), message[:300]))
report.close()
print("  suites=%d tests=%d failures=%d errors=%d skipped=%d  exit=%s"
      % (len(rows), t, f, e, sk, exit_code))
print("  preserved in %s" % out)
PY
