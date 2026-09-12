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
set -u
SERVER="/c/Users/Administrator/AppData/Roaming/.minecraft_server"
JAVA="/c/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot/bin/java.exe"
LABEL="${1:?label}"
ARM="${2:?off or a jar path}"
shift 2
RUNGS=("$@")
[ "${#RUNGS[@]}" -gt 0 ] || { echo "give at least one rung"; exit 1; }

OUT="/c/Users/Administrator/AppData/Roaming/.minecraft/modding/PathWeaver/bench/deep"
BUDGET="${BUDGET:-300}"   # the cap, measured rather than hoped for
RESERVE="${RESERVE:-45}"  # shutdown, readout and the controls still happen inside it
BURSTS=2            # player teleports per rung: each one makes the whole population re-path at once
BURST_GAP=11        # seconds between them; spark health's 10 s window must land on a burst

START=$(date +%s)
elapsed() { echo $(( $(date +%s) - START )); }

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

# ---- the arm, and putting the server back exactly as it was found -------------------------------
HELD=""          # jar moved out of mods/ for this run
INSTALLED=""     # jar copied into mods/ for this run
restore() {
  for pid in "${TAILPID:-}" "${SERVERPID:-}"; do [ -n "$pid" ] && kill "$pid" 2>/dev/null; done
  [ -n "$INSTALLED" ] && rm -f "$SERVER/mods/$(basename "$INSTALLED")"
  if [ -n "$HELD" ] && [ -f "$SERVER/.pw-held/$(basename "$HELD")" ]; then
    mv -f "$SERVER/.pw-held/$(basename "$HELD")" "$HELD"
  fi
  rmdir "$SERVER/.pw-held" 2>/dev/null
  [ -f "$SERVER/server.properties.pristine" ] &&
    cp -f "$SERVER/server.properties.pristine" "$SERVER/server.properties"
  [ -f "$SERVER/config/pathweaver.json.pristine" ] &&
    cp -f "$SERVER/config/pathweaver.json.pristine" "$SERVER/config/pathweaver.json"
}
trap restore EXIT INT TERM HUP

# Whatever pathweaver is resident gets held aside in every arm, so the jar under test is the only one
# present and the off arm has none. Leaving the resident jar in place would have measured two.
RESIDENT="$(ls -1 "$SERVER"/mods/pathweaver-*.jar 2>/dev/null | head -1)"
if [ -n "$RESIDENT" ]; then
  mkdir -p "$SERVER/.pw-held"; mv -f "$RESIDENT" "$SERVER/.pw-held/" || exit 8
  HELD="$RESIDENT"
fi
if [ "$ARM" != "off" ]; then
  [ -f "$ARM" ] || { echo "REFUSING: no jar at $ARM"; exit 8; }
  cp -f "$ARM" "$SERVER/mods/" || exit 8
  INSTALLED="$ARM"
  ENABLED=true
else
  ENABLED=false
fi

rm -rf "$SERVER/pw-bench"; mkdir -p config
cat > config/pathweaver.json <<CFG
{"configVersion":3,"enabled":${ENABLED},"compatibilityTier":"UNSAFE","brainSinkAsync":true,"resultCacheMode":"SHADOW"}
CFG

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

echo "arm=$ARM  rungs=${RUNGS[*]}  budget=${BUDGET}s (reserve ${RESERVE}s)"

: > "$IN"; : > "$LOG"; mkdir -p "$SPARKDIR"
( tail -f "$IN" & echo $! > "$OUT/$LABEL.tailpid"; wait ) | "$JAVA" -Xmx12G -Xms4G \
    -XX:+UseG1GC -XX:+ParallelRefProcEnabled \
    -jar fabric-server-mc.26.1.2-loader.0.19.3-launcher.jar nogui >> "$LOG" 2>&1 &
SERVERPID=$!
sleep 1
TAILPID="$(cat "$OUT/$LABEL.tailpid" 2>/dev/null)"

say() { echo "$1" >> "$IN"; }
wait_for() { for _ in $(seq 1 "$2"); do grep -aq "$1" "$LOG" && return 0; sleep 1; done; return 1; }

wait_for 'Done (' 300 || { echo "SERVER NEVER STARTED"; exit 2; }
echo "  server up at $(elapsed)s"

# The same flat arena as bench/saturate.sh: empty sky so worldgen cannot vary between arms, walls so
# nothing falls out, pillars with gaps so a route is a search and not a straight line.
say "forceload add -60 -60 60 60"
say "fill -60 200 -60 60 200 60 minecraft:stone"
for y in 201 202 203; do say "fill -60 $y -60 60 $y 60 minecraft:air"; done
say "fill -60 201 -60 60 203 -60 minecraft:stone"
say "fill -60 201 60 60 203 60 minecraft:stone"
say "fill -60 201 -60 -60 203 60 minecraft:stone"
say "fill 60 201 -60 60 203 60 minecraft:stone"
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
say "gamerule maxEntityCramming 0"
say "time set midnight"
say "kill @e[type=!minecraft:player]"
sleep 3

# A player must be present: this pack ships letmedespawn, which removes mobs with no player nearby
# whatever PersistenceRequired says. The fake player is also what every zombie paths toward, and it
# is in SURVIVAL because a spectator is not a valid target and the swarm would have nothing to chase.
say "carpet commandPlayer true"
sleep 2
say "player Bench spawn at 0 201 0 facing 0 0"
wait_for 'Bench.*logged in with entity id' 60 || echo "  WARNING: fake player never joined"
say "gamemode survival Bench"
say "effect give Bench minecraft:resistance 99999 255 true"
say "effect give Bench minecraft:regeneration 99999 255 true"
sleep 2
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
  need=$(( 8 + add / 120 + BURSTS * BURST_GAP + 10 ))
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
  for _ in $(seq 1 "$BURSTS"); do
    say "tp Bench ${CORNERS[$(( corner % 4 ))]}"
    corner=$(( corner + 1 ))
    sleep "$BURST_GAP"
  done

  say "execute if entity @e[type=!minecraft:player]"
  sleep 2
  alive="$(grep -aoE 'Test passed. Count: [0-9]+' "$LOG" | tail -1 | grep -oE '[0-9]+$')"
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
grep -aq 'Bench.*logged in with entity id' "$LOG" || void "the fake player never joined, so nothing had a target"
grep -aqiE 'Bench (was slain|died|drowned|fell)' "$LOG" &&
  void "the target died during the run, so part of the window measured mobs with no target"
[ -z "${reached:-}" ] && void "not one rung completed"
[ "$(grep -c . "$ROWS")" -lt 2 ] &&
  void "fewer than two rungs, so nothing here can show whether the harness responds to load"
grep -q 'NO TICK DURATIONS' "$ROWS" && void "a rung produced no tick distribution, which is the measurement"
case "$ARM" in
  off) grep -aqE "^\s+- pathweaver [0-9]" "$LOG" && void "the off arm still loaded pathweaver" ;;
  *)   grep -aqE "^\s+- pathweaver [0-9]" "$LOG" || void "the arm jar never loaded"
       [ "$DELTA" -le 0 ] && void "the mod loaded but dispatched nothing across the whole ladder" ;;
esac
[ "$TOTAL" -gt "$BUDGET" ] && echo "OVER BUDGET: ${TOTAL}s against a ${BUDGET}s cap."
echo "run $LABEL complete in ${TOTAL}s, dispatchDelta=$DELTA"
