#!/usr/bin/env bash
# What PathWeaver does to a server that is actually struggling, in under five minutes.
#
#   bash bench/saturate.sh <label> <on|off> [mobs] [settle] [sample]
#
# WHY THIS EXISTS. Every number 0.8.0 published was measured on a server that was 87% idle: the
# route-sharing arm spent 325,588 ms of 375,804 in Unsafe.park, at 5 to 6 ms against a 50 ms budget.
# Those arms are comparable to each other and none of them tells someone whose server is stuttering
# what they would get, which is the only question that person has.
#
# THE FIVE-MINUTE CAP IS A DESIGN CONSTRAINT, NOT A PREFERENCE. Running a benchmark means holding
# every other agent off this machine. deep-bench.sh is about 5.8 minutes per run and server-bench.sh
# about 4.3, so an interleaved campaign is out of the question. This runs ONE arm and reports the
# elapsed time; if it goes over 300 s it says so, because a budget nobody measures is a wish.
#
# WHAT IT MEASURES, and why it is not just the mean. The mod's claim is fewer spikes rather than a
# higher average, so a mean on a saturated server hides exactly the thing being sold. `spark health`
# reports min/median/95th/max tick durations over the last 10 s and 1 m, which is the distribution,
# tail included. Both windows are captured.
#
# THE TARGET IS THE PLAYER, deliberately. Zombies path to a player continuously through their own
# goals, so saturation needs no retarget command and no tick-level scripting. Moving the player
# forces the whole population to re-path at once, which is the burst this mod exists for.
# PATHS COME FROM THE ENVIRONMENT. This repository is public; a hard-coded home directory
# publishes the layout of one machine and works on no other. Override PW_SERVER, PW_JAVA or
# PW_OUT to point these somewhere else.
set -u
SERVER="${PW_SERVER:-$HOME/AppData/Roaming/.minecraft_server}"
# The Adoptium patch version moves, so it is discovered rather than pinned, and a miss is fatal
# rather than a path that does not exist being handed to the launcher.
JAVA="${PW_JAVA:-$(ls -1d "/c/Program Files/Eclipse Adoptium/jdk-25"*/bin/java.exe 2>/dev/null | tail -1)}"
[ -x "$JAVA" ] || { echo "No JDK 25 found. Set PW_JAVA to a java executable." >&2; exit 7; }
LABEL="${1:-sat}"
ARM="${2:-on}"
MOBS="${3:-1024}"
SETTLE="${4:-40}"
SAMPLE="${5:-120}"
OUT="${PW_OUT:-$(cd "$(dirname "$0")/.." && pwd)/bench/deep}"
BUDGET=300
START=$(date +%s)

case "$ARM" in
  on)  ENABLED=true ;;
  off) ENABLED=false ;;   # jar removed entirely, see below
  *) echo "unknown arm: $ARM (on|off)"; exit 1 ;;
esac

mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
LOG="$OUT/$LABEL.log"; IN="$OUT/$LABEL.stdin"; SPARKDIR="$SERVER/config/spark"
cd "$SERVER" || exit 1

if grep -q '^level-name=pw-bench' server.properties 2>/dev/null; then
  [ -f server.properties.pristine ] || { echo "REFUSING: bench properties live with no pristine backup"; exit 6; }
else
  cp -f server.properties server.properties.pristine
fi
[ -f config/pathweaver.json ] && [ ! -f config/pathweaver.json.pristine ] &&
  cp -f config/pathweaver.json config/pathweaver.json.pristine

HELD_JAR=""
restore() {
  for pid in "${TAILPID:-}" "${SERVERPID:-}"; do [ -n "$pid" ] && kill "$pid" 2>/dev/null; done
  if [ -n "$HELD_JAR" ] && [ -f "$SERVER/.pw-held/$(basename "$HELD_JAR")" ]; then
    mv -f "$SERVER/.pw-held/$(basename "$HELD_JAR")" "$HELD_JAR" && rmdir "$SERVER/.pw-held" 2>/dev/null
  fi
  [ -f "$SERVER/server.properties.pristine" ] &&
    cp -f "$SERVER/server.properties.pristine" "$SERVER/server.properties"
  [ -f "$SERVER/config/pathweaver.json.pristine" ] &&
    cp -f "$SERVER/config/pathweaver.json.pristine" "$SERVER/config/pathweaver.json"
}
trap restore EXIT INT TERM HUP

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

rm -rf "$SERVER/pw-bench"; mkdir -p config
cat > config/pathweaver.json <<CFG
{"configVersion":3,"enabled":${ENABLED},"compatibilityTier":"UNSAFE","brainSinkAsync":true,"resultCacheMode":"SHADOW"}
CFG

