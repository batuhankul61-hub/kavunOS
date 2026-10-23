# Measures the launcher on the TV: cold start, return to Home from an app, frame times while moving the focus,
# and memory. Run it before and after a change and compare.
#
# Usage: tools\perf.ps1 -TvHost 192.168.1.50 [-Runs 3]
param(
    [string]$TvHost = $env:TV_HOST,
    [int]$Runs = 3
)
$ErrorActionPreference = 'Stop'
# Pipe plain UTF-8 to tv.py --batch; the default prepends a BOM that corrupts the first command.
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost
$repo = Split-Path -Parent $PSScriptRoot
$py = Join-Path $repo 'tools\venv\Scripts\python.exe'
$tv = Join-Path $repo 'tools\tv.py'
$pkg = 'com.kavunos.tv'
function Tv([string]$cmd) { & $py -I $tv $cmd }

function Median([double[]]$xs) { $s = $xs | Sort-Object; $s[[int][math]::Floor(($s.Count - 1) / 2)] }

# Restart: process killed, Home started again. A force-stop unbinds the launcher's accessibility services, and a
# moment later Android may also drop them from the setting, so it is read once here, before any restart. Writing
# the same value back does not rebind them (the remote's Home button then falls through to the system): the
# setting is cleared, left a moment, and written again until they are bound. Through stdin: Windows PowerShell
# drops the inner double quotes from a native argument.
function TvBatch([string]$cmd) { $cmd | & $py -I $tv --batch }
$a11y = ((Tv 'settings get secure enabled_accessibility_services') | Select-Object -Last 1).Trim()
$cold = @()
for ($i = 0; $i -lt $Runs; $i++) {
    $out = TvBatch "am force-stop $pkg; sleep 2; am start -W -n $pkg/.MainActivity | grep TotalTime; sleep 3"
    $cold += [double]((($out | Select-String '^TotalTime') -join ' ') -replace '[^0-9]', '')
}
if ($a11y -and $a11y -ne 'null') {
    TvBatch ("for t in 1 2 3 4 5; do dumpsys accessibility | grep -q 'Bound services:{Service' && break; " +
        "settings put secure enabled_accessibility_services `"`"; sleep 2; " +
        "settings put secure enabled_accessibility_services '$a11y'; settings put secure accessibility_enabled 1; sleep 3; done") | Out-Null
}

# Return to Home from another app (the everyday case).
$warm = @()
for ($i = 0; $i -lt $Runs; $i++) {
    Tv 'am start -n com.android.tv.settings/.MainSettings >/dev/null 2>&1; sleep 3' | Out-Null
    $out = Tv "am start -W -a android.intent.action.MAIN -c android.intent.category.HOME | grep TotalTime; sleep 2"
    $warm += [double](($out -join ' ') -replace '[^0-9]', '')
}

# Frame times: 24 focus moves across the dock and the grid.
$moves = ((1..6 | % { 'input keyevent KEYCODE_DPAD_RIGHT; sleep 0.25' }) + 'input keyevent KEYCODE_DPAD_DOWN; sleep 0.4' +
    (1..6 | % { 'input keyevent KEYCODE_DPAD_LEFT; sleep 0.25' }) + 'input keyevent KEYCODE_DPAD_DOWN; sleep 0.4' +
    (1..6 | % { 'input keyevent KEYCODE_DPAD_RIGHT; sleep 0.25' }) + 'input keyevent KEYCODE_DPAD_UP; sleep 0.4' +
    'input keyevent KEYCODE_DPAD_UP; sleep 0.4') -join '; '
Tv "input keyevent KEYCODE_HOME; sleep 2; dumpsys gfxinfo $pkg reset >/dev/null; $moves; sleep 1" | Out-Null
# "Janky" counts shell-injected keys as late input; "Frame deadline missed" is the real dropped-frame count.
$gfx = Tv "dumpsys gfxinfo $pkg | grep -E 'Total frames rendered|Janky frames:|50th percentile|90th percentile|99th percentile|Frame deadline missed|Slow issue draw' | head -7"
$mem = Tv "dumpsys meminfo `$(pidof $pkg) | grep -E 'PSS:|Graphics:'"
$apk = Tv "ls -l `$(pm path $pkg | sed 's/package://')"

"cold start ms   : median $(Median $cold)  ($($cold -join ', '))"
"return Home ms  : median $(Median $warm)  ($($warm -join ', '))"
$gfx | ForEach-Object { "frames          : $($_.Trim())" }
$mem | ForEach-Object { "memory          : $($_.Trim())" }
"apk             : $((($apk -join ' ') -split '\s+')[4]) bytes"
# The restarts above must leave the Home button service running; say so loudly if they did not.
$bound = Tv 'dumpsys accessibility | grep Bound'
if ("$bound" -notmatch 'Service\[') { Write-Warning 'An accessibility service is enabled but not bound: toggle it in the TV settings or rerun the install script.' }
