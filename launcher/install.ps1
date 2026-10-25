# Install a built kavunOS APK on the TV over the network and make it the home screen. Needs no JDK, SDK or
# checkout: without -Apk it takes kavunOS.apk from the latest GitHub release, and outside a checkout it fetches
# the two ADB helpers into %LOCALAPPDATA%\kavunOS. Python is installed with winget when missing.
#
# Usage: launcher\install.ps1 [-TvHost <tv ip>] [-Apk <file>] [-StockLauncher <package>|none]
#                             [-InputKeyService] [-HomeKeyService] [-Reinstall]
#        Without -TvHost the local network is scanned for a TV with network debugging on.
#        -Reinstall    uninstall first; needed when the TV has a build signed by another PC (settings are lost)
# From the web (what kavunOS-kur.cmd runs):
#   & ([scriptblock]::Create((irm https://raw.githubusercontent.com/batuhankul61-hub/kavunOS/main/launcher/install.ps1))) -HomeKeyService
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$Apk = '',
    [string]$StockLauncher = '',
    [switch]$InputKeyService,
    [switch]$HomeKeyService,
    [switch]$Reinstall
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # Windows PowerShell's progress bar slows Invoke-WebRequest down many times
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$repo = 'https://github.com/batuhankul61-hub/kavunOS'
$raw = 'https://raw.githubusercontent.com/batuhankul61-hub/kavunOS/main'

# The ADB helpers: the checkout's own tools when run from one, otherwise a copy fetched from GitHub.
$checkoutTools = if ($PSScriptRoot) { Join-Path (Split-Path $PSScriptRoot) 'tools' } else { '' }
if ($checkoutTools -and (Test-Path (Join-Path $checkoutTools 'tv.py'))) {
    $tools = $checkoutTools
    $venv = Join-Path $tools 'venv'
} else {
    $tools = Join-Path $env:LOCALAPPDATA 'kavunOS'
    $venv = Join-Path $tools 'venv'
    New-Item -ItemType Directory -Force $tools | Out-Null
    foreach ($f in 'tv.py', 'b64push.py', 'requirements.txt') {
        Invoke-WebRequest "$raw/tools/$f" -UseBasicParsing -OutFile (Join-Path $tools $f)
    }
}
$py = Join-Path $venv 'Scripts\python.exe'
$tv = Join-Path $tools 'tv.py'

# Exit code of a quiet probe. Under 'Stop', Windows PowerShell turns a redirected stderr line into a terminating error.
function Test-Native([string]$Exe, [string[]]$Arguments) {
    $ErrorActionPreference = 'Continue'
    & $Exe @Arguments 2>$null | Out-Null
    return $LASTEXITCODE -eq 0
}

function Find-Python {
    if ((Get-Command py -ErrorAction SilentlyContinue) -and (Test-Native 'py' @('-3', '-c', 'import sys'))) { return @('py', '-3') }
    # The WindowsApps python.exe is only a Microsoft Store shortcut and does not run anything.
    $p = Get-Command python -ErrorAction SilentlyContinue | Where-Object { $_.Source -notlike '*\WindowsApps\*' } | Select-Object -First 1
    if ($p) { return @($p.Source) }
    return $null
}

# A missing or half-built venv is set up again; the import check also catches an interrupted pip install.
if (-not (Test-Path $py) -or -not (Test-Native $py @('-I', '-c', 'import adb_shell'))) {
    $base = Find-Python
    if (-not $base) {
        if (-not (Get-Command winget -ErrorAction SilentlyContinue)) { throw 'Python 3 is needed: install it from https://www.python.org/downloads/ and run this again' }
        Write-Host 'Installing Python...'
        winget install -e --id Python.Python.3.12 --accept-source-agreements --accept-package-agreements --silent | Out-Host
        $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
        $base = Find-Python
        if (-not $base) { throw 'Python was installed but is not on PATH yet: open a new window and run this again' }
    }
    Write-Host 'Setting up the ADB helper...'
    $baseArgs = @($base | Select-Object -Skip 1)
    & $base[0] @baseArgs -m venv $venv
    if ($LASTEXITCODE -ne 0) { throw 'could not create the Python environment' }
    & $py -m pip install -q --disable-pip-version-check -r (Join-Path $tools 'requirements.txt')
    if ($LASTEXITCODE -ne 0) { throw 'could not install the Python packages (check the internet connection)' }
}

