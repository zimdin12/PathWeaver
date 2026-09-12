#!/usr/bin/env bash
# Does fluid simulation cost enough to be worth a mod? One boot, a ladder of dam breaks.
#
#   bash bench/fluid-ladder.sh <label> <rung-edge> [rung-edge...]
#
# WHY. The roadmap proposes a fluid-performance mod on the strength of a guess, and the pathfinding
# figure in docs/PERFORMANCE-2026-09.md is only credible because it was measured rather than assumed.
# The arena that produced it had no water in it at all, so there is currently NO number for fluid
# ticking, and the honest next step is to get one before committing to anything.
#
# WHAT IS MEASURED. Vanilla water settles: once it has spread, it stops ticking, and a still pool
# costs nothing. The cost is the SPREAD. So each rung releases a wall of water of a given edge length
# and samples the tick distribution while it is moving, which is the dam break a player actually feels.
#
# Rung N releases an N x 4 x N volume, so the work grows roughly with N squared rather than N.
#
# WHAT THIS CANNOT SETTLE. A finite-fluid mod like Flowing Fluids changes the rules: water that never
# fully settles ticks forever, which is a different and probably larger cost than vanilla's. This
# measures vanilla. If vanilla's spread is already expensive then finite fluids are certainly worse,
# which is the direction that matters for the decision.
#
# PATHS COME FROM THE ENVIRONMENT, as with every script here. PW_SERVER, PW_JAVA.
set -u
# Hold and restore live in one place; see bench/lib/hold.sh for the bug that made that matter.
. "$(cd "$(dirname "$0")" && pwd)/lib/hold.sh"
SERVER="${PW_SERVER:-$HOME/AppData/Roaming/.minecraft_server}"
JAVA="${PW_JAVA:-$(ls -1d "/c/Program Files/Eclipse Adoptium/jdk-25"*/bin/java.exe 2>/dev/null | tail -1)}"
[ -x "$JAVA" ] || { echo "No JDK 25 found. Set PW_JAVA." >&2; exit 7; }
LABEL="${1:?label}"
shift
RUNGS=("$@")
[ "${#RUNGS[@]}" -gt 0 ] || { echo "give at least one rung edge, e.g. 16 32 48 64"; exit 1; }

OUT="${PW_OUT:-$(cd "$(dirname "$0")/.." && pwd)/bench/deep}"
BUDGET="${BUDGET:-600}"
RESERVE="${RESERVE:-90}"
SETTLE="${SETTLE:-14}"      # seconds of spreading sampled per rung; spark health reads the last 10 s

START=$(date +%s)
elapsed() { echo $(( $(date +%s) - START )); }

mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
LOG="$OUT/$LABEL.log"; IN="$OUT/$LABEL.stdin"
cd "$SERVER" || exit 1

if grep -q '^level-name=pw-bench' server.properties 2>/dev/null; then
  [ -f server.properties.pristine ] || { echo "REFUSING: bench properties live with no pristine backup"; exit 6; }
else
  cp -f server.properties server.properties.pristine
fi

# Every pathfinding mod is held aside. This measures fluids, and a mob mod in the mix would put its
# own cost in the denominator for no reason.
restore() {
  for pid in "${TAILPID:-}" "${SERVERPID:-}"; do [ -n "$pid" ] && kill "$pid" 2>/dev/null; done
  pw_hold_restore
  [ -f "$SERVER/server.properties.pristine" ] &&
    cp -f "$SERVER/server.properties.pristine" "$SERVER/server.properties"
}
trap restore EXIT INT TERM HUP
pw_hold_init "$SERVER" || exit 8
pw_hold_residents || exit 8

rm -rf "$SERVER/pw-bench"
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

echo "fluid ladder, edges ${RUNGS[*]}, budget ${BUDGET}s"
: > "$IN"; : > "$LOG"
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

