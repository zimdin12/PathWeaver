#!/usr/bin/env bash
# One boot, a ladder of mob counts, a tick distribution at every rung.
#
#   bash bench/ladder.sh <label> <off|/path/to/pathweaver.jar> <rung> [rung...]
#
# WHY A LADDER RATHER THAN A POINT. bench/saturate.sh measures one population per boot, and boot plus
# arena is most of the five-minute budget, so a single number costs almost as much as a curve. Worse,
# a single number cannot say whether the harness is measuring load at all. A ladder can: if tick
# durations do not rise as the population climbs, this is not reading the server's work and no
# comparison between arms means anything. That check is the reason the rungs exist, not a by-product.
#
# WHERE THE CLIMB STOPS. It stops when the next rung cannot fit in the budget, and it says which rung
# it reached. A run that quietly dropped rungs to stay inside its cap would report a short ladder that
# looks like a complete one.
#
# THE ARM IS A JAR, NOT A SETTING. "off" holds the jar out of mods/ entirely, because a disabled mod
# still routes every createPath through its mixin wrapper and that prices the feature being off
# rather than the mod being absent. Any other value is a path to the jar to install for this run,
# which is how two released versions are compared against the same arena and the same seed.
#
# GPU: none of this touches one. A dedicated server renders nothing.
# PATHS COME FROM THE ENVIRONMENT. This repository is public; a hard-coded home directory
# publishes the layout of one machine and works on no other. Override PW_SERVER, PW_JAVA or
# PW_OUT to point these somewhere else.
set -u
# Hold and restore live in one place; see bench/lib/hold.sh for the bug that made that matter.
. "$(cd "$(dirname "$0")" && pwd)/lib/hold.sh"
. "$(cd "$(dirname "$0")" && pwd)/lib/arena.sh"
SERVER="${PW_SERVER:-$HOME/AppData/Roaming/.minecraft_server}"
# The Adoptium patch version moves, so it is discovered rather than pinned, and a miss is fatal
# rather than a path that does not exist being handed to the launcher.
JAVA="${PW_JAVA:-$(ls -1d "/c/Program Files/Eclipse Adoptium/jdk-25"*/bin/java.exe 2>/dev/null | tail -1)}"
[ -x "$JAVA" ] || { echo "No JDK 25 found. Set PW_JAVA to a java executable." >&2; exit 7; }
LABEL="${1:?label}"
ARM="${2:?off or a jar path}"
shift 2
RUNGS=("$@")
[ "${#RUNGS[@]}" -gt 0 ] || { echo "give at least one rung"; exit 1; }

OUT="${PW_OUT:-$(cd "$(dirname "$0")/.." && pwd)/bench/deep}"
BUDGET="${BUDGET:-300}"   # the cap, measured rather than hoped for
RESERVE="${RESERVE:-45}"  # shutdown, readout and the controls still happen inside it
BURSTS=2            # player teleports per rung: each one makes the whole population re-path at once
BURST_GAP=11        # seconds between them; spark health's 10 s window must land on a burst

# THE DRIFT EXPERIMENT (docs/evidence/diag-phase1-2026-09-14/w1-drift-proposal/PROPOSAL.md) sets PW_DRIFT_DIR,
# PW_DRIFT_RAW (the load monitor's -Raw file) and PW_AFFINITY (the arm's processor mask, from the topology
# receipt). Unset, nothing below changes what this script does. Set, it refuses to launch unless the minute before
# launch was quiet, sets the mask on the process that creates Java rather than on Java afterwards, freezes the
# JVM's processor count and PathWeaver's pool, and binds the server's Windows process before the server-up mark
# and the arena. Each of those steps lives in bench/drift/drift.sh; a failure here refuses or voids the run.
DRIFT="${PW_DRIFT_DIR:-}"
POOL_FIELD=""
JVM_EXTRA=()
if [ -n "$DRIFT" ]; then
  : "${PW_AFFINITY:?PW_DRIFT_DIR needs PW_AFFINITY}" "${PW_DRIFT_RAW:?PW_DRIFT_DIR needs PW_DRIFT_RAW}"
  DRIFT_BIN="$(cd "$(dirname "$0")" && pwd)/drift"
  mkdir -p "$DRIFT" || exit 8
  DRIFT="$(cd "$DRIFT" && pwd)" || exit 8
  POOL_FIELD=',"poolThreads":8'
  JVM_EXTRA=(-XX:ActiveProcessorCount=16)
  . "$DRIFT_BIN/drift.sh" || exit 8
  drift_begin || { echo "REFUSING: could not open the preflight phase"; exit 8; }
