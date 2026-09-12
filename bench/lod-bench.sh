#!/usr/bin/env bash
# One boot, one distant herd, terrain changing under it or not, LOD on or off. Measures what distance
# LOD actually removes.
#
#   bash bench/lod-bench.sh <label> <jar> <async:true|false> <lod:on|off> <churn:on|off>
#
# WHY bench/ladder.sh CANNOT MEASURE THIS. The ladder drives load by teleporting the player, which gives
# every mob a new destination. New destinations go moveTo -> createPath and never reach
# PathNavigation.recomputePath, which is the only method LOD touches. Run LOD through the ladder and it
# reports no difference, correctly, about a workload LOD was never going to see.
#
# WHAT DOES REACH IT, read from the 26.1.2 bytecode. recomputePath has two callers in the whole game:
# ServerLevel.sendBlockUpdated, for navigations whose route is near a changed block, and
# PathNavigation.tick, retrying a call that was deferred. So the scenario is a herd far from the player
# with blocks changing among it.
#
# THE CHURN HAS TO CHANGE A COLLISION SHAPE. sendBlockUpdated returns before it looks at a single
# navigation unless Shapes.joinIsNotEmpty(oldCollision, newCollision, NOT_SAME). The first design toggled
# light blocks, which have no collision either way, and would have measured a scenario in which
# recomputePath is never called and reported that LOD does nothing. Carpet has a 1/16 collision box and
# mobs walk over it, so it triggers the loop without blocking a route.
#
# CHURN OFF IS NOT "NO COMMAND BLOCKS". The same chain runs in both, and the off arm's fills replace a
# block that is not there, so the command execution and the region scan are identical and only the
# block changes differ.
#
# ASYNC FALSE IS AN INSTRUMENT, NOT A RECOMMENDATION. With the mod's async path off, every recompute
# search runs on the server thread, where spark's call tree attributes it to recomputePath. With it on,
# the search runs on a worker and the server thread only sees dispatch. Both are measured, and they
# answer different questions: false says how much search LOD removes, true says what a user of the
# shipped configuration gets.
#
# PATHS COME FROM THE ENVIRONMENT, as in the other scripts: PW_SERVER, PW_JAVA, PW_OUT.
set -u
. "$(cd "$(dirname "$0")" && pwd)/lib/hold.sh"
SERVER="${PW_SERVER:-$HOME/AppData/Roaming/.minecraft_server}"
JAVA="${PW_JAVA:-$(ls -1d "/c/Program Files/Eclipse Adoptium/jdk-25"*/bin/java.exe 2>/dev/null | tail -1)}"
[ -x "$JAVA" ] || { echo "No JDK 25 found. Set PW_JAVA to a java executable." >&2; exit 7; }

LABEL="${1:?label}"; JAR="${2:?jar}"; ASYNC="${3:?async true|false}"; LOD="${4:?lod on|off}"; CHURN="${5:?churn on|off}"
case "$ASYNC" in true|false) ;; *) echo "async must be true or false"; exit 1;; esac
case "$LOD" in on) LOD_JSON=true;; off) LOD_JSON=false;; *) echo "lod must be on or off"; exit 1;; esac
case "$CHURN" in on) PLACED="minecraft:air"; SWAP="minecraft:white_carpet";;
                 off) PLACED="minecraft:bedrock"; SWAP="minecraft:bedrock";;
                 *) echo "churn must be on or off"; exit 1;; esac
[ -f "$JAR" ] || { echo "REFUSING: no jar at $JAR"; exit 8; }
# Made absolute HERE, before the script changes into the server directory. The first smoke run passed a
# relative path, found the jar on this check, then failed to copy it after the cd.
JAR="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"

MOBS="${MOBS:-400}"
SETTLE="${SETTLE:-20}"
PROFILE="${PROFILE:-60}"

OUT="${PW_OUT:-$(cd "$(dirname "$0")/.." && pwd)/bench/lod}"
mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
LOG="$OUT/$LABEL.log"; IN="$OUT/$LABEL.stdin"; ROW="$OUT/$LABEL.row.txt"; SPARKDIR="$SERVER/config/spark"
[ -e "$ROW" ] && { echo "REFUSING: $ROW exists. A run never overwrites a previous one; name a new label."; exit 2; }
START=$(date +%s); elapsed() { echo $(( $(date +%s) - START )); }

