#!/usr/bin/env bash
# PathWeaver server benchmark driver.
#
# Runs the dedicated 26.1.2 pack headless on a throwaway superflat world, summons a fixed mob
# population, lets it settle, then takes a spark profile of the server thread. The only variable
# between runs is the setting named by $1.
#
# The published 0.6.1 benchmark left no scenario behind, so its numbers cannot be reproduced or
# re-measured. This exists so 0.8.0's can be.
set -u

SERVER="/c/Users/Administrator/AppData/Roaming/.minecraft_server"
JAVA="/c/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot/bin/java.exe"
LABEL="${1:-discovery}"
OUT="${2:-/c/Users/Administrator/AppData/Roaming/.minecraft/modding/PathWeaver/bench/out}"
BRAIN_SINK="${3:-true}"
MODE="${4:-full}"          # full | discovery
SETTLE="${5:-60}"
SAMPLE="${6:-45}"

# Resolve OUT before the cd below, or a relative path silently lands under the server dir.
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
LOG="$OUT/$LABEL.log"
IN="$OUT/$LABEL.stdin"

cd "$SERVER" || exit 1

# --- preserve the operator's setup, always, even on failure
[ -f server.properties.prebench ] || cp server.properties server.properties.prebench
restore() {
  [ -f "$SERVER/server.properties.prebench" ] && \
    mv -f "$SERVER/server.properties.prebench" "$SERVER/server.properties"
}
trap restore EXIT

python - <<'PY'
import pathlib
p = pathlib.Path("server.properties")
lines = p.read_text().splitlines()
want = {"level-name": "pw-bench", "level-type": "minecraft:flat", "spawn-monsters": "false",
        "spawn-animals": "false", "spawn-npcs": "false", "view-distance": "10",
        "simulation-distance": "10", "difficulty": "easy", "sync-chunk-writes": "false", "level-seed": "20260831"}
out, seen = [], set()
for l in lines:
    k = l.split("=", 1)[0]
    if k in want:
        out.append(f"{k}={want[k]}"); seen.add(k)
    else:
        out.append(l)
for k, v in want.items():
    if k not in seen: out.append(f"{k}={v}")
p.write_text("\n".join(out) + "\n")
print("bench server.properties written")
PY

# Start each arm from an identical world.
#
# Gamerules and force-loaded chunks persist INSIDE the world, so a run inherits the previous run's
# setup. spawnChunkRadius=10 survived one boot and the next server spent six minutes generating 441
# chunks of Tectonic terrain and never reached Done(). Deleting the throwaway bench world is also
# what makes the two arms comparable rather than merely sequential.
rm -rf "$SERVER/pw-bench"

mkdir -p config
cat > config/pathweaver.json <<CFG
{"configVersion":2,"enabled":true,"compatibilityTier":"UNSAFE","brainSinkAsync":${BRAIN_SINK}}
CFG
echo "config: brainSinkAsync=${BRAIN_SINK}"

: > "$IN"
: > "$LOG"
tail -f "$IN" | "$JAVA" -Xmx12G -Xms4G -XX:+UseG1GC -XX:+ParallelRefProcEnabled \
    -jar fabric-server-mc.26.1.2-loader.0.19.3-launcher.jar nogui >> "$LOG" 2>&1 &
TAILPID=$!

say() { echo "$1" >> "$IN"; }

# --- wait for the server to be up, with a hard ceiling so a hang cannot run forever
for i in $(seq 1 300); do
  grep -q 'Done (' "$LOG" && break
  sleep 2
done
if ! grep -q 'Done (' "$LOG"; then
  echo "SERVER NEVER REPORTED Done() -- see $LOG"; say "stop"; sleep 20; kill $TAILPID 2>/dev/null; exit 2
fi
echo "server up after $(grep -o 'Done ([0-9.]*s)' "$LOG" | head -1)"

if [ "$MODE" = "discovery" ]; then
  # spark auto-starts a background profiler when it enables. Confirm here, BEFORE any real run,
  # that stopping it writes a local file instead of uploading: an upload would publish the pack's
  # composition to a third party, which is not ours to do.
  say "spark profiler stop --save-to-file"
  sleep 20
  say "spark help"
  sleep 10
  say "stop"
  sleep 30
  kill $TAILPID 2>/dev/null
  exit 0
fi

# --- fixed, reproducible population
# WITHOUT THIS THE BENCHMARK MEASURES NOTHING.
#
# A dedicated server with no player connected keeps almost no chunk in the entity-ticking state, so
# the mobs stand frozen and the server thread parks. The first run of this harness looked fine --
# a profile saved, a percentage printed -- and the server thread had spent 48.5s of 54.8s in
# Unsafe.park with tickServer at 670ms. Force-loading the arena is what makes the mobs tick.
say "forceload add -40 -40 40 40"

