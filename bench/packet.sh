#!/usr/bin/env bash
# The 0.9.0 evidence packet for one branch: identities, controls, series, and what is NOT established.
#
# Everything here is a pointer into the repository or into a preserved series directory. Nothing is
# summarised from memory, and the unresolved obligations are printed with the results rather than
# after them, because a reader who stops halfway should not come away with the good half.
set -u
cd "$(dirname "$0")/.."
OUT="${1:-build/harness-0.9.0}"

blob() { git rev-parse "HEAD:$1" 2>/dev/null || echo "UNTRACKED"; }

echo "==============================================================================="
echo "PathWeaver 0.9.0 evidence packet"
echo "==============================================================================="
echo "branch          $(git rev-parse --abbrev-ref HEAD)"
echo "commit          $(git rev-parse HEAD)"
echo "tree clean      $([ -z "$(git status --porcelain --untracked-files=no)" ] && echo yes || echo NO)"
echo "minecraft       $(grep -E '^minecraft_version=' gradle.properties | cut -d= -f2)"
echo "mod version     $(grep -E '^mod_version=' gradle.properties | cut -d= -f2)"
echo
echo "RUNNER IDENTITIES (blob sha1)"
for f in bench/witness.py bench/harness-roster.sh bench/manifest_verdict.py \
         bench/manifest_verdict_control.py bench/classpath_providers.py \
         bench/harness-receipt.sh bench/print-classpath.gradle; do
  printf '  %-40s %s\n' "$f" "$(blob "$f")"
done
echo
echo "-------------------------------------------------------------------------------"
echo "UNIT SUITE"
echo "-------------------------------------------------------------------------------"
sed -n '1,5p' docs/evidence/UNIT-0.9.0.txt 2>/dev/null || echo "  MISSING"
echo
echo "-------------------------------------------------------------------------------"
echo "FOUR-STATE WITNESSES  (bench/witness.py, one exact production revert each)"
echo "-------------------------------------------------------------------------------"
grep -cE "witnessed$" docs/evidence/WITNESS-0.9.0.txt 2>/dev/null \
  | sed 's/^/  entries witnessed: /'
grep -E "NOT WITNESSED|INVALID" docs/evidence/WITNESS-0.9.0.txt 2>/dev/null \
  | sed 's/^/  UNRESOLVED: /' || true
echo "  full transcript: docs/evidence/WITNESS-0.9.0.txt"
echo
echo "-------------------------------------------------------------------------------"
echo "OFFLINE CONTROLS  (no server; the classifier the harness receipt depends on)"
echo "-------------------------------------------------------------------------------"
python bench/manifest_verdict_control.py 2>&1 | sed 's/^/  /'
echo
echo "-------------------------------------------------------------------------------"
echo "HARNESS SERIES"
echo "-------------------------------------------------------------------------------"
bash bench/harness-receipt.sh "$OUT" 2>&1 | sed 's/^/  /'
echo
echo "  Preserved earlier series on this branch, each with its own classification:"
for d in build/harness-0.9.0-*; do
  [ -d "$d" ] && printf '    %s\n' "$d"
done
echo
echo "-------------------------------------------------------------------------------"
echo "FULL DIGESTS of each consumed manifest (the abbreviated ones above are prefixes)"
echo "-------------------------------------------------------------------------------"
for m in "$OUT"/*.manifest.json; do
  [ -f "$m" ] && printf '  %-70s %s\n' "$m" "$(sha256sum "$m" | cut -d' ' -f1)"
done
echo "  The manifest bytes themselves are retained beside each log, so any digest here is"
echo "  recomputable rather than taken on trust."
echo
echo "-------------------------------------------------------------------------------"
echo "NOT ESTABLISHED  (read this with the results, not after them)"
echo "-------------------------------------------------------------------------------"
cat <<'LIMITS'
  ADAPTER ARGUMENT FIDELITY. The block-change hook is checked for exactly one call to the observer,
  no branch of its own, no second copy of the recording rule, and that the three level-derived values
  are computed. Nothing checks that those values reach the right parameters. A hook that swapped the
  dimension hash and the tick would pass every test in the suite. OPEN.

  LOADED-CONTENT IDENTITY, temporal premise. The provider inventory is captured after the harness JVM
  exits and before the next iteration's delete, and it records content with digests rather than paths.
  Calling that the content used DURING execution additionally assumes generation completed before the
  JVM read the resource and that nothing wrote to it between the read and the capture. The task-order
  evidence for the first half is in the packet; the second half is an inference from there being no
  other writer, not an observation. Where a series has no inventory at all it is marked UNVERIFIED and
  is not backfilled.

  CANDIDATE PROVIDERS, not selected providers. The enumeration says how many classpath entries COULD
  supply the harness mod id. It does not model Fabric's selection, and its uniqueness result is
  conditional on the discovery population it actually covered: top-level and nested jars under
  META-INF/jars, recursively, plus exploded directories. Other loader discovery sources are not
  excluded by it.

  GLOBAL ID UNIQUENESS IS FALSE, and was claimed in error earlier. 45 mod ids on this classpath are
  supplied by more than one entry, because the Fabric API modules appear both standalone and nested.
  Only the harness id is unique.

  PER-TEST IDENTITIES are unavailable. A passing GameTest run reports a batch size and a total and
  names tests only on failure. Batch size plus the selecting manifest is a bounded selector claim.

  OWNERSHIP is three separate populations and none of them alone is closure: a production
  direct-field-write census, container mutation prevented by sealing published collections, and the
  excluded reflection and test populations. Public scalar fields on a published snapshot remain
  writable through get().

  UNRESOLVED RUN HISTORY. One stock harness failure is UNEXPLAINED; the contamination mechanism I
  proposed for it was refuted by its own control. One refused harness failure is UNATTRIBUTED, seen
  once under self-inflicted CPU contention and not reproduced. Neither is promoted by later greens.
LIMITS