fi
# PHASE 1 COUNTED RUNS (docs/research/phase1-diagnostics-v6.md, W1) set PW_TRACE to the diagnostic jar's state:
# absent (no property), idle or record. PW_TRACE_RUNGS names the rungs whose measurement phase is marked: the
# window opens before the rung's first burst and closes just before spark health reads the tick distribution.
# The jar writes one trace file per window; after the run they are renamed <label>.<rung>.trace.txt in PW_OUT.
# PW_TRACE_BURSTS sets the number of player teleports on a marked rung (default: the same as every rung). v6's W1
# exposure needs 2000 dispatched in the window, and two bursts gave 1734 at rung 1000 in the first smoke run.
# Unset, nothing below changes what this script does.
TRACE="${PW_TRACE:-}"
TRACE_RUNGS=" ${PW_TRACE_RUNGS:-} "
case "$TRACE" in
  "") ;;
  absent) ;;
  idle|record) JVM_EXTRA+=("-Dpathweaver.trace=$TRACE") ;;
  *) echo "REFUSING: PW_TRACE must be absent, idle or record, not $TRACE"; exit 8 ;;
esac
if [ -n "$TRACE" ]; then
  [ -n "${PW_TRACE_RUNGS:-}" ] || { echo "REFUSING: PW_TRACE needs PW_TRACE_RUNGS"; exit 8; }
  for r in $PW_TRACE_RUNGS; do
    case " $* " in *" $r "*) ;; *) echo "REFUSING: traced rung $r is not on the ladder"; exit 8 ;; esac
  done
fi
marked_rung() { [ -n "$TRACE" ] && case "$TRACE_RUNGS" in *" $1 "*) return 0 ;; esac; return 1; }

run_jvm() {
  if [ -n "$DRIFT" ]; then drift_pin_self "$PW_AFFINITY" || exit 9; fi
  exec "$@"
}

START=$(date +%s)
elapsed() { echo $(( $(date +%s) - START )); }
[ -n "$DRIFT" ] && echo "  start clock $(date -u -d "@$START" +%H:%M:%S)"

mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
LOG="$OUT/$LABEL.log"; IN="$OUT/$LABEL.stdin"; SPARKDIR="$SERVER/config/spark"
cd "$SERVER" || exit 1

if grep -q '^level-name=pw-bench' server.properties 2>/dev/null; then
  [ -f server.properties.pristine ] || { echo "REFUSING: bench properties live with no pristine backup"; exit 6; }
else
  cp -f server.properties server.properties.pristine
fi
# The pristine copy follows the operator's file: refreshed whenever the live file is not one a bench run wrote.
[ -f config/pathweaver.json ] && ! grep -q '"compatibilityTier":"UNSAFE","brainSinkAsync":true,"resultCacheMode"' config/pathweaver.json &&
  cp -f config/pathweaver.json config/pathweaver.json.pristine

# ---- the arm, and putting the server back exactly as it was found -------------------------------
INSTALLED=""     # jar copied into mods/ for this run
restore() {
  for pid in "${TAILPID:-}" "${SERVERPID:-}"; do [ -n "$pid" ] && kill "$pid" 2>/dev/null; done
  # Windows holds a jar open until its JVM is gone; deleting earlier can leave the arm jar in the real mods/.
  for _ in $(seq 1 30); do [ -n "${SERVERPID:-}" ] && kill -0 "$SERVERPID" 2>/dev/null || break; sleep 1; done
  # The installed arm jar comes out BEFORE the held jar goes back: they can share a filename, and the
  # other order would overwrite the operator's jar with the one under test.
  [ -n "$INSTALLED" ] && rm -f "$SERVER/mods/$(basename "$INSTALLED")"
  pw_hold_restore
  [ -f "$SERVER/server.properties.pristine" ] &&
    cp -f "$SERVER/server.properties.pristine" "$SERVER/server.properties"
  [ -f "$SERVER/config/pathweaver.json.pristine" ] &&
    cp -f "$SERVER/config/pathweaver.json.pristine" "$SERVER/config/pathweaver.json"
  # A trace that was collected has been moved out; one that was not stays, and so does the folder holding it.
  rmdir "$SERVER/pathweaver-trace" 2>/dev/null
}
trap restore EXIT INT TERM HUP

