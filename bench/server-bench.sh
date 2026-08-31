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
say "fill -40 200 -40 40 200 40 minecraft:stone"
for y in 201 202 203 204; do say "fill -40 $y -40 40 $y 40 minecraft:air"; done

# WALLS, for two reasons.
#
# Containment: on an open slab the villagers wander off the edge and fall. A run that looked healthy
# ended with 117 of 180 alive, and the survivor control is only meaningful once they cannot leave.
#
# And cost: on a flat empty floor A* is trivial. The first controlled run dispatched 3204 searches,
# 3199 of them the brain sink, and all eight workers together accounted for 412 ms -- there was
# nothing worth moving off the server thread, so the scenario could not have shown a gain whatever
# the setting did. A serpentine of internal walls forces long detours, which is what makes the
# search expensive enough to be worth measuring.
say "fill -40 201 -40 40 203 -40 minecraft:stone"
say "fill -40 201 40 40 203 40 minecraft:stone"
say "fill -40 201 -40 -40 203 40 minecraft:stone"
say "fill 40 201 -40 40 203 40 minecraft:stone"
for x in -24 -8 8 24; do
  say "fill $x 201 -38 $x 203 24 minecraft:stone"
done
for x in -16 0 16; do
  say "fill $x 201 -24 $x 203 38 minecraft:stone"
done
sleep 6

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
    inp.write(f"setblock {x} 201 {z} minecraft:white_bed[facing=east,part=foot]\n")
    inp.write(f"setblock {x+1} 201 {z} minecraft:white_bed[facing=east,part=head]\n")
# Walls stand on x = -24,-16,-8,0,8,16,24. A mob summoned on one of those lines is buried in it,
# so place them in corridor centres derived from the wall positions rather than listed beside them.
WALLS = (-24, -16, -8, 0, 8, 16, 24)
CORRIDORS = [x for x in range(-34, 35) if all(abs(x - w) > 2 for w in WALLS)]
for kind, n in (("villager", 140), ("goat", 30)):
    for _ in range(n):
        inp.write(f"summon minecraft:{kind} {random.choice(CORRIDORS)} 201 "
                  f"{random.randint(-34, 34)}\n")
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
# Count the population with the SAME command on both sides of the window. The earlier version read
# a "Count:" that no villager command had produced -- it picked up another mod's output and reported
# 192 villagers where 140 had been summoned. A number whose noun you have not checked is not evidence.
say "execute if entity @e[type=minecraft:villager]"
sleep 2
say "pathweaver status"
sleep 3
BEFORE_DISPATCH="$(grep -aoE 'dispatched=[0-9]+' "$LOG" | tail -1 | cut -d= -f2)"
BEFORE_ALIVE="$(grep -aoE 'Test passed. Count: [0-9]+' "$LOG" | tail -1 | grep -oE '[0-9]+$')"

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
AFTER_ALIVE="$(grep -aoE "Test passed. Count: [0-9]+" "$LOG" | tail -1 | grep -oE "[0-9]+$")"

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

[ "$SUMMONED" -lt 165 ] && void "only $SUMMONED mobs summoned; not the population described"
[ -z "${BEFORE_ALIVE:-}" ] || [ -z "${AFTER_ALIVE:-}" ] &&
  void "the population was not counted on both sides of the window, so stability is unknown"
[ "${BEFORE_ALIVE:-0}" -lt 80 ] &&
  void "only ${BEFORE_ALIVE} villagers were alive when the window opened; too few to measure"
if [ "$(( AFTER_ALIVE * 100 / BEFORE_ALIVE ))" -lt 90 ]; then
  void "the population fell from $BEFORE_ALIVE to $AFTER_ALIVE DURING the window; the arms are not comparable"
fi
# The dispatch control runs in BOTH directions, because the two arms expect opposite things.
#
# With the brain sink on, these mobs are villagers and goats and every one of their walk targets goes
# through MoveToTargetSink, so the window must show dispatches or the feature did not run. With it
# off, that route must dispatch nothing -- so a non-trivial count would mean the setting did not take
# effect and the two arms are not actually different. Asserting only the first would have let a
# silently-ignored setting through as a clean result.
if [ "$BRAIN_SINK" = "true" ]; then
  [ "$DELTA" -le 0 ] && void "brain sink is ON but nothing dispatched during the window (delta=$DELTA)"
else
  [ "$DELTA" -gt 20 ] && void "brain sink is OFF but $DELTA searches dispatched; the setting did not take effect"
fi
echo "run $LABEL complete -> $OUT/$LABEL.sparkprofile"
