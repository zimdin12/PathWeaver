#!/usr/bin/env bash
# Is the campaign alive, finished, or stale? One cheap call, always answers.
#
# Written because the failure mode is not the test hanging, it is ME waiting on a test that already
# finished. That happened for 80 minutes tonight on a probe that had exited at 02:27.
OUT="/c/Users/Administrator/AppData/Roaming/.minecraft/modding/PathWeaver/bench/deep"
LOG="$OUT/campaign.txt"
now=$(date +%s)
[ -f "$LOG" ] || { echo "NO CAMPAIGN"; exit 0; }

mtime=$(stat -c %Y "$LOG" 2>/dev/null || echo 0)
idle=$(( now - mtime ))
newest=$(ls -t "$OUT"/*.log 2>/dev/null | head -1)
lidle=999999
[ -n "$newest" ] && lidle=$(( now - $(stat -c %Y "$newest" 2>/dev/null || echo 0) ))
servers=$(powershell -NoProfile -Command "@(Get-CimInstance Win32_Process -Filter \"Name like '%java%'\" | Where-Object { \$_.CommandLine -like '*fabric-server-mc*' }).Count" 2>/dev/null | tr -d ' ')

done_line=$(grep -ac "campaign complete" "$LOG")
runs=$(grep -ac "^control:" "$LOG")
echo "runs finished: $runs/9   server processes: ${servers:-?}   campaign.txt idle ${idle}s   server log idle ${lidle}s"

if [ "$done_line" -gt 0 ]; then
  echo "STATUS: FINISHED  <-- send the CPU-embargo message to graph-tech-lead, comms-tech-lead, sc-manager"
elif [ "${servers:-0}" = "0" ] && [ "$idle" -gt 120 ]; then
  echo "STATUS: DEAD  (no server running and nothing written for ${idle}s) <-- stop waiting, and send the embargo message"
elif [ "$lidle" -gt 420 ]; then
  echo "STATUS: STALE  (a server is up but its log has not moved for ${lidle}s)"
else
  echo "STATUS: RUNNING"
fi
