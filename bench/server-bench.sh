#!/usr/bin/env bash
# PathWeaver server benchmark driver.
#
# Runs the dedicated 26.1.2 pack headless on a throwaway world, builds a fixed arena, populates it
# with BRAIN mobs and real points of interest, then profiles every thread while the population
# paths. The only variable between two runs is the setting named on the command line.
#
#   bash bench/server-bench.sh <label> <outdir> <brainSinkAsync true|false> full <settle> <sample>
#
# Every number this produces is guarded by a control that can actually fail. Four separate runs were
# thrown away before these existed, each looking perfectly healthy: a paused server, mobs summoned
# inside the floor, a population that never ticked, and a profile of a single idle thread.
set -u

SERVER="/c/Users/Administrator/AppData/Roaming/.minecraft_server"
JAVA="/c/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot/bin/java.exe"
LABEL="${1:-run}"
OUT="${2:-}"
[ -z "$OUT" ] && OUT="/c/Users/Administrator/AppData/Roaming/.minecraft/modding/PathWeaver/bench/out"
BRAIN_SINK="${3:-true}"
MODE="${4:-full}"
SETTLE="${5:-90}"
SAMPLE="${6:-60}"

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
LOG="$OUT/$LABEL.log"
IN="$OUT/$LABEL.stdin"
SPARKDIR="$SERVER/config/spark"

cd "$SERVER" || exit 1

# ---------------------------------------------------------------- preserve the operator's setup
#
# Back up only from a file that is not already bench-modified. Backing up unconditionally means a
# run starting after a restore captures the modified file as if it were pristine, and the real
# settings are then one failure away from gone. Refuse rather than guess.
if grep -q '^level-name=pw-bench' server.properties 2>/dev/null; then
  if [ ! -f server.properties.pristine ]; then
    echo "REFUSING: server.properties is already bench-modified and there is no pristine backup."
    exit 6
  fi
else
  cp -f server.properties server.properties.pristine
fi
# The operator's pathweaver.json holds their pool and tolerance tuning. It was previously
# overwritten with a four-field bench document and never restored.
if [ -f config/pathweaver.json ] && [ ! -f config/pathweaver.json.pristine ]; then
  cp -f config/pathweaver.json config/pathweaver.json.pristine
fi

restore() {
  kill "${SERVERPID:-0}" 2>/dev/null
  kill "${TAILPID:-0}" 2>/dev/null
  [ -f "$SERVER/server.properties.pristine" ] &&
    cp -f "$SERVER/server.properties.pristine" "$SERVER/server.properties"
  [ -f "$SERVER/config/pathweaver.json.pristine" ] &&
    cp -f "$SERVER/config/pathweaver.json.pristine" "$SERVER/config/pathweaver.json"
}
trap restore EXIT INT TERM HUP

# ---------------------------------------------------------------- bench configuration
python - <<'PY'
import pathlib
p = pathlib.Path("server.properties")
# pause-when-empty-seconds is THE one that matters. The server stops ticking entirely once it has
# been empty for that long, and the stock value here is 60 -- exactly the old settle time, so the
# sample window opened at the moment the world froze. Three runs were lost to this before it was
# found; the symptom was the server thread parked for 54.1s of a 54.4s sample.
want = {"level-name": "pw-bench", "pause-when-empty-seconds": "0", "spawn-monsters": "false",
        "spawn-animals": "false", "spawn-npcs": "false", "view-distance": "10",
        "simulation-distance": "10", "difficulty": "peaceful", "level-seed": "20260831",
        "sync-chunk-writes": "false", "max-tick-time": "-1"}
lines, seen = [], set()
for l in p.read_text().splitlines():
    k = l.split("=", 1)[0]
    if k in want:
        lines.append(f"{k}={want[k]}"); seen.add(k)
    else:
        lines.append(l)
lines += [f"{k}={v}" for k, v in want.items() if k not in seen]
p.write_text("\n".join(lines) + "\n")
print("bench server.properties written (pause-when-empty-seconds=0)")
PY

# Gamerules and force-loads persist inside the world, so a run inherits the previous run's setup.
# Deleting the throwaway world is also what makes the two arms comparable rather than merely
# sequential.
rm -rf "$SERVER/pw-bench"

mkdir -p config
cat > config/pathweaver.json <<CFG
{"configVersion":2,"enabled":true,"compatibilityTier":"UNSAFE","brainSinkAsync":${BRAIN_SINK}}
CFG
echo "config: brainSinkAsync=${BRAIN_SINK}"

: > "$IN"
: > "$LOG"
mkdir -p "$SPARKDIR"
rm -f "$SPARKDIR"/profile-*.sparkprofile

tail -f "$IN" | "$JAVA" -Xmx12G -Xms4G -XX:+UseG1GC -XX:+ParallelRefProcEnabled \
    -jar fabric-server-mc.26.1.2-loader.0.19.3-launcher.jar nogui >> "$LOG" 2>&1 &
TAILPID=$!

say() { echo "$1" >> "$IN"; }
wait_for() {  # wait_for <pattern> <seconds>
  for _ in $(seq 1 "$2"); do grep -aq "$1" "$LOG" && return 0; sleep 1; done
  return 1
}

if ! wait_for 'Done (' 600; then
  echo "SERVER NEVER REPORTED Done() -- see $LOG"; exit 2
fi
echo "server up: $(grep -ao 'Done ([0-9.]*s)' "$LOG" | head -1)"