# BUILD THE ARENA, do not trust level-type=flat.
#
# This pack ships Tectonic and Terralith, which override worldgen, so a "superflat" bench world is
# not flat at all. The first corrected run summoned 220 mobs into open air at y=-60 and every one of
# them fell to y=-252 and died on impact. A stone floor placed by /fill is the same on any pack.
# Each fill stays under the 32768-block command limit, so the air is cleared one layer at a time.
say "fill -40 -60 -40 40 -60 40 minecraft:stone"
say "fill -40 -59 -40 40 -59 40 minecraft:air"
say "fill -40 -58 -40 40 -58 40 minecraft:air"
say "fill -40 -57 -40 40 -57 40 minecraft:air"
say "fill -40 -56 -40 40 -56 40 minecraft:air"
sleep 3

# A PLAYER HAS TO BE PRESENT OR NOTHING TICKS.
#
# A dedicated server with nobody connected does not tick entities, and force-loading the arena did
# not change that: the server thread parked for 54.1s of a 54.4s sample with tickServer absent from
# the profile entirely, while 220 healthy mobs stood still and PathWeaver dispatched nothing. The
# published 0.6.1 benchmark was taken with a real client attached, which is why it saw work at all.
# Carpet ships a fake player, which is a real ServerPlayer to the tick loop.
say "player Bench spawn at 0 -59 0 facing 0 0 in minecraft:overworld"
say "gamerule doMobSpawning false"
say "gamerule randomTickSpeed 0"
say "gamerule doDaylightCycle false"
say "gamerule doWeatherCycle false"
say "time set day"
say "kill @e[type=!minecraft:player]"
sleep 3

# 120 villagers (brain mobs -- these are what the brain sink acts on) and 100 pathfinder animals,
# scattered over a 60x60 flat area so they have room to actually path.
python - "$IN" <<'PY'
import random, sys
random.seed(20260831)          # fixed, so both arms get the identical layout
inp = open(sys.argv[1], "a")
def spread(kind, n):
    for _ in range(n):
        x = random.randint(-30, 30); z = random.randint(-30, 30)
        inp.write(f"summon minecraft:{kind} {x} -59 {z}\n")
spread("villager", 120)
for kind, n in (("cow", 25), ("sheep", 25), ("pig", 25), ("goat", 25)):
    spread(kind, n)
inp.flush()
PY
echo "population summoned"
sleep "$SETTLE"

say "pathweaver status"
sleep 2

# spark enables a background profiler on its own, and restarts it after every stop. So the sample
# window is bracketed by two stops rather than a start/stop pair: the first discards the warmup and
# restarts the clock, the second saves exactly the interval between them. Both commands are ones
# this harness has actually observed working; --save-to-file writes locally and does not upload.
SPARKDIR="$SERVER/config/spark"
mkdir -p "$SPARKDIR"
say "spark profiler stop --save-to-file"
sleep 12
BEFORE="$(ls -1t "$SPARKDIR"/*.sparkprofile 2>/dev/null | head -1)"
echo "warmup profile discarded: ${BEFORE:-none}"

sleep "$SAMPLE"

say "spark profiler stop --save-to-file"
sleep 15
AFTER="$(ls -1t "$SPARKDIR"/*.sparkprofile 2>/dev/null | head -1)"
if [ -n "$AFTER" ] && [ "$AFTER" != "$BEFORE" ]; then
  cp "$AFTER" "$OUT/$LABEL.sparkprofile"
  echo "saved $OUT/$LABEL.sparkprofile"
else
  echo "NO NEW PROFILE -- the sample did not save; do not quote a number from this run"
fi

say "pathweaver status"
sleep 5
say "stop"
sleep 40
kill $TAILPID 2>/dev/null

# THE POSITIVE CONTROL. A profile that recorded no pathfinding is indistinguishable from a profile
# taken while nothing ran, and the second is what happened on the first attempt. If the mod
# dispatched nothing, the run is void and its number must not be quoted.
DISPATCHED="$(grep -oE 'dispatched=[0-9]+' "$LOG" | tail -1 | cut -d= -f2)"
TICKED="$(grep -c 'Summoned new' "$LOG")"
DIED="$(grep -ac 'died, message' "$LOG")"
JOINED="$(grep -ac 'Bench.*logged in with entity id' "$LOG")"
if [ "$JOINED" = "0" ]; then
  echo "VOID RUN: the fake player never joined, so the world was not ticking."
  mv -f "$OUT/$LABEL.sparkprofile" "$OUT/$LABEL.VOID.sparkprofile" 2>/dev/null
  exit 5
fi
echo "control: mobs summoned=$TICKED, died=$DIED, dispatched=${DISPATCHED:-unknown}"
if [ "$DIED" -gt 20 ]; then
  echo "VOID RUN: $DIED mobs died. The arena is wrong, so the population is not what was measured."
  mv -f "$OUT/$LABEL.sparkprofile" "$OUT/$LABEL.VOID.sparkprofile" 2>/dev/null
  exit 4
fi
if [ "${DISPATCHED:-0}" = "0" ]; then
  echo "VOID RUN: PathWeaver dispatched nothing, so this profile says nothing about it."
  mv -f "$OUT/$LABEL.sparkprofile" "$OUT/$LABEL.VOID.sparkprofile" 2>/dev/null
  exit 3
fi
echo "run $LABEL complete"
