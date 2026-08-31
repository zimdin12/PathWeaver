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
        "simulation-distance": "10", "difficulty": "easy", "sync-chunk-writes": "false"}
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
for i in $(seq 1 180); do
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
        inp.write(f"summon minecraft:{kind} {x} -60 {z}\n")
spread("villager", 120)
for kind, n in (("cow", 25), ("sheep", 25), ("pig", 25), ("goat", 25)):
    spread(kind, n)
inp.flush()
PY
echo "population summoned"
sleep "$SETTLE"

say "pathweaver status"
sleep 2
say "spark profiler start --timeout $SAMPLE --thread \"Server thread\""
sleep $((SAMPLE + 25))
say "pathweaver status"
sleep 3
say "stop"
sleep 40
kill $TAILPID 2>/dev/null
echo "run $LABEL complete"
