#!/usr/bin/env bash
# One scripted session in the real client pack: load a fresh copy of a world, stand still, spawn a
# cat and one monster, then a small horde, and record frame times, integrated-server tick times and a
# spark profile of every thread across all of it.
#
#   bash bench/client/client-run.sh <label> <arm>
#
# Arms:  none      no PathWeaver jar in mods/
#        v080      the 0.8.0 file published on Modrinth, with the player's own config
#        v090      the release 0.9.0 jar, with the player's own config
#        v090def   the release 0.9.0 jar, with no config file (shipped defaults)
#        cand      a candidate jar (PW_CAND), with the player's own config
#
# WHY THE CLIENT. The complaint is "the moment I spawn a cat or a monster the game lags", in
# singleplayer, where the integrated server shares a process and a CPU with the render thread. A
# dedicated-server benchmark cannot see the render thread at all.
#
# NOTHING TYPES INTO A WINDOW. The first version drove chat with injected keystrokes, and when the game
# lost focus a campaign typed its commands into the operator's terminal. The scenario now runs inside
# the game, from the probe mod (bench/frameprobe ScenarioScript), so focus does not matter.
#
# WHAT IS TOUCHED AND PUT BACK. PathWeaver jars in mods/ are moved to mods-pw-held/ and returned; the
# player's config is copied aside once and restored; options.txt and Dynamic FPS's config are restored;
# the scenario file is removed. The world is a copy; the player's own saves are never opened.
set -u
LABEL="${1:?label}"; ARM="${2:?arm}"
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
MC="${PW_MC:-$HOME/AppData/Roaming/.minecraft}"
BASE_WORLD="${PW_BASE_WORLD:?PW_BASE_WORLD: a pristine world folder to copy}"
PROBE="$REPO/bench/frameprobe/build/libs/pw-frameprobe-1.0.0.jar"
PLAYER_CONFIG="$MC/config/pathweaver.json.player"
OUT="$REPO/bench/client-runs/$LABEL"
[ -e "$OUT" ] && { echo "REFUSING: $OUT exists; a run never overwrites another"; exit 2; }
[ -f "$PROBE" ] || { echo "REFUSING: probe jar missing, build bench/frameprobe"; exit 2; }
if tasklist //FI "IMAGENAME eq javaw.exe" | grep -qi javaw; then
  echo "REFUSING: a javaw process is running; the player may be in the game"; exit 2
fi
mkdir -p "$OUT" "$MC/mods-pw-held"
say() { echo "[$(date -u +%H:%M:%S)] $*" | tee -a "$OUT/driver.txt"; }

# ---------------------------------------------------------------- arm setup
for j in "$MC"/mods/pathweaver-*.jar; do [ -e "$j" ] && mv "$j" "$MC/mods-pw-held/"; done
ls "$MC"/mods/pathweaver-*.jar >/dev/null 2>&1 && { echo "REFUSING: could not clear pathweaver jars"; exit 3; }
[ -f "$PLAYER_CONFIG" ] || cp "$MC/config/pathweaver.json" "$PLAYER_CONFIG"
cp "$MC/options.txt" "$OUT/options.txt.before"
cp "$MC/config/dynamic_fps.json" "$OUT/dynamic_fps.json.before"
PID=""
restore() {
  [ -n "$PID" ] && taskkill //PID "$PID" //F >/dev/null 2>&1
  rm -f "$MC"/mods/pathweaver-*.jar "$MC/mods/pw-frameprobe-1.0.0.jar" "$MC/pwprobe-script.txt"
  mv "$MC"/mods-pw-held/pathweaver-*.jar "$MC/mods/" 2>/dev/null
  rmdir "$MC/mods-pw-held" 2>/dev/null
  cp "$PLAYER_CONFIG" "$MC/config/pathweaver.json"
  cp "$OUT/options.txt.before" "$MC/options.txt"
  cp "$OUT/dynamic_fps.json.before" "$MC/config/dynamic_fps.json"
  rm -rf "$MC/saves/PW-repro"
  say "restored mods, config, options, dynamic fps; removed the world copy"
}
trap restore EXIT
case "$ARM" in
  none)    rm -f "$MC/config/pathweaver.json" ;;
  v080)    cp "${PW_V080:?}" "$MC/mods/"; cp "$PLAYER_CONFIG" "$MC/config/pathweaver.json" ;;
  v090)    cp "${PW_V090:?}" "$MC/mods/"; cp "$PLAYER_CONFIG" "$MC/config/pathweaver.json" ;;
  v090def) cp "${PW_V090:?}" "$MC/mods/"; rm -f "$MC/config/pathweaver.json" ;;
  cand)    cp "${PW_CAND:?}" "$MC/mods/"; cp "$PLAYER_CONFIG" "$MC/config/pathweaver.json" ;;
  *) echo "unknown arm $ARM"; exit 1 ;;
esac
cp "$PROBE" "$MC/mods/"
{ sha256sum "$MC"/mods/pathweaver-*.jar 2>/dev/null; echo "arm=$ARM"; } > "$OUT/jars.txt"
# A game that pauses when it loses focus would stop the integrated server, and Dynamic FPS throttles an
# unfocused window. Nothing here keeps the focus, so both are switched off for the run.
sed -i 's/^pauseOnLostFocus:true/pauseOnLostFocus:false/' "$MC/options.txt"
echo '{"enabled": false}' > "$MC/config/dynamic_fps.json"
rm -rf "$MC/saves/PW-repro"; cp -r "$BASE_WORLD" "$MC/saves/PW-repro"
cp "$HERE/scenario.txt" "$MC/pwprobe-script.txt"; cp "$HERE/scenario.txt" "$OUT/scenario.txt"
say "arm $ARM set up: $(tr '\n' ' ' < "$OUT/jars.txt")"

# ---------------------------------------------------------------- launch and wait
BEFORE=$(date +%s)
PID=$(python "$HERE/launch_client.py" "$OUT/launch_cmd.txt" PW-repro "$OUT/stdout.txt" | tail -1)
[ -n "$PID" ] || { say "launcher printed no pid"; exit 4; }
say "launched pid $PID"
for i in $(seq 1 600); do
  sleep 1
  if [ "$(stat -c %Y "$MC/logs/latest.log" 2>/dev/null || echo 0)" -ge "$BEFORE" ] \
      && grep -q "PWMARK done" "$MC/logs/latest.log"; then break; fi
  tasklist //FI "PID eq $PID" | grep -q "$PID" || { say "client exited mid-scenario"; break; }
done
sleep 3
taskkill //PID "$PID" //F >/dev/null 2>&1
for i in $(seq 1 30); do tasklist //FI "PID eq $PID" | grep -q "$PID" || break; sleep 1; done
PID=""
cp "$MC/logs/latest.log" "$OUT/latest.log"
newest=$(ls -t "$MC"/spark/*.sparkprofile "$MC"/config/spark/*.sparkprofile 2>/dev/null | head -1)
[ -n "$newest" ] && [ "$(stat -c %Y "$newest")" -ge "$BEFORE" ] && cp "$newest" "$OUT/profile.sparkprofile"
grep -q "Server thread.*PWMARK done" "$OUT/latest.log" || { say "VOID: the scenario never finished"; echo VOID > "$OUT/VOID"; }
[ -f "$OUT/profile.sparkprofile" ] || say "no profile saved"
say "run complete"
