#!/usr/bin/env bash
# Can bench/lib/hold.sh put a server back, and can it refuse? Offline, in a throwaway directory.
#
#   bash bench/hold-control.sh
#
# Every benchmark that touches the real server moves its PathWeaver jar out of mods/ and trusts this
# to move it back. That trust was misplaced once already: a copy of this logic in fluid-ladder.sh ran
# its restore loop zero times for a single held jar and left a real server without the mod. So the
# restore is exercised here against a fake server, including the exact shape that failed, and the old
# loop is run too, to prove this control can tell the difference. A control that passes the broken
# version as well is not a control.
set -u
cd "$(dirname "$0")/.."
. bench/lib/hold.sh

T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
pass=0; fail=0
ok()  { echo "  PASS  $1"; pass=$((pass + 1)); }
bad() { echo "  FAIL  $1"; fail=$((fail + 1)); }

fresh() {  # a fake server with the given jars in mods/
  rm -rf "$T/srv"; mkdir -p "$T/srv/mods"
  local j; for j in "$@"; do echo "$j" > "$T/srv/mods/$j"; done
}

echo "hold/restore controls"

# 1. The exact shape that failed: one resident jar.
fresh pathweaver-0.9.0+26.1.2.jar other-mod.jar
pw_hold_init "$T/srv" && pw_hold_residents
[ ! -f "$T/srv/mods/pathweaver-0.9.0+26.1.2.jar" ] && [ -f "$T/srv/.pw-held/pathweaver-0.9.0+26.1.2.jar" ] \
  && ok "a single resident jar is moved aside" || bad "a single resident jar is moved aside"
pw_hold_restore
[ -f "$T/srv/mods/pathweaver-0.9.0+26.1.2.jar" ] && [ ! -d "$T/srv/.pw-held" ] \
  && ok "a single held jar is restored and the holding directory removed" \
  || bad "a single held jar is restored and the holding directory removed"
[ -f "$T/srv/mods/other-mod.jar" ] && ok "an unrelated mod is never touched" || bad "an unrelated mod is never touched"

# 2. Both kinds of resident, together.
fresh pathweaver-0.8.0+26.1.2.jar pathwright-1.0.3-fabric.jar
pw_hold_init "$T/srv" && pw_hold_residents
[ -z "$(ls "$T/srv/mods" 2>/dev/null)" ] && ok "every resident pathfinding jar is held, ours and a competitor's" \
  || bad "every resident pathfinding jar is held, ours and a competitor's"
pw_hold_restore
[ -f "$T/srv/mods/pathweaver-0.8.0+26.1.2.jar" ] && [ -f "$T/srv/mods/pathwright-1.0.3-fabric.jar" ] \
  && ok "both are restored" || bad "both are restored"

# 3. Nothing to hold is not an error, and leaves no holding directory behind.
fresh other-mod.jar
pw_hold_init "$T/srv" && pw_hold_residents && pw_hold_restore
[ ! -d "$T/srv/.pw-held" ] && ok "a server with no resident jar is left exactly as found" \
  || bad "a server with no resident jar is left exactly as found"

# 4. THE REFUSAL. An occupied holding directory means an earlier run lost a jar.
fresh
mkdir -p "$T/srv/.pw-held"; echo lost > "$T/srv/.pw-held/pathweaver-0.9.0+26.1.2.jar"
if pw_hold_init "$T/srv" 2>/dev/null; then
  bad "a run starting over an occupied .pw-held is refused"
else
  ok "a run starting over an occupied .pw-held is refused"
fi
[ -f "$T/srv/.pw-held/pathweaver-0.9.0+26.1.2.jar" ] && ok "the refusal moves nothing" || bad "the refusal moves nothing"

# 5. A jar that cannot be put back is reported, not swallowed.
fresh pathweaver-0.9.0+26.1.2.jar
pw_hold_init "$T/srv" && pw_hold_residents
rm -f "$T/srv/.pw-held/pathweaver-0.9.0+26.1.2.jar"   # simulate the parked copy going missing
if pw_hold_restore 2>/dev/null; then bad "a jar that is neither held nor restored fails the restore"
else ok "a jar that is neither held nor restored fails the restore"; fi

# 6. THE OLD LOOP, to prove case 1 would have caught it. This must FAIL to restore.
fresh pathweaver-0.9.0+26.1.2.jar
SERVER="$T/srv"; HELD=""
mkdir -p "$SERVER/.pw-held"; mv -f "$SERVER/mods/pathweaver-0.9.0+26.1.2.jar" "$SERVER/.pw-held/"
HELD="$SERVER/mods/pathweaver-0.9.0+26.1.2.jar"
printf '%s' "$HELD" | tr ':' '\n' | while read -r h; do
  [ -n "$h" ] && [ -f "$SERVER/.pw-held/$(basename "$h")" ] && mv -f "$SERVER/.pw-held/$(basename "$h")" "$h"
done
if [ -f "$SERVER/mods/pathweaver-0.9.0+26.1.2.jar" ]; then
  bad "the old fluid-ladder loop is detected as failing (it restored, so this control cannot see the bug)"
else
  ok "the old fluid-ladder loop is detected as failing to restore a single jar"
fi

echo
if [ "$fail" -eq 0 ]; then echo "  all $pass control cases behaved as required"; exit 0
else echo "  $fail of $((pass + fail)) control cases FAILED"; exit 1; fi