# TCP 5555 is where network debugging listens. Only the PC's own /24 networks are tried, all at once.
function Find-Tv {
    $own = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notmatch '^(127|169\.254)\.' } | ForEach-Object IPAddress)
    $targets = $own | ForEach-Object { $p = $_.Split('.'); 1..254 | ForEach-Object { "$($p[0]).$($p[1]).$($p[2]).$_" } } |
        Select-Object -Unique | Where-Object { $own -notcontains $_ }
    $probes = foreach ($t in $targets) { $c = New-Object Net.Sockets.TcpClient; [pscustomobject]@{ Ip = $t; Client = $c; Task = $c.ConnectAsync($t, 5555) } }
    $deadline = (Get-Date).AddSeconds(2)
    while ((Get-Date) -lt $deadline -and @($probes | Where-Object { -not $_.Task.IsCompleted }).Count) { Start-Sleep -Milliseconds 100 }
    $found = @($probes | Where-Object { $_.Task.Status -eq 'RanToCompletion' } | ForEach-Object Ip)
    $probes | ForEach-Object { $_.Client.Close() }
    return $found
}

if (-not $TvHost) {
    Write-Host 'Looking for the TV on the network...'
    $found = @(Find-Tv)
    if ($found.Count -eq 1) {
        $TvHost = $found[0]
        Write-Host "Found $TvHost"
    } else {
        if ($found.Count) { Write-Host "Devices with network debugging on: $($found -join ', ')" }
        else { Write-Host 'No TV with network debugging on was found. On the TV: Settings > Device Preferences > About, press Build 7 times, then turn on USB debugging (and Network debugging if listed) under Developer options. The IP is under Settings > Network.' }
        $TvHost = (Read-Host "TV's IP address").Trim()
        if (-not $TvHost) { throw 'no TV address given' }
    }
}
$env:TV_HOST = $TvHost

if (-not $Apk) {
    $Apk = Join-Path $tools 'kavunOS.apk'
    Write-Host 'Downloading the latest kavunOS...'
    Invoke-WebRequest "$repo/releases/latest/download/kavunOS.apk" -UseBasicParsing -OutFile $Apk
}

Write-Host "Connecting to $TvHost; if the TV asks to allow debugging, tick 'Always allow' and press Allow."
$model = (& $py -I $tv 'getprop ro.product.model') -join ' '
if ($LASTEXITCODE -ne 0) { throw "could not connect to $TvHost`: check the IP, that debugging is on, and accept the prompt on the TV" }
Write-Host "Connected: $($model.Trim())"