# Any resident pathfinding mod is held aside in every arm, ours or a competitor's, so the jar under
# test is the only one present and the off arm has none. Both mixin the same navigation call, so
# leaving one in place would have priced the pair.
pw_hold_init "$SERVER" || exit 8
pw_hold_residents || exit 8
ARM_ID="none"
if [ "$ARM" != "off" ]; then
  [ -f "$ARM" ] || { echo "REFUSING: no jar at $ARM"; exit 8; }
  cp -f "$ARM" "$SERVER/mods/" || exit 8
  INSTALLED="$ARM"
  ENABLED=true
  # Derived from the filename rather than listed in a table here, so adding an arm does not mean
  # remembering to update one. pathweaver-0.9.0+26.1.2.jar and pathwright-1.0.3-fabric.jar both
  # reduce to the id the loader prints at startup.
  ARM_ID="$(basename "$ARM" | sed 's/-[0-9].*$//')"
else
  ENABLED=false
fi

rm -rf "$SERVER/pw-bench" "$SERVER/pathweaver-trace"; mkdir -p config
# PW_CACHE selects the route-cache mode for OUR arm only. The default is SHADOW because that is what
# the mod ships: the cache measures and does not serve. A competitor arm is left on its own defaults,
# which is what its users actually run. That asymmetry is real and is reported rather than equalised.
cat > config/pathweaver.json <<CFG
{"configVersion":3,"enabled":${ENABLED},"compatibilityTier":"UNSAFE","brainSinkAsync":true,"resultCacheMode":"${PW_CACHE:-SHADOW}"${POOL_FIELD}}
CFG
rm -f config/pathwright.json
# The bytes the server will read, kept with the run, so a run can be checked against what FROZEN.txt pinned.
cp -f config/pathweaver.json "$OUT/$LABEL.config-used.json"

python - <<'PY'
import pathlib
p = pathlib.Path("server.properties")
want = {"level-name": "pw-bench", "pause-when-empty-seconds": "0", "spawn-monsters": "false",
        "spawn-animals": "false", "spawn-npcs": "false", "view-distance": "10",
        "simulation-distance": "10", "difficulty": "easy", "level-seed": "20260831",
        "sync-chunk-writes": "false", "max-tick-time": "-1"}
lines, seen = [], set()
for l in p.read_text().splitlines():
    k = l.split("=", 1)[0]
    if k in want: lines.append(f"{k}={want[k]}"); seen.add(k)
    else: lines.append(l)
lines += [f"{k}={v}" for k, v in want.items() if k not in seen]
p.write_text("\n".join(lines) + "\n")
PY

echo "arm=$ARM (id=$ARM_ID)  rungs=${RUNGS[*]}  budget=${BUDGET}s (reserve ${RESERVE}s)  pwCache=${PW_CACHE:-SHADOW}"

: > "$IN"; : > "$LOG"; mkdir -p "$SPARKDIR"
if [ -n "$DRIFT" ]; then
  rm -f "$DRIFT/$LABEL.affinity"
  drift_preflight_launch_mark "$START" || { echo "REFUSING: the minute before launch was not quiet"; exit 8; }
fi
( tail -f "$IN" & echo $! > "$OUT/$LABEL.tailpid"; wait ) | run_jvm "$JAVA" -Xmx12G -Xms4G "${JVM_EXTRA[@]}" \
    -XX:+UseG1GC -XX:+ParallelRefProcEnabled \
    -jar fabric-server-mc.26.1.2-loader.0.19.3-launcher.jar nogui >> "$LOG" 2>&1 &
SERVERPID=$!
sleep 1
TAILPID="$(cat "$OUT/$LABEL.tailpid" 2>/dev/null)"
if [ -n "$DRIFT" ]; then
  # The pinning shell writes its record before it execs Java or exits; nothing is judged until it exists.
  for _ in $(seq 1 30); do [ -f "$DRIFT/$LABEL.affinity" ] && break; sleep 1; done
  grep -q 'match=yes$' "$DRIFT/$LABEL.affinity" 2>/dev/null ||
    { echo "VOID RUN: the JVM was not started under the arm's mask"; exit 3; }
fi

say() { echo "$1" >> "$IN"; }
wait_for() { for _ in $(seq 1 "$2"); do grep -aq "$1" "$LOG" && return 0; sleep 1; done; return 1; }

wait_for 'Done (' 300 || { echo "SERVER NEVER STARTED"; exit 2; }
# Bound before the server-up mark, so every sample of the measured span carries the SERVER phase. The monitor
# stamps a sample's second before it reads the phase file, so a sample in the same second as the phase write can
# still carry PREFLIGHT; the pause puts the mark past that second.
if [ -n "$DRIFT" ]; then
  drift_bind_server "$SERVERPID" "$JAVA" || { echo "VOID RUN: the server could not be bound as this run's subject"; exit 3; }
  sleep 2