# ---------------------------------------------------------------- the arena
#
# Do not trust level-type. This pack ships Tectonic and Terralith, which override worldgen, so a
# "superflat" bench world is not flat and 220 mobs summoned into open air fell to y=-252 and died.
# A floor placed by /fill is the same on any pack. Each fill stays under the 32768-block limit.
say "forceload add -40 -40 40 40"
say "fill -40 -60 -40 40 -60 40 minecraft:stone"
for y in -59 -58 -57 -56; do say "fill -40 $y -40 40 $y 40 minecraft:air"; done
sleep 4

say "gamerule doMobSpawning false"
say "gamerule randomTickSpeed 0"
say "gamerule doDaylightCycle false"
say "gamerule doWeatherCycle false"
say "gamerule doFireTick false"
say "time set day"
say "kill @e[type=!minecraft:player]"
sleep 3

# ---------------------------------------------------------------- population and points of interest
#
# brainSinkAsync gates ONLY MoveToTargetSink, which is a Brain behaviour. Cows, sheep and pigs are
# goal mobs: they generate pathfinding load the setting cannot move, in both arms equally, which
# dilutes the very delta being measured. The population is brain mobs only.
#
# Beds matter as much as the mobs. A villager on a bare stone slab has no home, job site or meeting
# point, so its brain only ever idles-strolls -- the least demanding thing MoveToTargetSink does.
# Beds give it a real walk target to compute a real path to.
python - "$IN" <<'PY'
import random, sys
random.seed(20260831)              # fixed, so both arms get an identical layout
inp = open(sys.argv[1], "a")
for i in range(60):                # beds, spread across the arena, two blocks each
    x = -36 + (i % 10) * 8
    z = -36 + (i // 10) * 14
    inp.write(f"setblock {x} -59 {z} minecraft:white_bed[facing=east,part=foot]\n")
    inp.write(f"setblock {x+1} -59 {z} minecraft:white_bed[facing=east,part=head]\n")
for kind, n in (("villager", 180), ("goat", 40)):
    for _ in range(n):
        inp.write(f"summon minecraft:{kind} {random.randint(-30, 30)} -59 "
                  f"{random.randint(-30, 30)}\n")
inp.flush()
PY
echo "population summoned"
sleep "$SETTLE"

# ---------------------------------------------------------------- measurement
#
# Profile EVERY thread. spark's default samples the server thread alone, which is the one thread
# guaranteed to show less work when the mod succeeds -- the workers that receive it were never
# sampled at all, so the measurement could not distinguish "moved the work" from "removed it".
say "spark profiler cancel"
sleep 5
say "pathweaver status"
sleep 3
BEFORE_DISPATCH="$(grep -aoE 'dispatched=[0-9]+' "$LOG" | tail -1 | cut -d= -f2)"
BEFORE_ALIVE="$(grep -aoE 'Count: [0-9]+' "$LOG" | tail -1 | cut -d' ' -f2)"

say "spark profiler start --thread * --not-combined"
sleep 5

if ! wait_for "Profiler is now running" 20; then
  grep -aiE "spark|profiler" "$LOG" | tail -5
fi
sleep "$SAMPLE"
say "spark profiler stop --save-to-file"
if ! wait_for "Profiler stopped & save complete" 90; then
  echo "VOID RUN: the profiler never reported a completed save."; exit 7
fi
sleep 3
SAVED="$(ls -1t "$SPARKDIR"/*.sparkprofile 2>/dev/null | head -1)"
[ -n "$SAVED" ] && cp "$SAVED" "$OUT/$LABEL.sparkprofile"

say "execute if entity @e[type=minecraft:villager]"
sleep 2
say "pathweaver status"
sleep 5
AFTER_DISPATCH="$(grep -aoE "dispatched=[0-9]+" "$LOG" | tail -1 | cut -d= -f2)"
AFTER_ALIVE="$(grep -aoE "Count: [0-9]+" "$LOG" | tail -1 | cut -d" " -f2)"

say "stop"
sleep 45
kill "${TAILPID:-0}" 2>/dev/null

# ---------------------------------------------------------------- controls
#
# Each of these can fail, and each HAS failed on a real run. A control that cannot return ABSENT
# cannot return PRESENT, and four runs were thrown away proving that.
SUMMONED="$(grep -ac "Summoned new" "$LOG")"
DELTA=$(( ${AFTER_DISPATCH:-0} - ${BEFORE_DISPATCH:-0} ))
echo "control: summoned=$SUMMONED alive_before=${BEFORE_ALIVE:-?} alive_after=${AFTER_ALIVE:-?}"
echo "control: dispatched before=${BEFORE_DISPATCH:-?} after=${AFTER_DISPATCH:-?} delta=$DELTA"

void() {
  echo "VOID RUN: $1"
  mv -f "$OUT/$LABEL.sparkprofile" "$OUT/$LABEL.VOID.sparkprofile" 2>/dev/null
  exit 3
}

[ "$SUMMONED" -lt 200 ] && void "only $SUMMONED mobs summoned; not the population described"
[ -z "${AFTER_ALIVE:-}" ] && void "no survivor count was read, so mortality is unknown"
[ "${AFTER_ALIVE:-0}" -lt 150 ] && void "only ${AFTER_ALIVE} of 180 villagers survived; arena is wrong"
[ "$DELTA" -le 0 ] && void "nothing dispatched DURING the sample window (delta=$DELTA)"
echo "run $LABEL complete -> $OUT/$LABEL.sparkprofile"
