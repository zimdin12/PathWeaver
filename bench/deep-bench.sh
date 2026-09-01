#!/usr/bin/env bash
# PathWeaver deep benchmark: three arms, every evaluator family, tick distribution, and a log scan.
#
#   bash bench/deep-bench.sh <label> <arm> [settle] [sample]
#
# ARMS, and why there are three rather than two:
#   off    enabled=false                      the mod does nothing. The baseline a regression is
#                                             measured against, because "slower than 0.7" and
#                                             "slower than not installed" are different questions.
#   sync   enabled=true, brainSinkAsync=false  what 0.7.0 does: everything except brain mobs.
#   async  enabled=true, brainSinkAsync=true   what 0.8.0 ships.
#
# The population covers every evaluator family the mod claims to touch, not just the brain route,
# because the master switch affects all six and a regression could land anywhere. Families are
# separated into their own corridors so hostile mobs cannot kill the population being counted.
set -u

SERVER="/c/Users/Administrator/AppData/Roaming/.minecraft_server"
JAVA="/c/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot/bin/java.exe"
LABEL="${1:-run}"
ARM="${2:-async}"
SETTLE="${3:-120}"
SAMPLE="${4:-120}"
OUT="/c/Users/Administrator/AppData/Roaming/.minecraft/modding/PathWeaver/bench/deep"

case "$ARM" in
  off)   ENABLED=false; SINK=false ;;
  sync)  ENABLED=true;  SINK=false ;;
  async) ENABLED=true;  SINK=true  ;;
  *) echo "unknown arm: $ARM (off|sync|async)"; exit 1 ;;
esac

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
LOG="$OUT/$LABEL.log"
IN="$OUT/$LABEL.stdin"
SPARKDIR="$SERVER/config/spark"

cd "$SERVER" || exit 1

if grep -q '^level-name=pw-bench' server.properties 2>/dev/null; then
  [ -f server.properties.pristine ] || { echo "REFUSING: bench properties live with no pristine backup"; exit 6; }
else
  cp -f server.properties server.properties.pristine
fi
[ -f config/pathweaver.json ] && [ ! -f config/pathweaver.json.pristine ] &&
  cp -f config/pathweaver.json config/pathweaver.json.pristine

