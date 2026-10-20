# Build the cyberpunk flavor (dev.tleyton.cyberhome) and install it on the TV as the default home
# screen, using plain adb from the Android SDK's platform-tools instead of tools/tv.py.
#
# Usage: launcher\build-and-install-cyberpunk.ps1 -TvHost 192.168.1.50 [-StockLauncher <package>|none] [-NoBuild] [-KeepSetupWizard]
#        The stock launcher is detected from the TV when -StockLauncher is not given.
#        On Google TV the setup wizard (com.google.android.tungsten.setupwraith) also claims Home with a
#        higher priority than any third-party launcher, so it is disabled too unless -KeepSetupWizard.
#        The TV needs ADB debugging enabled (Developer options > USB/network debugging).
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$StockLauncher = '',
    [switch]$NoBuild,
    [switch]$KeepSetupWizard
)
$ErrorActionPreference = 'Stop'
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
Set-Location $PSScriptRoot

$pkg = 'dev.tleyton.cyberhome'
$component = "$pkg/com.gathin.tvhome.MainActivity"

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jdk = @('C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Microsoft') |
        ForEach-Object { Get-ChildItem $_ -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue } |
        Where-Object { Test-Path "$($_.FullName)\bin\java.exe" } | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $jdk) { throw 'Set JAVA_HOME to a JDK 17 (Temurin or Microsoft OpenJDK 17 are looked up automatically)' }
    $env:JAVA_HOME = $jdk.FullName
}
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path local.properties)) {
    "sdk.dir=$($sdk -replace '\\', '\\')" | Out-File -Encoding ascii local.properties
}
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "adb not found at $adb; install platform-tools in the Android SDK or set ANDROID_HOME" }

if (-not $NoBuild) {
    .\gradlew.bat assembleCyberpunkRelease -q --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }
}
$apk = 'app\build\outputs\apk\cyberpunk\release\app-cyberpunk-release.apk'
if (-not (Test-Path $apk)) { throw "APK not found: $apk (run without -NoBuild)" }

$serial = if ($TvHost -match ':\d+$') { $TvHost } else { "${TvHost}:5555" }
# Run adb and return its output lines. stderr is merged in, so errors must not be terminating here.
function Invoke-Adb {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $lines = & $adb -s $serial @args 2>&1 | ForEach-Object { "$_".TrimEnd() } }
    finally { $ErrorActionPreference = $prev }
    return @($lines | Where-Object { $_ -ne '' })
}

$prev = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$connect = & $adb connect $serial 2>&1 | ForEach-Object { "$_" }
$devices = & $adb devices 2>&1 | ForEach-Object { "$_" }
$ErrorActionPreference = $prev
Write-Host ($connect -join "`n")
$state = ($devices | Where-Object { $_ -match ('^' + [regex]::Escape($serial) + '\s+(\S+)') } | ForEach-Object { $Matches[1] } | Select-Object -First 1)
if ($state -eq 'unauthorized') { throw "The TV at $serial has not authorized this PC; accept the 'Allow USB debugging' prompt on the TV and run again." }
if ($state -ne 'device') { throw "Could not reach $serial through adb (state: $(if ($state) { $state } else { 'not listed' })). Enable ADB debugging on the TV (Developer options) and check -TvHost." }