# A big flat basin in empty sky. Water released at the top spreads across it and down the sides, and
# nothing else in the world is doing anything.
say "forceload add -120 -120 120 120"
say "gamerule doMobSpawning false"
say "gamerule randomTickSpeed 0"
say "gamerule doDaylightCycle false"
say "gamerule doWeatherCycle false"
say "gamerule doFireTick false"
say "time set noon"
# EVERY FILL STAYS UNDER 32768 BLOCKS, which is vanilla's limit for one fill command. The first
# version of this used 201x1x201 = 40401 and the command simply failed: no basin was built, the water
# landed on natural terrain, and the run still produced a tidy table of numbers describing an arena
# that did not exist. 128x1x128 = 16384 per fill, well inside it.
say "fill -63 200 -63 64 200 64 minecraft:stone"
for y in 201 202 203 204 205 206 207; do say "fill -63 $y -63 64 $y 64 minecraft:air"; done
sleep 6
# The arena is then CHECKED rather than assumed. A silently failed fill is exactly the wrong zero this
# project keeps finding, and it costs one command to refuse instead.
say "execute if block 0 200 0 minecraft:stone"
sleep 2
if ! grep -aq "Test passed" "$LOG"; then
  echo "ARENA NOT BUILT: the floor fill did not take, so nothing below would describe this basin" >&2
  say "stop"; sleep 5
  for pid in "${TAILPID:-}" "${SERVERPID:-}"; do [ -n "$pid" ] && kill "$pid" 2>/dev/null; done
  exit 4
fi
echo "  basin ready and verified at $(elapsed)s"

ROWS="$OUT/$LABEL.rungs.txt"; : > "$ROWS"
reached=""
stopped_because="completed every rung"

for edge in "${RUNGS[@]}"; do
  need=$(( SETTLE + 14 ))
  left=$(( BUDGET - RESERVE - $(elapsed) ))
  if [ "$need" -gt "$left" ]; then
    stopped_because="edge $edge needs about ${need}s and only ${left}s remained"
    break
  fi

  half=$(( edge / 2 ))
  # Clear the previous rung's water, then release a fresh volume. Clearing is done before the timer so
  # its own cost is not inside the measured window.
  # Clearing reports how many blocks it changed, which is the count of water that actually existed at
  # the end of the previous rung. Without it, "fluids are cheap" cannot be told apart from "the fill
  # did nothing and there was never any water".
  for y in 201 202 203 204 205 206 207; do
    say "fill -63 $y -63 64 $y 64 minecraft:air replace minecraft:water"
  done
  sleep 4
  cleared="$(grep -aoE 'Changed [0-9]+ block' "$LOG" | tail -1 | grep -oE '[0-9]+')"
  say "fill -$half 205 -$half $half 207 $half minecraft:water"
  # Sample WHILE it spreads. A settled pool does not tick, so a window taken after it stops would
  # measure nothing and read as "fluids are free".
  sleep "$SETTLE"
  say "spark health"
  sleep 3
  ticks="$(grep -aA1 'Tick durations' "$LOG" | tail -1 | sed 's/^[^0-9]*//')"

  printf '%4s  volume=%-9s at=%3ss  %s\n' \
    "$edge" "$(( edge * edge * 3 ))" "$(elapsed)" "${ticks:-NO TICK DURATIONS}" >> "$ROWS"
  reached="$edge"
done

say "stop"
wait_for 'ThreadedAnvilChunkStorage: All dimensions are saved' 90
sleep 2
for pid in "${TAILPID:-}" "${SERVERPID:-}"; do [ -n "$pid" ] && kill "$pid" 2>/dev/null; done

echo
echo "reached edge ${reached:-NONE}, elapsed $(elapsed)s"
echo "stopped because: $stopped_because"
echo "edge  placed  waterAtPrevEnd  tick durations (min/med/95%ile/max ms, 10s; 1m)"
cat "$ROWS"

void() { echo "VOID RUN: $1"; exit 3; }
[ -z "${reached:-}" ] && void "not one rung completed"
[ "$(grep -c . "$ROWS")" -lt 2 ] && void "fewer than two rungs, so nothing shows a response to load"
grep -q 'NO TICK DURATIONS' "$ROWS" && void "a rung produced no tick distribution, which is the measurement"
grep -aqE "^\s+- (pathweaver|pathwright) [0-9]" "$LOG" && void "a pathfinding mod was loaded during a fluid measurement"
echo "run $LABEL complete in $(elapsed)s"