# The OFF arm removes the jar rather than setting enabled=false. A disabled mod still routes every
# createPath through the mixin wrapper, so it prices the feature being off, not the mod being absent.
if [ "$ARM" = "off" ]; then
  HELD_JAR="$(ls -1 "$SERVER"/mods/pathweaver-*.jar 2>/dev/null | head -1)"
  [ -z "$HELD_JAR" ] && { echo "REFUSING: no pathweaver jar to remove"; exit 8; }
  mkdir -p "$SERVER/.pw-held"; mv -f "$HELD_JAR" "$SERVER/.pw-held/" || exit 8
  echo "off arm: held $(basename "$HELD_JAR") out of mods/"
fi
echo "arm=$ARM mobs=$MOBS settle=${SETTLE}s sample=${SAMPLE}s  budget=${BUDGET}s"

: > "$IN"; : > "$LOG"; mkdir -p "$SPARKDIR"; rm -f "$SPARKDIR"/profile-*.sparkprofile
( tail -f "$IN" & echo $! > "$OUT/$LABEL.tailpid"; wait ) | "$JAVA" -Xmx12G -Xms4G \
    -XX:+UseG1GC -XX:+ParallelRefProcEnabled \
    -jar fabric-server-mc.26.1.2-loader.0.19.3-launcher.jar nogui >> "$LOG" 2>&1 &
SERVERPID=$!
sleep 1
TAILPID="$(cat "$OUT/$LABEL.tailpid" 2>/dev/null)"

say() { echo "$1" >> "$IN"; }
wait_for() { for _ in $(seq 1 "$2"); do grep -aq "$1" "$LOG" && return 0; sleep 1; done; return 1; }
elapsed() { echo $(( $(date +%s) - START )); }

wait_for 'Done (' 300 || { echo "SERVER NEVER STARTED"; exit 2; }
echo "  server up at $(elapsed)s"

# Flat arena in empty sky, so worldgen cannot vary it between arms and nothing falls out of the world.
say "forceload add -60 -60 60 60"
say "fill -60 200 -60 60 200 60 minecraft:stone"
for y in 201 202 203; do say "fill -60 $y -60 60 $y 60 minecraft:air"; done
say "fill -60 201 -60 60 203 -60 minecraft:stone"
say "fill -60 201 60 60 203 60 minecraft:stone"
say "fill -60 201 -60 -60 203 60 minecraft:stone"
say "fill 60 201 -60 60 203 60 minecraft:stone"
# Pillars, so a route is a search rather than a straight line. Spaced 8 apart with 2-wide gaps.
for x in -40 -24 -8 8 24 40; do say "fill $x 201 -56 $x 203 56 minecraft:stone"; done
for x in -40 -24 -8 8 24 40; do say "fill $x 201 -2 $x 203 2 minecraft:air"; done
sleep 5
say "gamerule doMobSpawning false"
say "gamerule randomTickSpeed 0"
say "gamerule doDaylightCycle false"
say "gamerule doWeatherCycle false"
say "gamerule doFireTick false"
say "gamerule doMobLoot false"
say "gamerule doImmediateRespawn true"
say "time set midnight"
say "kill @e[type=!minecraft:player]"
sleep 3

# A player must be present: this pack ships letmedespawn, and it removes mobs with no player near
# regardless of PersistenceRequired. The fake player is also the thing every zombie paths toward.
say "carpet commandPlayer true"
sleep 2
say "player Bench spawn at 0 201 0 facing 0 0"
wait_for 'Bench.*logged in with entity id' 60 || echo "  WARNING: fake player never joined"
# SURVIVAL, not spectator. A spectator is not a valid target for a hostile mob, so 1024 zombies
# would have had nothing to path towards and the saturating benchmark would have saturated nothing
# while looking perfectly healthy. Resistance 255 keeps it alive under a swarm it cannot escape;
# the point is the pathfinding load, not a fight anyone wins.
say "gamemode survival Bench"
say "effect give Bench minecraft:resistance 99999 255 true"
say "effect give Bench minecraft:regeneration 99999 255 true"
sleep 2
echo "  arena ready at $(elapsed)s"

# Zombies, spread across the corridors. Batched through one stdin write: 1024 separate command
# round-trips is slower than the tick budget this run is trying to measure.
python - "$IN" "$MOBS" <<'PY'
import random, sys
inp, mobs = sys.argv[1], int(sys.argv[2])
random.seed(20260907)
lanes = [-52, -46, -32, -16, 0, 16, 32, 46, 52]
with open(inp, "a") as f:
    for _ in range(mobs):
        x = random.choice(lanes) + random.randint(-3, 3)
        z = random.randint(-56, 56)
        f.write(f"summon minecraft:zombie {x} 201 {z} "
                '{PersistenceRequired:1b,IsBaby:0b,CanPickUpLoot:0b,'
                'Attributes:[{id:"minecraft:follow_range",base:128}]}\n')
PY
sleep 8
echo "  population summoned at $(elapsed)s"
sleep "$SETTLE"

