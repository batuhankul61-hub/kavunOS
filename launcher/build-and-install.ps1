# Windows version of build-and-install.sh: build the launcher APK and install it on the TV as the
# default home screen (the install half is install.ps1). Uses Android Studio's bundled JDK and the
# default SDK location unless JAVA_HOME / ANDROID_HOME are set.
#
# Usage: launcher\build-and-install.ps1 [-TvHost 192.168.1.50] [-StockLauncher <package>|none]
#        Without -TvHost the local network is scanned for the TV.
#        The stock launcher is detected from the TV when -StockLauncher is not given.
#        [-InputKeyService]   remote's Input button opens the input switcher (Google TV), plus config.xml
#                             input_key_keycodes and app_keys; re-pass on every install
#        [-HomeKeyService]    remotes whose Home button sends KEYCODE_ALL_APPS (Xiaomi sticks)
#        [-PhoneUpload]       "Add from phone" in the Wallpapers dialog; the only feature that needs the
#                             network permission, so it is left out unless asked for
#        [-Reinstall]         uninstall first, when the TV has a build signed by another PC
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$StockLauncher = '',
    [switch]$InputKeyService,
    [switch]$HomeKeyService,
    [switch]$PhoneUpload,
    [switch]$Reinstall
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jbr = Get-ChildItem 'C:\Program Files\Android' -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'jbr' } | Where-Object { Test-Path "$_\bin\java.exe" } | Select-Object -First 1
    if (-not $jbr) { throw 'Set JAVA_HOME to a JDK 17+ (Android Studio ships one in its jbr folder), or install the release APK without building: launcher\install.ps1' }
    $env:JAVA_HOME = $jbr
}
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path local.properties)) {
    "sdk.dir=$($sdk -replace '\\', '\\')" | Out-File -Encoding ascii local.properties
}

.\gradlew.bat assembleRelease -q --console=plain "-PphoneUpload=$($PhoneUpload.IsPresent.ToString().ToLower())"
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }

& .\install.ps1 -TvHost $TvHost -Apk "$PSScriptRoot\app\build\outputs\apk\release\app-release.apk" -StockLauncher $StockLauncher `
    -InputKeyService:$InputKeyService -HomeKeyService:$HomeKeyService -Reinstall:$Reinstall