fi
echo "  server up at $(elapsed)s"

# The arena lives in bench/lib/arena.sh, shared with the Phase 1 W4 workload. It checks its own gamerules and
# that the fake player is online, and a run without either measured something else.
pw_arena || { echo "VOID RUN: the arena is not the one this ladder describes"; exit 3; }
echo "  arena ready at $(elapsed)s"

say "pathweaver status"
sleep 2
FIRST_DISPATCH="$(grep -aoE 'dispatched=[0-9]+' "$LOG" | tail -1 | cut -d= -f2)"

# BASELINE POPULATION, before a single zombie is summoned. The first campaign reported 38, 46 and 32
# more entities than it had asked for, differing per run, and an unexplained addend on the population
# is an unexplained addend on every rung. At 10000 it rounds away; at rung 100 it is a third of the
# load. So it is counted and broken down rather than assumed to be nothing.
say "execute if entity @e[type=!minecraft:player]"
sleep 2
BASELINE="$(grep -aoE 'Test passed. Count: [0-9]+' "$LOG" | tail -1 | grep -oE '[0-9]+$')"
for t in item experience_orb armor_stand item_frame falling_block arrow bat villager; do
  say "execute if entity @e[type=minecraft:$t]"
  sleep 1
done
say "data get entity @e[type=minecraft:item,limit=1] Item"
sleep 2
echo "  baseline population before any summon: ${BASELINE:-?}"

CORNERS=("-50 201 -50" "50 201 -50" "50 201 50" "-50 201 50")
ROWS="$OUT/$LABEL.rungs.txt"; : > "$ROWS"
present=0
corner=0
reached=""
stopped_because="completed every rung"

for want in "${RUNGS[@]}"; do
  # Can the next rung finish inside the cap? Summon cost scales with the increment, and the measuring
  # window is fixed. Guessing low here is how a run silently truncates its own ladder.
  add=$(( want - present ))
  bursts="$BURSTS"; marked_rung "$want" && bursts="${PW_TRACE_BURSTS:-$BURSTS}"
  need=$(( 8 + add / 120 + bursts * BURST_GAP + 10 ))
  left=$(( BUDGET - RESERVE - $(elapsed) ))
  if [ "$need" -gt "$left" ]; then
    stopped_because="rung $want needs about ${need}s and only ${left}s of budget remained"
    break
  fi

  if [ "$add" -gt 0 ]; then
    python - "$IN" "$add" <<'PY'
import random, sys
inp, mobs = sys.argv[1], int(sys.argv[2])
# Seeded per increment size so the same ladder lays mobs down the same way in every arm.
random.seed(20260907 + mobs)
lanes = [-52, -46, -32, -16, 0, 16, 32, 46, 52]
with open(inp, "a") as f:
    for _ in range(mobs):
        x = random.choice(lanes) + random.randint(-3, 3)
        z = random.randint(-56, 56)
        f.write(f"summon minecraft:zombie {x} 201 {z} "
                '{PersistenceRequired:1b,IsBaby:0b,CanPickUpLoot:0b,'
                'Attributes:[{id:"minecraft:follow_range",base:128}]}\n')
PY
    sleep $(( 6 + add / 150 ))
  fi

  # Bursts, then read the distribution. spark health's 10 s window has to land ON a burst, because a
  # steady state is the thing any server absorbs and the spikes are what this mod is sold against.
  if marked_rung "$want"; then say "pathweaver trace mark begin"; sleep 1; fi
  for _ in $(seq 1 "$bursts"); do
    say "tp Bench ${CORNERS[$(( corner % 4 ))]}"
    corner=$(( corner + 1 ))
    TP_SENT=$(( ${TP_SENT:-0} + 1 ))
    sleep "$BURST_GAP"
  done

  say "execute if entity @e[type=!minecraft:player]"
  sleep 2
  alive="$(grep -aoE 'Test passed. Count: [0-9]+' "$LOG" | tail -1 | grep -oE '[0-9]+$')"
  if marked_rung "$want"; then say "pathweaver trace mark end"; sleep 1; MARKED="${MARKED:-} $want"; fi
  say "spark health"
  sleep 3
  ticks="$(grep -aA1 'Tick durations' "$LOG" | tail -1 | sed 's/^[^0-9]*//')"
  say "pathweaver status"
  sleep 2
  disp="$(grep -aoE 'dispatched=[0-9]+' "$LOG" | tail -1 | cut -d= -f2)"

  printf '%6s  zombies=%-6s total=%-6s dispatched=%-8s at=%3ss  %s\n' \
    "$want" "$want" "${alive:-?}" "${disp:-n/a}" "$(elapsed)" "${ticks:-NO TICK DURATIONS}" >> "$ROWS"
  present="$want"
  reached="$want"
