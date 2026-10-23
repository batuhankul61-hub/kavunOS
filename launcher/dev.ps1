# Develop kavunOS on this PC: an Android TV emulator instead of a TV. Starts the emulator if it is not running
# (first run creates the virtual TV), builds a debug APK (no R8, so it builds fast), installs it, makes it Home
# and opens it. Run it again after every change. Arrows, Enter and Esc on the keyboard act as the remote.
#
# Usage: launcher\dev.ps1            build, install, open
#        launcher\dev.ps1 -Fresh     wipe the virtual TV first (a clean first start)
#        launcher\dev.ps1 -Logs      afterwards, follow kavunOS's log (Ctrl+C to stop)
#        launcher\dev.ps1 -Theme themes\scenes   also push a theme (config.json and wallpapers)
#
# Needs: the Android SDK emulator and system-images;android-30;android-tv;x86, and Windows Hypervisor Platform.
param(
    [switch]$Fresh,
    [switch]$Logs,
    [string]$Theme = ''
)
if ($Theme) { $Theme = (Resolve-Path $Theme).Path }
# Windows PowerShell 5.1 turns any stderr line of a native tool (adb, avdmanager) into an error, so failures are
# checked by exit code and by what the tools report instead.
$ErrorActionPreference = 'Continue'
Set-Location $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adb = "$sdk\platform-tools\adb.exe"
$emulator = "$sdk\emulator\emulator.exe"
$avd = 'kavunOS_TV'
$image = 'system-images;android-30;android-tv;x86'
if (-not (Test-Path $emulator)) { throw "no emulator: run  $sdk\cmdline-tools\latest\bin\sdkmanager.bat emulator `"$image`"" }

# The virtual TV: 1080p Android TV 11, the same Android as the Mi TV Stick 4K.
$avds = & $emulator -list-avds
if ($avds -notcontains $avd) {
    'no' | & "$sdk\cmdline-tools\latest\bin\avdmanager.bat" create avd -n $avd -k $image -d tv_1080p 2>$null | Out-Null
    if ((& $emulator -list-avds) -notcontains $avd) { throw "could not create the virtual TV $avd" }
    $ini = "$env:USERPROFILE\.android\avd\$avd.avd\config.ini"
    Add-Content $ini "hw.ramSize=2048`nhw.keyboard=yes`nhw.gpu.enabled=yes`nhw.gpu.mode=auto"
    Write-Host "Created the virtual TV $avd"
}

& $adb start-server 2>$null
$running = (& $adb devices) -match '^emulator-\d+\s+device'
if ($Fresh -and $running) { & $adb -e emu kill | Out-Null; Start-Sleep 3; $running = $false }
if (-not $running) {
    $emuArgs = @('-avd', $avd, '-netdelay', 'none', '-netspeed', 'full')
    if ($Fresh) { $emuArgs += '-wipe-data' }
    Start-Process $emulator -ArgumentList $emuArgs
    Write-Host 'Starting the virtual TV...'
    & $adb wait-for-device
    while ((& $adb -e shell getprop sys.boot_completed 2>$null) -notmatch '1') { Start-Sleep 2 }
    & $adb -e shell settings put global window_animation_scale 0.5
    & $adb -e shell settings put global transition_animation_scale 0.5
    & $adb -e shell settings put global animator_duration_scale 0.5
}

$env:ANDROID_SERIAL = ((& $adb devices) -match '^emulator-\d+' | Select-Object -First 1) -replace '\s.*$', ''
.\gradlew.bat installDebug -q --console=plain
if ($LASTEXITCODE -ne 0) { throw 'build or install failed' }
& $adb shell cmd package set-home-activity com.kavunos.tv/.MainActivity | Out-Null
& $adb shell cmd notification allow_listener com.kavunos.tv/com.kavunos.tv.MediaListener 2>$null | Out-Null
& $adb shell am start -n com.kavunos.tv/.MainActivity | Out-Null
# The emulator's adbd listens on 127.0.0.1:5555, where the TV tools connect as they do to a real TV.
if ($Theme) { & "$PSScriptRoot\..\tools\theme.ps1" -TvHost 127.0.0.1 $Theme }
Write-Host "kavunOS is on the virtual TV ($env:ANDROID_SERIAL)."
if ($Logs) { & $adb logcat -v brief 'TvHome*:V' 'kavunOS*:V' 'AndroidRuntime:E' '*:S' }
