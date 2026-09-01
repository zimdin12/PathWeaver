#!/usr/bin/env bash
# Is the campaign alive, finished, or stale? One cheap call, always answers.
#
# Written because the failure mode is not the test hanging, it is waiting on a test that already
# finished: 80 minutes went that way on a probe that had exited at 02:27.
#
# LIVENESS IS THE SCRIPT, NOT THE SERVER. Between runs there is a two to three minute window with no
# java process and nothing appended to campaign.txt, because that file is only written at run
# boundaries. The first version called that DEAD, which would have released a CPU embargo other
# agents are waiting on while the campaign was still going.
OUT="/c/Users/Administrator/AppData/Roaming/.minecraft/modding/PathWeaver/bench/deep"
LOG="$OUT/campaign.txt"
[ -f "$LOG" ] || { echo "NO CAMPAIGN"; exit 0; }

now=$(date +%s)
newest=$(ls -t "$OUT"/*.log 2>/dev/null | head -1)
lidle=999999
[ -n "$newest" ] && lidle=$(( now - $(stat -c %Y "$newest" 2>/dev/null || echo 0) ))
script=$(powershell -NoProfile -Command "@(Get-CimInstance Win32_Process | Where-Object { \$_.CommandLine -like '*deep-campaign.sh*' -and \$_.Name -eq 'bash.exe' }).Count" 2>/dev/null | tr -d '\r ')
runs=$(grep -ac "^control:" "$LOG")
finished=$(grep -ac "campaign complete" "$LOG")
voids=$(grep -ac "VOID RUN" "$LOG")

echo "runs finished: $runs/9  (void: $voids)   campaign script: ${script:-?}   newest log idle: ${lidle}s"

if [ "$finished" -gt 0 ]; then
  echo "STATUS: FINISHED  --> send the CPU-embargo message to graph-tech-lead, comms-tech-lead, sc-manager"
elif [ "${script:-0}" = "0" ]; then
  echo "STATUS: DEAD, the campaign script is gone  --> stop waiting, and send the embargo message"
elif [ "$lidle" -gt 600 ]; then
  echo "STATUS: STALE, script alive but no log written for ${lidle}s  --> investigate before trusting it"
else
  echo "STATUS: RUNNING"
fi
