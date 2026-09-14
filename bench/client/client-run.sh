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
#
# WHY THE CLIENT. The complaint is "the moment I spawn a cat or a monster the game lags", in
# singleplayer, where the integrated server shares a process and a CPU with the render thread. A
# dedicated-server benchmark cannot see the render thread at all.
#
# WHAT IS TOUCHED AND PUT BACK. The PathWeaver jar in mods/ is moved to mods-pw-held/ for arms that
# need it absent or different, the player's config is copied aside once and restored, and
# options.txt is restored. The world is a copy; the player's own save is never opened.
set -u
LABEL="${1:?label}"; ARM="${2:?arm}"
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
MC="${PW_MC:-$HOME/AppData/Roaming/.minecraft}"
BASE_WORLD="${PW_BASE_WORLD:?PW_BASE_WORLD: a pristine world folder to copy}"
V080="${PW_V080:?PW_V080: the published 0.8.0 jar}"
V090="${PW_V090:?PW_V090: the release 0.9.0 jar}"
PROBE="$REPO/bench/frameprobe/build/libs/pw-frameprobe-1.0.0.jar"
PLAYER_CONFIG="$MC/config/pathweaver.json.player"
OUT="$REPO/bench/client-runs/$LABEL"
[ -e "$OUT" ] && { echo "REFUSING: $OUT exists; a run never overwrites another"; exit 2; }
[ -f "$PROBE" ] || { echo "REFUSING: probe jar missing, build bench/frameprobe"; exit 2; }
mkdir -p "$OUT" "$MC/mods-pw-held"
PS1W="$(cygpath -w "$HERE/mcdrive.ps1")"; OUTW="$(cygpath -w "$OUT")"
say() { echo "[$(date -u +%H:%M:%S)] $*" | tee -a "$OUT/driver.txt"; }

# ---------------------------------------------------------------- arm setup
for j in "$MC"/mods/pathweaver-*.jar; do [ -e "$j" ] && mv "$j" "$MC/mods-pw-held/"; done
ls "$MC"/mods/pathweaver-*.jar >/dev/null 2>&1 && { echo "REFUSING: could not clear pathweaver jars"; exit 3; }
[ -f "$PLAYER_CONFIG" ] || cp "$MC/config/pathweaver.json" "$PLAYER_CONFIG"
cp "$MC/options.txt" "$OUT/options.txt.before"
restore() {
  rm -f "$MC"/mods/pathweaver-*.jar "$MC/mods/pw-frameprobe-1.0.0.jar"
  mv "$MC"/mods-pw-held/pathweaver-*.jar "$MC/mods/" 2>/dev/null
  cp "$PLAYER_CONFIG" "$MC/config/pathweaver.json"
  cp "$OUT/options.txt.before" "$MC/options.txt"
  say "restored mods, config and options"
}
trap restore EXIT
case "$ARM" in
  none)    rm -f "$MC/config/pathweaver.json" ;;
  v080)    cp "$V080" "$MC/mods/"; cp "$PLAYER_CONFIG" "$MC/config/pathweaver.json" ;;
  v090)    cp "$V090" "$MC/mods/"; cp "$PLAYER_CONFIG" "$MC/config/pathweaver.json" ;;
  v090def) cp "$V090" "$MC/mods/"; rm -f "$MC/config/pathweaver.json" ;;
  *) echo "unknown arm $ARM"; exit 1 ;;
esac
cp "$PROBE" "$MC/mods/"
sha256sum "$MC"/mods/pathweaver-*.jar 2>/dev/null > "$OUT/jars.txt"; echo "arm=$ARM" >> "$OUT/jars.txt"
# A game that pauses when it loses focus would stop the integrated server whenever anything else
# takes the foreground, and the probe would read that as a quiet tick rate.
sed -i 's/^pauseOnLostFocus:true/pauseOnLostFocus:false/' "$MC/options.txt"
rm -rf "$MC/saves/PW-repro"; cp -r "$BASE_WORLD" "$MC/saves/PW-repro"
say "arm $ARM set up: $(tr '\n' ' ' < "$OUT/jars.txt")"

# ---------------------------------------------------------------- launch
BEFORE=$(stat -c %Y "$MC/logs/latest.log" 2>/dev/null || echo 0)
PID=$(python "$HERE/launch_client.py" "$OUT/launch_cmd.txt" PW-repro "$OUT/stdout.txt" | tail -1)
[ -n "$PID" ] || { say "launcher printed no pid"; exit 4; }
say "launched pid $PID"
for i in $(seq 1 300); do
  sleep 1
  now=$(stat -c %Y "$MC/logs/latest.log" 2>/dev/null || echo 0)
  if [ "$now" -gt "$BEFORE" ] && grep -q "joined the game" "$MC/logs/latest.log"; then break; fi
  if ! tasklist //FI "PID eq $PID" | grep -q "$PID"; then say "client exited before joining"; exit 4; fi
done
grep -q "joined the game" "$MC/logs/latest.log" || { say "never joined"; taskkill //PID "$PID" //F; exit 4; }
say "joined; settling 60 s"
sleep 60

# Git Bash rewrites any argument that starts with / into a Windows path, so "/say x" reached the game
# as "C:/Program Files/Git/say x" and no command in the first smoke run ever executed. Switched off
# for the chat calls only: the launcher needs its paths converted.
chat() { MSYS_NO_PATHCONV=1 powershell -NoProfile -ExecutionPolicy Bypass -File "$PS1W" -ProcessId "$PID" -Action chat -Text "$1" -OutFile "$OUTW\shot-$2.png" >> "$OUT/driver.txt" 2>&1; say "chat: $1"; }

chat "/time set 1000" t
chat "/gamemode survival" gm
chat "/effect give @s minecraft:resistance infinite 255 true" e1
chat "/effect give @s minecraft:regeneration infinite 255 true" e2
chat "/say PWMARK base" base
sleep 40
chat "/spark profiler start --thread * --not-combined" pstart
chat "/say PWMARK one" one
chat "/summon minecraft:cat ~3 ~ ~" cat
chat "/summon minecraft:husk ~8 ~ ~" husk
sleep 40
chat "/say PWMARK horde" horde
for i in 1 2 3 4 5 6 7 8 9 10; do
  MSYS_NO_PATHCONV=1 powershell -NoProfile -ExecutionPolicy Bypass -File "$PS1W" -ProcessId "$PID" -Action chat -Text "/summon minecraft:husk ~8 ~ ~$i" -OutFile "$OUTW\shot-h.png" >> "$OUT/driver.txt" 2>&1
done
say "horde summoned"
sleep 40
chat "/say PWMARK end" end
chat "/spark profiler stop --save-to-file" pstop
sleep 10
case "$ARM" in none) ;; *) chat "/pathweaver status" status; sleep 3 ;; esac
chat "/say PWMARK done" done
sleep 2
taskkill //PID "$PID" //F >/dev/null 2>&1
for i in $(seq 1 30); do tasklist //FI "PID eq $PID" | grep -q "$PID" || break; sleep 1; done
cp "$MC/logs/latest.log" "$OUT/latest.log"
newest=$(ls -t "$MC"/spark/*.sparkprofile "$MC"/config/spark/*.sparkprofile 2>/dev/null | head -1)
[ -n "$newest" ] && [ "$(stat -c %Y "$newest")" -gt "$BEFORE" ] && cp "$newest" "$OUT/profile.sparkprofile"
grep -q "PWMARK horde" "$OUT/latest.log" && ! grep -q "Program Files/Git" "$OUT/latest.log" || { say "VOID: chat commands did not reach the game"; echo VOID > "$OUT/VOID"; }
say "run complete"