cd "$SERVER" || exit 1
if grep -q '^level-name=pw-bench' server.properties 2>/dev/null; then
  [ -f server.properties.pristine ] || { echo "REFUSING: bench properties live with no pristine backup"; exit 6; }
else
  cp -f server.properties server.properties.pristine
fi
[ -f config/pathweaver.json ] && [ ! -f config/pathweaver.json.pristine ] &&
  cp -f config/pathweaver.json config/pathweaver.json.pristine

INSTALLED=""
restore() {
  for pid in "${TAILPID:-}" "${SERVERPID:-}"; do [ -n "$pid" ] && kill "$pid" 2>/dev/null; done
  # The installed jar comes out BEFORE the held one goes back; they can share a filename.
  [ -n "$INSTALLED" ] && rm -f "$SERVER/mods/$(basename "$INSTALLED")"
  pw_hold_restore
  [ -f "$SERVER/server.properties.pristine" ] &&
    cp -f "$SERVER/server.properties.pristine" "$SERVER/server.properties"
  [ -f "$SERVER/config/pathweaver.json.pristine" ] &&
    cp -f "$SERVER/config/pathweaver.json.pristine" "$SERVER/config/pathweaver.json"
}
pw_hold_init "$SERVER" || exit 8
trap restore EXIT INT TERM HUP
pw_hold_residents || exit 8
cp -f "$JAR" "$SERVER/mods/" || exit 8
INSTALLED="$JAR"
# The resident jar and the one under test can carry the same version string, so the loader's mod list
# cannot tell them apart. Hash is the only identity that can.
N_JARS="$(ls -1 "$SERVER"/mods/pathweaver-*.jar 2>/dev/null | wc -l)"
[ "$N_JARS" -eq 1 ] || { echo "REFUSING: $N_JARS pathweaver jars in mods/, expected exactly the one under test"; exit 8; }
WANT="$(sha256sum "$JAR" | cut -c1-64)"; GOT="$(sha256sum "$SERVER"/mods/pathweaver-*.jar | cut -c1-64)"
[ "$WANT" = "$GOT" ] || { echo "REFUSING: the jar in mods/ is not the jar under test"; exit 8; }
echo "  jar under test sha256 ${WANT:0:16}"

rm -rf "$SERVER/pw-bench"; mkdir -p config
cat > config/pathweaver.json <<CFG
{"configVersion":3,"enabled":${ASYNC},"compatibilityTier":"UNSAFE","brainSinkAsync":true,"resultCacheMode":"SHADOW","lodEnabled":${LOD_JSON},"lodMinDistanceBlocks":64,"lodIntervalTicks":40}
CFG

python - <<'PY'
import pathlib
p = pathlib.Path("server.properties")
want = {"level-name": "pw-bench", "pause-when-empty-seconds": "0", "spawn-monsters": "false",
        "spawn-animals": "false", "spawn-npcs": "false", "view-distance": "10",
        "simulation-distance": "10", "difficulty": "easy", "level-seed": "20260831",
        "sync-chunk-writes": "false", "max-tick-time": "-1", "enable-command-block": "true"}
lines, seen = [], set()
for l in p.read_text().splitlines():
    k = l.split("=", 1)[0]
    if k in want: lines.append(f"{k}={want[k]}"); seen.add(k)
    else: lines.append(l)
lines += [f"{k}={v}" for k, v in want.items() if k not in seen]
p.write_text("\n".join(lines) + "\n")
PY

echo "label=$LABEL jar=$(basename "$JAR") async=$ASYNC lod=$LOD churn=$CHURN mobs=$MOBS profile=${PROFILE}s"
: > "$IN"; : > "$LOG"; mkdir -p "$SPARKDIR"; rm -f "$SPARKDIR"/profile-*.sparkprofile
( tail -f "$IN" & echo $! > "$OUT/$LABEL.tailpid"; wait ) | "$JAVA" -Xmx12G -Xms4G \
    -XX:+UseG1GC -XX:+ParallelRefProcEnabled \
    -jar fabric-server-mc.26.1.2-loader.0.19.3-launcher.jar nogui >> "$LOG" 2>&1 &
SERVERPID=$!
sleep 1
TAILPID="$(cat "$OUT/$LABEL.tailpid" 2>/dev/null)"

