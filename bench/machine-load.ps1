# Records what else the machine is doing while a benchmark runs, so a slow run can be explained by the
# clock rather than blamed on a jar. Written after a regression check was voided by load nobody recorded.
#
#   pwsh -NoProfile -File bench/machine-load.ps1 <out.csv> [interval seconds, default 10]
#
# One row per sample: UTC time, total CPU percent, the cores the benchmark server's java uses, and the
# three busiest other processes. Stops when <out.csv>.stop exists. Reads Windows' own performance data,
# which attributes processes that Get-Process hides (a second desktop window manager held 8 cores, and
# Get-Process showed it as idle).
param([Parameter(Mandatory = $true)][string]$Out, [int]$Interval = 10)
# A comma-decimal locale would split every number into two CSV cells.
[Threading.Thread]::CurrentThread.CurrentCulture = [Globalization.CultureInfo]::InvariantCulture

if (-not (Test-Path $Out)) { "utc,total_pct,java_cores,other1,other1_cores,other2,other2_cores,other3,other3_cores" | Set-Content $Out }
while (-not (Test-Path "$Out.stop")) {
    $rows = Get-CimInstance Win32_PerfFormattedData_PerfProc_Process
    $total = ($rows | Where-Object { $_.Name -eq '_Total' }).PercentProcessorTime
    $idle = ($rows | Where-Object { $_.Name -eq 'Idle' }).PercentProcessorTime
    $cores = [Environment]::ProcessorCount
    $busyPct = [Math]::Round(100.0 * (1.0 - $idle / (100.0 * $cores)), 1)
    $procs = $rows | Where-Object { $_.Name -notin '_Total', 'Idle' }
    $java = ($procs | Where-Object { $_.Name -like 'java*' } | Measure-Object PercentProcessorTime -Sum).Sum / 100.0
    $others = $procs | Where-Object { $_.Name -notlike 'java*' } | Sort-Object PercentProcessorTime -Descending | Select-Object -First 3
    $cells = @($others | ForEach-Object { $_.Name; [Math]::Round($_.PercentProcessorTime / 100.0, 2) })
    ((@((Get-Date).ToUniversalTime().ToString('HH:mm:ss'), $busyPct, [Math]::Round($java, 2)) + $cells) -join ',') | Add-Content $Out
    Start-Sleep -Seconds $Interval
}