# Which app owns Home differs by build; ask the TV, but only accept a launcher we recognise (the answer
# can also be Android's chooser "android", the setup wizard, or another third-party launcher).
$knownStock = @('com.google.android.tvlauncher', 'com.google.android.apps.tv.launcherx', 'com.google.android.leanbacklauncher', 'com.mitv.tvhome')
if (-not $StockLauncher) {
    $homePkg = Invoke-Adb shell 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | Select-Object -Last 1
    if ($LASTEXITCODE -ne 0 -or -not $homePkg) { throw 'could not ask the TV which launcher owns Home; check -TvHost and the debugging prompt, or pass -StockLauncher <package>' }
    $homePkg = "$homePkg".Trim().Split('/')[0]
    if ($knownStock -contains $homePkg) { $StockLauncher = $homePkg }
    elseif ($homePkg -eq $pkg) { $StockLauncher = 'none'; Write-Host 'Home already belongs to this launcher; nothing will be disabled.' }
    elseif ($homePkg -eq 'android') {
        # Android's chooser: launchers tie and no default is set. Take the one known stock launcher
        # among the HOME claimants, if there is exactly one.
        $claimants = (Invoke-Adb shell 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME') -join "`n"
        $found = @($knownStock | Where-Object { $claimants -match ([regex]::Escape($_) + '/') })
        if ($found.Count -eq 1) { $StockLauncher = $found[0]; Write-Host "Home is Android's chooser; the stock launcher among the candidates is $StockLauncher." }
        else { throw "Home is Android's launcher chooser and $($found.Count) known stock launchers claim Home: pick a default on the TV first, or run again with -StockLauncher <package to disable> or -StockLauncher none." }
    }
    else { throw "Home currently resolves to '$homePkg', which is not a stock launcher this script knows (android = launcher chooser, com.google.android.tungsten.setupwraith = setup wizard, see README Troubleshooting; anything else = another launcher is the default). Run again with -StockLauncher <package to disable> or -StockLauncher none." }
}
Write-Host "Stock launcher: $StockLauncher"

# Install on its own: a failed install must not lead to the stock launcher being disabled.
$out = Invoke-Adb install -r (Resolve-Path $apk).Path
Write-Host ($out -join "`n")
if ($LASTEXITCODE -ne 0 -or ($out -join "`n") -notmatch 'Success') { throw 'adb install failed; the stock launcher was left untouched' }

# Order matters, see build-and-install.sh: start the new launcher, disable the stock one, set the
# preference last, then check where Home ended up and put the stock launcher back if it is not ours.
Invoke-Adb shell "pm grant $pkg android.permission.ACCESS_FINE_LOCATION" | Out-Null
Write-Host 'location-granted'
Invoke-Adb shell "cmd package compile -m speed -f $pkg" | Out-Null
Invoke-Adb shell "am start -W -n $component" | Where-Object { $_ -match '^(Status|Error)' } | ForEach-Object { Write-Host $_ }
if ($StockLauncher -eq 'none') { Write-Host 'stock launcher left alone' }
else { Invoke-Adb shell "pm disable-user --user 0 $StockLauncher" | ForEach-Object { Write-Host $_ } }
# Google TV: the setup wizard's RecoveryActivity claims HOME at priority 1 and Android picks the
# highest priority before it looks at the preferred activity, so it would win over us once the
# stock launcher is gone. Disable it too (reversible with pm enable). Nothing else is touched.
$setupWizard = 'com.google.android.tungsten.setupwraith'
$disabledWizard = $false
$claimants = (Invoke-Adb shell 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME') -join "`n"
if ($claimants -match ([regex]::Escape($setupWizard) + '/')) {
    if ($KeepSetupWizard) { Write-Host "$setupWizard claims Home but -KeepSetupWizard was given; it will probably win over the new launcher." }
    else {
        Invoke-Adb shell "pm disable-user --user 0 $setupWizard" | ForEach-Object { Write-Host $_ }
        $disabledWizard = $true
    }
}
Invoke-Adb shell "cmd package set-home-activity --user 0 $component" | ForEach-Object { Write-Host $_ }
# Disabling the stock launcher can make adbd drop the connection for a moment: reconnect before checking.
Start-Sleep -Seconds 3
$null = & $adb connect $serial 2>&1

$homeNow = "$(Invoke-Adb shell 'cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN' | Select-Object -Last 1)".Trim()
if ($homeNow -notlike "$pkg/*") {
    if ($StockLauncher -ne 'none') { Invoke-Adb shell "pm enable --user 0 $StockLauncher" | Out-Null }
    if ($disabledWizard) { Invoke-Adb shell "pm enable --user 0 $setupWizard" | Out-Null }
    throw "Home resolves to '$homeNow', not to the new launcher; what was disabled has been re-enabled. See docs/cyberpunk.md and README Troubleshooting."
}
Write-Host "Home now resolves to $homeNow"
Write-Host 'Installed. Press Home on the remote.'