say() { echo "$1" >> "$IN"; }
wait_for() { for _ in $(seq 1 "$2"); do grep -aq "$1" "$LOG" && return 0; sleep 1; done; return 1; }
count() {  # the population matching a selector, read from the command's own reply
  say "execute if entity $1"; sleep 2
  grep -aoE 'Test passed. Count: [0-9]+' "$LOG" | tail -1 | grep -oE '[0-9]+$'
}
void() { echo "VOID RUN: $1"; echo "VOID $LABEL: $1" > "$ROW.VOID"; exit 3; }

wait_for 'Done (' 300 || void "server never started"
echo "  server up at $(elapsed)s"
# "initializing" rather than "runtime started": with async off the runtime may never start, and that arm
# is still running the mod. Which jar it is was settled before boot, by hash.
grep -aq "PathWeaver initializing" "$LOG" || void "PathWeaver never initialised, so the mod under test did not load"

# ---- arena: the player far west, the herd far east -------------------------------------------------
say "forceload add -8 -24 118 24"
say "gamerule doMobSpawning false"; say "gamerule randomTickSpeed 0"; say "gamerule doDaylightCycle false"
say "gamerule doWeatherCycle false"; say "gamerule doFireTick false"; say "gamerule doMobLoot false"
say "gamerule maxEntityCramming 0"; say "gamerule commandBlockOutput false"; say "time set midnight"
say "kill @e[type=!minecraft:player]"
# player box
say "fill -4 200 -4 4 200 4 minecraft:stone"
say "fill -4 201 -4 4 203 4 minecraft:air"
# herd pen: x 70..110, z -20..20. Nearest point is 70 blocks from the player, so every mob is past the
# 64-block LOD distance, and the farthest corner is 112, inside simulation distance.
say "fill 70 200 -20 110 200 20 minecraft:stone"
say "fill 70 201 -20 110 204 20 minecraft:air"
say "fill 70 201 -20 110 203 -20 minecraft:stone"; say "fill 70 201 20 110 203 20 minecraft:stone"
say "fill 70 201 -20 70 203 20 minecraft:stone";   say "fill 110 201 -20 110 203 20 minecraft:stone"
for x in 80 90 100; do say "fill $x 201 -16 $x 203 16 minecraft:stone"; say "fill $x 201 -2 $x 203 2 minecraft:air"; done
sleep 4

# ---- churn: a command-block chain, identical in both arms -------------------------------------------
# Every 4 ticks the strips go down, 2 ticks later they come up, so each strip changes every 2 ticks.
say "fill 58 199 -23 68 203 -21 minecraft:air"
say "fill 58 200 -22 68 200 -22 minecraft:stone"
say "scoreboard objectives add churn dummy"
say "scoreboard players set #four churn 4"
say "scoreboard players set #t churn 0"
strip() { echo "fill 71 201 $1 109 201 $1 $2 replace $3"; }
chain=(
  "repeating_command_block|scoreboard players add #t churn 1"
  "chain_command_block|scoreboard players operation #p churn = #t churn"
  "chain_command_block|scoreboard players operation #p churn %= #four churn"
  "chain_command_block|execute if score #p churn matches 0 run $(strip -10 "$SWAP" "$PLACED")"
  "chain_command_block|execute if score #p churn matches 0 run $(strip 10 "$SWAP" "$PLACED")"
  "chain_command_block|execute if score #p churn matches 2 run $(strip -10 "$PLACED" "$SWAP")"
  "chain_command_block|execute if score #p churn matches 2 run $(strip 10 "$PLACED" "$SWAP")"
)
x=60
for c in "${chain[@]}"; do
  say "setblock $x 201 -22 minecraft:${c%%|*}[facing=east]{Command:\"${c#*|}\",auto:1b}"
  x=$((x + 1))
done
sleep 3

# ---- the player ------------------------------------------------------------------------------------
say "carpet commandPlayer true"; sleep 2
say "player Bench spawn at 0 201 0 facing 0 0"
wait_for 'Bench.*logged in with entity id' 60 || void "fake player never joined"
say "gamemode survival Bench"
say "effect give Bench minecraft:resistance 99999 255 true"
sleep 2