restore() {
  [ -n "${TAILPID:-}" ] && kill "$TAILPID" 2>/dev/null
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

rm -rf "$SERVER/pw-bench"
mkdir -p config
cat > config/pathweaver.json <<CFG
{"configVersion":2,"enabled":${ENABLED},"compatibilityTier":"UNSAFE","brainSinkAsync":${SINK}}
CFG
echo "arm=$ARM enabled=$ENABLED brainSinkAsync=$SINK"

: > "$IN"; : > "$LOG"; mkdir -p "$SPARKDIR"; rm -f "$SPARKDIR"/profile-*.sparkprofile
tail -f "$IN" | "$JAVA" -Xmx12G -Xms4G -XX:+UseG1GC -XX:+ParallelRefProcEnabled \
    -jar fabric-server-mc.26.1.2-loader.0.19.3-launcher.jar nogui >> "$LOG" 2>&1 &
TAILPID=$!

say() { echo "$1" >> "$IN"; }
wait_for() { for _ in $(seq 1 "$2"); do grep -aq "$1" "$LOG" && return 0; sleep 1; done; return 1; }

wait_for 'Done (' 600 || { echo "SERVER NEVER STARTED"; exit 2; }
echo "server up: $(grep -ao 'Done ([0-9.]*s)' "$LOG" | head -1)"

# ---- arena in empty sky, so worldgen cannot vary it between arms
say "forceload add -40 -40 40 40"
say "fill -40 200 -40 40 200 40 minecraft:stone"
for y in 201 202 203 204 205; do say "fill -40 $y -40 40 $y 40 minecraft:air"; done
say "fill -40 201 -40 40 204 -40 minecraft:stone"
say "fill -40 201 40 40 204 40 minecraft:stone"
say "fill -40 201 -40 -40 204 40 minecraft:stone"
say "fill 40 201 -40 40 204 40 minecraft:stone"
for x in -24 -16 -8 0 8 16 24; do say "fill $x 201 -38 $x 204 38 minecraft:stone"; done
# gaps, so the corridors connect and a walk is a real search rather than a straight line
for x in -24 -16 -8 0 8 16 24; do say "fill $x 201 -4 $x 202 4 minecraft:air"; done
# water for the swim and amphibious families
say "fill 34 200 -34 38 201 -20 minecraft:water"
sleep 6

say "gamerule doMobSpawning false"
say "gamerule randomTickSpeed 0"
say "gamerule doDaylightCycle false"
say "gamerule doWeatherCycle false"
say "gamerule doFireTick false"
say "gamerule doMobLoot false"
say "time set day"
say "kill @e[type=!minecraft:player]"
sleep 3

# A PLAYER MUST BE PRESENT, or the population is deleted before it is measured.
#
# This pack ships letmedespawn, whose whole job is removing mobs with no player nearby, and it does
# not care that they were summoned with PersistenceRequired. Six runs of a three-arm campaign voided
# on "no survivor count": 60 villagers summoned, zero deaths logged, and zero alive by the time the
# window opened. An earlier run with a 90-second settle lost 38% the same way and still measured,
# which is exactly the kind of partial loss that looks like noise instead of a broken scenario.
#
# Carpet's fake player is a real ServerPlayer to every mod that asks "is a player near", so the
# population survives. Ticking is handled separately by pause-when-empty-seconds=0.
say "carpet commandPlayer true"
sleep 2
# One fake player, because a real server has one and some mods behave differently without.
#
# NOT for despawn reasons. An earlier version of this file claimed four players were needed to keep
# mobs inside letmedespawn's range, and that was a wrong diagnosis built on a broken control: the
# population was never dying. See the counting note below.
say "player Bench spawn at 0 202 0 facing 0 0 in minecraft:overworld"
sleep 3

# ---- one family per corridor, so a hostile family cannot eat the population being counted
python - "$IN" <<'PY'
import random, sys
random.seed(20260831)
inp = open(sys.argv[1], "a")

def fill(kind, n, x0, x1, z0=-34, z1=34, extra="{PersistenceRequired:1b}"):
    for _ in range(n):
        x = random.randint(x0, x1); z = random.randint(z0, z1)
        inp.write(f"summon minecraft:{kind} {x} 201 {z} {extra}\n")

# beds give villagers a real destination rather than idle strolling
for i in range(40):
    x = -36 + (i % 8) * 2
    z = -30 + (i // 8) * 14
    inp.write(f"setblock {x} 201 {z} minecraft:white_bed[facing=east,part=foot]\n")
    inp.write(f"setblock {x+1} 201 {z} minecraft:white_bed[facing=east,part=head]\n")

fill("villager", 60, -38, -26)   # brain + walk: the brainSinkAsync route
fill("goat",     20, -22, -18)   # brain
fill("frog",     20, -14, -10)   # brain + the frog evaluator
fill("cow",      30,  -6,  -2)   # goal-driven walk
fill("bee",      30,   2,   6)   # fly evaluator
fill("spider",   20,  10,  14)   # wall-climber navigation, sealed away from the villagers
fill("squid",    10,  35,  37, -33, -21)   # swim evaluator
fill("axolotl",  10,  35,  37, -33, -21)   # amphibious evaluator
inp.flush()
PY
echo "population summoned"
sleep "$SETTLE"

# ---- measurement
say "spark profiler cancel"
sleep 5
say "spark health"
# Count EVERY non-player entity, not minecraft:villager.
#
# This pack ships MCA, which replaces vanilla villagers with its own entity type, so a villager
# selector correctly returns zero on a fully healthy arena. That zero was read as "the population
# died" and produced two rounds of fixes for a problem that did not exist: a despawn theory, then a
# distance theory, then four fake players. The mobs were alive and dispatching the whole time.
#
# A zero is only evidence when the probe has been shown capable of returning something else, and this
# one never was. The control below now requires a non-trivial count before the window opens, so the
# instrument has to prove it can see the population before any run is trusted.
say "execute if entity @e[type=!minecraft:player]"
sleep 3
say "pathweaver status"
sleep 3
BEFORE_DISPATCH="$(grep -aoE 'dispatched=[0-9]+' "$LOG" | tail -1 | cut -d= -f2)"
BEFORE_ALIVE="$(grep -aoE 'Test passed. Count: [0-9]+' "$LOG" | tail -1 | grep -oE '[0-9]+$')"

say "spark profiler start --thread * --not-combined"
sleep 5
sleep "$SAMPLE"
say "spark profiler stop --save-to-file"
wait_for 'Profiler stopped & save complete' 120 || { echo "VOID: no profile saved"; exit 7; }
sleep 3
SAVED="$(ls -1t "$SPARKDIR"/*.sparkprofile 2>/dev/null | head -1)"
[ -n "$SAVED" ] && cp "$SAVED" "$OUT/$LABEL.sparkprofile"

say "spark health"
# Count EVERY non-player entity, not minecraft:villager.
#
# This pack ships MCA, which replaces vanilla villagers with its own entity type, so a villager
# selector correctly returns zero on a fully healthy arena. That zero was read as "the population
# died" and produced two rounds of fixes for a problem that did not exist: a despawn theory, then a
# distance theory, then four fake players. The mobs were alive and dispatching the whole time.
#
# A zero is only evidence when the probe has been shown capable of returning something else, and this
# one never was. The control below now requires a non-trivial count before the window opens, so the
# instrument has to prove it can see the population before any run is trusted.
say "execute if entity @e[type=!minecraft:player]"
sleep 3
say "pathweaver status"
sleep 5
AFTER_DISPATCH="$(grep -aoE 'dispatched=[0-9]+' "$LOG" | tail -1 | cut -d= -f2)"
AFTER_ALIVE="$(grep -aoE 'Test passed. Count: [0-9]+' "$LOG" | tail -1 | grep -oE '[0-9]+$')"

say "stop"
sleep 50
[ -n "${TAILPID:-}" ] && kill "$TAILPID" 2>/dev/null

SUMMONED="$(grep -ac 'Summoned new' "$LOG")"
DELTA=$(( ${AFTER_DISPATCH:-0} - ${BEFORE_DISPATCH:-0} ))
echo "control: arm=$ARM summoned=$SUMMONED alive ${BEFORE_ALIVE:-?} -> ${AFTER_ALIVE:-?} dispatchDelta=$DELTA"

void() { echo "VOID RUN: $1"; mv -f "$OUT/$LABEL.sparkprofile" "$OUT/$LABEL.VOID.sparkprofile" 2>/dev/null; exit 3; }
[ "$SUMMONED" -lt 190 ] && void "only $SUMMONED mobs summoned"
grep -aq 'Bench.*logged in with entity id' "$LOG" || void "the fake player never joined"
[ "${BEFORE_ALIVE:-0}" -lt 150 ] &&
  void "only ${BEFORE_ALIVE:-0} entities were alive when the window opened, of 200 summoned; the "       "counting probe cannot be trusted to report a real population"
[ -z "${AFTER_ALIVE:-}" ] && void "no survivor count read"
[ "$(( AFTER_ALIVE * 100 / ${BEFORE_ALIVE:-1} ))" -lt 90 ] &&
  void "population fell from $BEFORE_ALIVE to $AFTER_ALIVE during the window"
case "$ARM" in
  off)   [ "$DELTA" -gt 5 ]  && void "the mod is disabled but dispatched $DELTA searches" ;;
  async) [ "$DELTA" -le 0 ]  && void "brain sink on but nothing dispatched in the window" ;;
esac
echo "run $LABEL ($ARM) complete"
