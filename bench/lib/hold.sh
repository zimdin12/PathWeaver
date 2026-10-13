# Hold every resident pathfinding jar out of the server's mods/ for one run, and give each one back. e4mc is held
# too (see WHY E4MC below).
#
#   . bench/lib/hold.sh
#   pw_hold_init "$SERVER"       || exit 8     # refuses if an earlier run left something held
#   pw_hold_residents            || exit 8     # moves pathweaver-*.jar, pathwright-*.jar and e4mc-*.jar aside
#   ...run...
#   pw_hold_restore                            # call from the script's EXIT/INT/TERM/HUP trap
#
# WHY THIS IS ONE FILE. Four benchmark scripts each carried their own copy of this, and one copy
# diverged into a bug that took the jar off a real server and never put it back. fluid-ladder.sh kept
# its held jars in a colon-joined string and restored them with
#
#     printf '%s' "$HELD" | tr ':' '\n' | while read -r h; do mv ...; done
#
# `read` returns false on a final line with no trailing newline, and with ONE held jar the only line
# is the final line, so the loop body ran zero times. Every run of that script moved the server's
# PathWeaver into .pw-held and left it there, and the next run found nothing in mods/ to hold and
# carried on. The server ran without the mod and nothing said so. The held jars live in an array now,
# which has no final-line problem and does not split a path on the colon in C:/.
#
# WHY IT REFUSES AN OCCUPIED .pw-held. The bug above was invisible because every later run treated a
# populated holding directory as normal. A jar already sitting there means an earlier run did not give
# it back, and starting another run on top of that is how one lost jar becomes a permanent state.
# Refusing is recoverable; carrying on silently is not.

# WHY E4MC. e4mc tunnels the server to the public internet, and with it installed Carpet's fake player is removed
# about a second after it joins: no "joined the game", and `list` reports no players. Every ladder run from
# 2026-09-12 to 2026-09-18 lost its Bench player this way, so no teleport landed and the zombies had no one to
# chase. A bisect of the server's 219 mods on a scratch server found e4mc alone reproduces it, and fabric-api plus
# Carpet alone do not (docs/evidence/phase1-counted/fake-player/). A benchmark has no use for a public tunnel.

PW_HOLD_SERVER=""
PW_HELD=()

pw_hold_init() {
  PW_HOLD_SERVER="${1:?pw_hold_init needs the server directory}"
  PW_HELD=()
  local held="$PW_HOLD_SERVER/.pw-held"
  if [ -d "$held" ] && [ -n "$(ls -A "$held" 2>/dev/null)" ]; then
    echo "REFUSING TO START: $held is not empty. An earlier run held these and never restored them:" >&2
    ls -1 "$held" | sed 's/^/    /' >&2
    echo "Move each back into $PW_HOLD_SERVER/mods/ (or wherever it belongs) and remove $held." >&2
    return 1
  fi
  return 0
}

pw_hold_residents() {
  [ -n "$PW_HOLD_SERVER" ] || { echo "pw_hold_residents: call pw_hold_init first" >&2; return 1; }
  local jar
  local extra=() pattern
  # PW_HOLD_EXTRA: space-separated jar globs a workload also holds, each with its reason in the caller.
  for pattern in ${PW_HOLD_EXTRA:-}; do extra+=("$PW_HOLD_SERVER"/mods/$pattern); done
  for jar in "$PW_HOLD_SERVER"/mods/pathweaver-*.jar "$PW_HOLD_SERVER"/mods/pathwright-*.jar              "$PW_HOLD_SERVER"/mods/e4mc-*.jar "${extra[@]}"; do
    [ -f "$jar" ] || continue
    mkdir -p "$PW_HOLD_SERVER/.pw-held" || return 1
    # Recorded BEFORE the move, so a failure part-way still leaves restore knowing what to look for.
    PW_HELD+=("$jar")
    mv -f "$jar" "$PW_HOLD_SERVER/.pw-held/" || { echo "REFUSING: could not move $jar aside" >&2; return 1; }
  done
  return 0
}

pw_hold_restore() {
  [ -n "$PW_HOLD_SERVER" ] || return 0
  local jar parked failed=0
  for jar in "${PW_HELD[@]}"; do
    parked="$PW_HOLD_SERVER/.pw-held/$(basename "$jar")"
    if [ -f "$parked" ]; then
      mv -f "$parked" "$jar" || { echo "RESTORE FAILED: $parked is still held" >&2; failed=1; }
    elif [ ! -f "$jar" ]; then
      echo "RESTORE FAILED: $(basename "$jar") is neither held nor back in mods/" >&2
      failed=1
    fi
  done
  # rmdir only succeeds on an empty directory, so a jar that could not be moved back keeps it, and the
  # guard in pw_hold_init refuses the next run instead of the loss going unnoticed.
  rmdir "$PW_HOLD_SERVER/.pw-held" 2>/dev/null
  PW_HELD=()
  return "$failed"
}