# ---- the herd --------------------------------------------------------------------------------------
# follow_range 16, so nothing in the pen can see a player 70 blocks away: the herd wanders rather than
# chasing, which keeps moveTo traffic low and leaves recomputes as a visible share of the work.
python - "$IN" "$MOBS" <<'PY'
import random, sys
inp, mobs = sys.argv[1], int(sys.argv[2])
random.seed(20260912 + mobs)
with open(inp, "a") as f:
    placed = 0
    while placed < mobs:
        x = random.randint(72, 108); z = random.randint(-18, 18)
        if x in (80, 90, 100) or z in (-10, 10):
            continue
        f.write(f"summon minecraft:zombie {x} 201 {z} "
                '{PersistenceRequired:1b,IsBaby:0b,CanPickUpLoot:0b,'
                'Attributes:[{id:"minecraft:follow_range",base:16}]}\n')
        placed += 1
PY
sleep $(( 4 + MOBS / 100 ))
sleep "$SETTLE"

# ---- controls before the window -------------------------------------------------------------------
BEFORE="$(count '@e[type=minecraft:zombie]')"
[ "${BEFORE:-0}" -eq "$MOBS" ] || void "asked for $MOBS zombies and found ${BEFORE:-none} before profiling"
say "scoreboard players get #t churn"; sleep 2
T0="$(grep -aoE '#t has [0-9]+' "$LOG" | tail -1 | grep -oE '[0-9]+$')"
# Is the churn actually changing blocks? Sampled at unaligned moments; a toggling strip is seen as carpet
# some of the time, and a strip that never changes is never seen as carpet.
SEEN=0
for _ in 1 2 3 4 5 6; do
  say "execute if block 75 201 -10 minecraft:white_carpet"
  sleep 1.3
done
SEEN="$(grep -aoE 'Test (passed|failed)' "$LOG" | tail -6 | grep -c passed)"
say "pathweaver status"; sleep 2
D0="$(grep -aoE 'dispatched=[0-9]+' "$LOG" | tail -1 | cut -d= -f2)"

# ---- the window ------------------------------------------------------------------------------------
say "spark profiler start --thread * --not-combined"
sleep 5
SAMPLE_START=$(elapsed)
sleep $(( PROFILE - 12 ))
say "spark health"
sleep 7
TICKS="$(grep -aA1 'Tick durations' "$LOG" | tail -1 | sed 's/^[^0-9]*//')"
say "spark profiler stop --save-to-file"
wait_for 'Saved the sampling profile\|saved to\|Profiler stopped' 30
sleep 3
SAVED="$(ls -1t "$SPARKDIR"/*.sparkprofile 2>/dev/null | head -1)"
[ -n "$SAVED" ] && cp "$SAVED" "$OUT/$LABEL.sparkprofile"

# ---- controls after the window ---------------------------------------------------------------------
say "pathweaver status"; sleep 2
D1="$(grep -aoE 'dispatched=[0-9]+' "$LOG" | tail -1 | cut -d= -f2)"
say "scoreboard players get #t churn"; sleep 2
T1="$(grep -aoE '#t has [0-9]+' "$LOG" | tail -1 | grep -oE '[0-9]+$')"
AFTER="$(count '@e[type=minecraft:zombie]')"

say "stop"
wait_for 'ThreadedAnvilChunkStorage: All dimensions are saved' 90
sleep 2

[ -f "$OUT/$LABEL.sparkprofile" ] || void "no profile was saved"
[ "${AFTER:-0}" -eq "$MOBS" ] || void "population moved during the window: $BEFORE before, ${AFTER:-none} after"
[ -n "$T0" ] && [ -n "$T1" ] && [ $(( T1 - T0 )) -gt 400 ] || void "the command-block chain did not run through the window (#t $T0 -> $T1)"
if [ "$CHURN" = "on" ]; then
  [ "$SEEN" -ge 1 ] && [ "$SEEN" -le 5 ] || void "churn on, but the strip read as carpet $SEEN of 6 times; it is not toggling"
else
  [ "$SEEN" -eq 0 ] || void "churn off, but the strip read as carpet $SEEN of 6 times"
fi

printf '%-22s async=%-5s lod=%-3s churn=%-3s zombies=%s/%s chainTicks=%s carpetSeen=%s/6 dispatched=%s at=%ss\n  ticks: %s\n' \
  "$LABEL" "$ASYNC" "$LOD" "$CHURN" "$BEFORE" "$AFTER" "$(( T1 - T0 ))" "$SEEN" "$(( ${D1:-0} - ${D0:-0} ))" \
  "$SAMPLE_START" "${TICKS:-NO TICK DURATIONS}" | tee "$ROW"