# Which app owns Home differs by build; ask the TV, but only accept a launcher we recognise (the answer
# can also be Android's chooser "android", the setup wizard, or another third-party launcher).
$knownStock = @('com.google.android.tvlauncher', 'com.google.android.apps.tv.launcherx', 'com.google.android.leanbacklauncher', 'com.mitv.tvhome')
if ($Reinstall) {
    # kavunOS may be Home with the stock launcher disabled: bring the stock one back before removing it, so the TV
    # is never left without a home screen.
    (@($knownStock | ForEach-Object { "pm enable --user 0 $_ 2>/dev/null; echo enabled $_" }) + 'pm uninstall com.kavunos.tv') |
        & $py -I $tv --batch | Out-Host
}
if (-not $StockLauncher) {
    $homePkg = & $py -I $tv 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | Select-Object -Last 1
    if ($LASTEXITCODE -ne 0 -or -not $homePkg) { throw 'could not ask the TV which launcher owns Home; check -TvHost and the debugging prompt, or pass -StockLauncher <package>' }
    $homePkg = "$homePkg".Trim().Split('/')[0]
    if ($knownStock -contains $homePkg) { $StockLauncher = $homePkg }
    elseif ($homePkg -eq 'com.kavunos.tv') { $StockLauncher = 'none'; Write-Host 'Home already belongs to this launcher; nothing will be disabled.' }
    elseif ($homePkg -eq 'android') {
        # Android's chooser: launchers tie and no default is set (a restore followed by a re-applied list
        # ends here). Take the one known stock launcher among the HOME claimants, if there is exactly one.
        $claimants = (& $py -I $tv 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME') -join "`n"
        $found = @($knownStock | Where-Object { $claimants -match ([regex]::Escape($_) + '/') })
        if ($found.Count -eq 1) { $StockLauncher = $found[0]; Write-Host "Home is Android's chooser; the stock launcher among the candidates is $StockLauncher." }
        else { throw "Home is Android's launcher chooser and $($found.Count) known stock launchers claim Home: pick a default on the TV first, or run again with -StockLauncher <package to disable> or -StockLauncher none." }
    }
    else { throw "Home currently resolves to '$homePkg', which is not a stock launcher this script knows (android = launcher chooser, com.google.android.tungsten.setupwraith = setup wizard, see README Troubleshooting; anything else = another launcher is the default). Run again with -StockLauncher <package to disable> or -StockLauncher none." }
}
Write-Host "Stock launcher: $StockLauncher"
$disableStock = if ($StockLauncher -eq 'none') { 'echo stock launcher left alone' } else { "pm disable-user --user 0 $StockLauncher" }

& $py -I (Join-Path $tools 'b64push.py') $Apk /data/local/tmp/tvhome.apk
if ($LASTEXITCODE -ne 0) { throw 'APK upload failed' }
$out = & $py -I $tv 'pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk'
Write-Host $out
if ("$out" -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') { throw 'the TV has a kavunOS signed by another PC; run again with -Reinstall (its settings and phone-added wallpapers are lost)' }
if ("$out" -notmatch 'Success') { throw 'pm install failed; the stock launcher was left untouched' }
# Android 9 only accepts a component here; Android 10+ also takes a bare package name.
$out = & $py -I $tv 'cmd package set-home-activity --user 0 com.kavunos.tv/.MainActivity'
Write-Host $out
if ("$out" -match 'Error') { throw 'could not set the new launcher as home; the stock launcher was left untouched' }
# Start the launcher on its own, before the stock Home is touched (see build-and-install.sh, #239):
# in the batch a failed start only prints an Error line and the stock launcher would be disabled anyway.
$start = (& $py -I $tv 'am start -W -n com.kavunos.tv/.MainActivity') -join "`n"
Write-Host $start
if ($start -notmatch 'Status: ok' -or $start -match '(?m)^Error') { throw "the new launcher did not start (see above); $StockLauncher was left enabled" }
@(
    'pm grant com.kavunos.tv android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted',
    'cmd notification allow_listener com.kavunos.tv/com.kavunos.tv.MediaListener 2>/dev/null; echo media-sessions-granted',
    'cmd package compile -m speed -f com.kavunos.tv',
    $disableStock,
    'cmd package set-home-activity --user 0 com.kavunos.tv/.MainActivity'
) | & $py -I $tv --batch
# Order matters, see build-and-install.sh: start the new launcher, disable the stock one, set the
# preference last, then check where Home ended up and put the stock launcher back if it is not ours.
$homeNow = (& $py -I $tv 'cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN' | Select-Object -Last 1).Trim()
if ($homeNow -notlike 'com.kavunos.tv/*') {
    if ($StockLauncher -ne 'none') {
        & $py -I $tv "pm enable --user 0 $StockLauncher" | Out-Null
        # set-home-activity needs pkg/activity on Android 9, so resolve the stock component on the TV.
        & $py -I $tv "cmd package set-home-activity --user 0 `$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $StockLauncher | tail -n 1)" | Out-Null
    }
    throw "Home resolves to '$homeNow', not to the new launcher; $StockLauncher was re-enabled. On Google TV this usually means com.google.android.tungsten.setupwraith also claims Home: add it to your debloat list (see README, Troubleshooting), apply it, then run this script again."
}
Write-Host "Home now resolves to $homeNow"
# Optional accessibility services, appended to enabled_accessibility_services without dropping others.
function Enable-Service([string]$Name) {
    $svc = "com.kavunos.tv/.$Name"
    # Through stdin: Windows PowerShell 5.1 drops the inner double quotes from a native argument, and the
    # TV's shell then fails with "no closing quote" while the service stays off.
    ('s=$(settings get secure enabled_accessibility_services); case "$s" in *' + $Name + '*) ;; null|"") settings put secure enabled_accessibility_services ' + $svc + ' ;; *) settings put secure enabled_accessibility_services "$s:' + $svc + '" ;; esac; settings put secure accessibility_enabled 1; echo ' + $Name + ' enabled') |
        & $py -I $tv --batch
}
if ($InputKeyService) { Enable-Service 'InputKeyService' }
if ($HomeKeyService) { Enable-Service 'HomeKeyService' }
Write-Host 'Installed. Press Home on the remote to see it.'