done

say "stop"
wait_for 'ThreadedAnvilChunkStorage: All dimensions are saved' 90
sleep 2
for pid in "${TAILPID:-}" "${SERVERPID:-}"; do [ -n "$pid" ] && kill "$pid" 2>/dev/null; done

LAST_DISPATCH="$(grep -aoE 'dispatched=[0-9]+' "$LOG" | tail -1 | cut -d= -f2)"
DELTA=$(( ${LAST_DISPATCH:-0} - ${FIRST_DISPATCH:-0} ))
TOTAL=$(elapsed)

echo
echo "arm=$ARM  reached rung ${reached:-NONE}  elapsed=${TOTAL}s"
echo "ladder stopped because: $stopped_because"
echo "rung    population          dispatch            tick durations (min/med/95%ile/max ms, 10s; 1m)"
cat "$ROWS"

void() { echo "VOID RUN: $1"; exit 3; }
# Every burst is a teleport, and a teleport that did not land means the mobs were not chasing what the rungs
# say they were. Until 2026-09-18 none landed and nothing looked.
[ "$(pw_arena_teleports_landed "$LOG")" -eq "${TP_SENT:-0}" ] ||
  void "$(pw_arena_teleports_landed "$LOG") of ${TP_SENT:-0} teleports of the fake player landed"
if [ -n "$TRACE" ]; then
  # Every mark must have been accepted, every requested rung marked, and one file written per window, in order.
  want_marks="$(echo $PW_TRACE_RUNGS | wc -w)"
  [ "$(echo ${MARKED:-} | wc -w)" -eq "$want_marks" ] || void "traced rungs marked: ${MARKED:-none}, asked for: $PW_TRACE_RUNGS"
  for which in begin end; do
    [ "$(grep -ac "PWTRACE mark $which queued" "$LOG")" -eq "$want_marks" ] || void "not every trace mark $which was accepted"
  done
  grep -aq "PWTRACE mark .* refused" "$LOG" && void "a trace mark was refused"
  traces=()
  while IFS= read -r f; do traces+=("$f"); done < <(ls "$SERVER/pathweaver-trace"/trace-*.txt 2>/dev/null |
    awk -F- '{ print $NF "\t" $0 }' | sed 's/\.txt\t/\t/' | sort -n | cut -f2-)
  [ "${#traces[@]}" -eq "$want_marks" ] || void "${#traces[@]} trace files for $want_marks marked windows"
  i=0
  for r in ${MARKED}; do
    mv -f "${traces[$i]}" "$OUT/$LABEL.$r.trace.txt" || void "could not move the trace for rung $r"
    echo "  trace for rung $r: $LABEL.$r.trace.txt"
    i=$(( i + 1 ))
  done
fi
grep -aq 'Bench.*logged in with entity id' "$LOG" || void "the fake player never joined, so nothing had a target"
grep -aqiE 'Bench (was slain|died|drowned|fell)' "$LOG" &&
  void "the target died during the run, so part of the window measured mobs with no target"
[ -z "${reached:-}" ] && void "not one rung completed"
[ "$(grep -c . "$ROWS")" -lt 2 ] &&
  void "fewer than two rungs, so nothing here can show whether the harness responds to load"
grep -q 'NO TICK DURATIONS' "$ROWS" && void "a rung produced no tick distribution, which is the measurement"
case "$ARM" in
  off) grep -aqE "^\s+- (pathweaver|pathwright) [0-9]" "$LOG" &&
         void "the off arm still loaded a pathfinding mod" ;;
  *)   grep -aqE "^\s+- $ARM_ID [0-9]" "$LOG" || void "the arm jar never loaded ($ARM_ID)"
       # Only our own mod publishes a dispatch counter. A competitor has no equivalent, so there is
       # NO check here that it did any work at all, and saying so is better than letting the absence
       # of a failure read as a pass.
       if [ "$ARM_ID" = "pathweaver" ] && [ "$DELTA" -le 0 ]; then
         void "the mod loaded but dispatched nothing across the whole ladder"
       fi ;;
esac
[ "$TOTAL" -gt "$BUDGET" ] && echo "OVER BUDGET: ${TOTAL}s against a ${BUDGET}s cap."
echo "run $LABEL complete in ${TOTAL}s, dispatchDelta=$DELTA"