# Count what is actually alive, and never a selector whose noun has not been checked. This pack
# replaces some vanilla types, so the count is of non-player entities rather than of a named type.
say "execute if entity @e[type=!minecraft:player]"
sleep 2
BEFORE_ALIVE="$(grep -aoE 'Test passed. Count: [0-9]+' "$LOG" | tail -1 | grep -oE '[0-9]+$')"
say "pathweaver status"
sleep 2
BEFORE_DISPATCH="$(grep -aoE 'dispatched=[0-9]+' "$LOG" | tail -1 | cut -d= -f2)"

echo "  measuring from $(elapsed)s, ${BEFORE_ALIVE:-?} entities alive"
say "spark profiler start --thread * --not-combined"
# Move the target every 15 s. Every zombie re-paths at once, which is the burst being measured
# rather than a steady state that any server can absorb.
CORNERS=("-50 201 -50" "50 201 -50" "50 201 50" "-50 201 50")
STOP=$(( $(date +%s) + SAMPLE ))
i=0
while [ "$(date +%s)" -lt "$STOP" ]; do
  say "tp Bench ${CORNERS[$(( i % 4 ))]}"
  i=$(( i + 1 ))
  sleep 15
done
say "spark profiler stop --save-to-file"
wait_for 'Profiler stopped & save complete' 90 || echo "  WARNING: no profile saved"
sleep 2
SAVED="$(ls -1t "$SPARKDIR"/*.sparkprofile 2>/dev/null | head -1)"
[ -n "$SAVED" ] && cp "$SAVED" "$OUT/$LABEL.sparkprofile"

# The distribution, not just the mean. This is the line the whole run exists to produce.
say "spark health"
sleep 3
say "execute if entity @e[type=!minecraft:player]"
sleep 2
AFTER_ALIVE="$(grep -aoE 'Test passed. Count: [0-9]+' "$LOG" | tail -1 | grep -oE '[0-9]+$')"
say "pathweaver status"
sleep 2
AFTER_DISPATCH="$(grep -aoE 'dispatched=[0-9]+' "$LOG" | tail -1 | cut -d= -f2)"

say "stop"
wait_for 'ThreadedAnvilChunkStorage: All dimensions are saved' 60
sleep 2
for pid in "${TAILPID:-}" "${SERVERPID:-}"; do [ -n "$pid" ] && kill "$pid" 2>/dev/null; done

# -A1, not -A2. spark prints the values on the line AFTER the heading and a blank line after that,
# so -A2 | tail -1 returns the blank and the run voids on "no tick durations" with the numbers
# sitting right there in the log. Checked against a real health block before this ran once.
TICKS="$(grep -aA1 'Tick durations' "$LOG" | tail -1 | sed 's/^[^0-9]*//')"
DELTA=$(( ${AFTER_DISPATCH:-0} - ${BEFORE_DISPATCH:-0} ))
TOTAL=$(elapsed)
echo
echo "control: arm=$ARM alive ${BEFORE_ALIVE:-?} -> ${AFTER_ALIVE:-?} dispatchDelta=$DELTA elapsed=${TOTAL}s"
echo "tick durations (min/med/95%ile/max ms, last 10s; last 1m): ${TICKS:-NOT REPORTED}"

void() { echo "VOID RUN: $1"; mv -f "$OUT/$LABEL.sparkprofile" "$OUT/$LABEL.VOID.sparkprofile" 2>/dev/null; exit 3; }
grep -aq 'Bench.*logged in with entity id' "$LOG" || void "the fake player never joined, so nothing had a target"
# If the target died the population spent part of the window with nothing to path to, and the tick
# numbers describe a quieter server than the one being claimed.
grep -aqiE 'Bench (was slain|died|drowned|fell)' "$LOG" &&
  void "the target died during the run, so part of the window measured mobs with no target"
[ -z "${BEFORE_ALIVE:-}" ] && void "no population count before the window"
[ "${BEFORE_ALIVE:-0}" -lt $(( MOBS * 8 / 10 )) ] &&
  void "only ${BEFORE_ALIVE:-0} of $MOBS alive when the window opened"
[ -z "${AFTER_ALIVE:-}" ] && void "no survivor count"
[ "$(( AFTER_ALIVE * 100 / ${BEFORE_ALIVE:-1} ))" -lt 85 ] &&
  void "population fell from $BEFORE_ALIVE to $AFTER_ALIVE during the window"
[ -z "${TICKS:-}" ] && void "spark health reported no tick durations, which is the measurement"
case "$ARM" in
  on)  [ "$DELTA" -le 0 ] && void "the mod is installed and enabled but dispatched nothing" ;;
  off) grep -aqE "^\s+- pathweaver [0-9]" "$LOG" && void "the off arm still loaded pathweaver" ;;
esac
[ "$TOTAL" -gt "$BUDGET" ] &&
  echo "OVER BUDGET: ${TOTAL}s against a ${BUDGET}s cap. Trim SAMPLE before running the other arm."
echo "run $LABEL ($ARM) complete in ${TOTAL}s"
