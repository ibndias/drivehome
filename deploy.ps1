<#
.SYNOPSIS
    Install DriveHome on the head unit (over Wi-Fi ADB) or on the emulator.

.DESCRIPTION
    The only step that needs the car. Before running, enable ADB over Wi-Fi on the
    head unit (see docs\HEADUNIT.md -> "Enable ADB over Wi-Fi"; it resets on every reboot).

    Steps: (optional build) -> connect -> install -> grant permissions ->
    allow media-session access -> set as default home -> open it.

.EXAMPLE
    .\deploy.ps1 -Build                 # build, then deploy to the head unit at the default IP
.EXAMPLE
    .\deploy.ps1 -Scan                  # find the head unit on the current Wi-Fi subnet first
.EXAMPLE
    .\deploy.ps1 -Serial emulator-5554  # deploy to the emulator instead
.EXAMPLE
    .\deploy.ps1 -Revert                # make the original Dofun launcher the home screen again
#>
[CmdletBinding()]
param(
    [string]$Ip = '10.37.78.13',
    [int]$Port = 5555,
    [string]$Serial = '',          # e.g. emulator-5554; skips Wi-Fi connect
    [switch]$Scan,                 # scan the PC's /24 subnet for an open ADB port
    [switch]$Build,                # run build.ps1 first
    [switch]$NoHome,               # install only, don't change the default home app
    [switch]$Revert                # set the original launcher back as home and stop
)
$ErrorActionPreference = 'Stop'
$Root      = $PSScriptRoot
$Adb       = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$Apk       = Join-Path $Root 'out\drivehome.apk'
$Pkg       = 'com.drivehome.launcher'
$HomeActivity = "$Pkg/.HomeActivity"   # (not $Home: that's a read-only automatic variable)
$Listener  = "$Pkg/$Pkg.MediaListener"
$Original  = 'com.dofun.variety/com.dofun.overseasvariety.Launcher'

function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Fail($m) { Write-Host "DEPLOY FAILED: $m" -ForegroundColor Red; exit 1 }
function AdbS { & $Adb -s $script:Target @args 2>&1 | ForEach-Object { "$_" } }

if (-not (Test-Path $Adb)) { Fail "adb not found at $Adb" }

if ($Build) {
    Step 'Building'
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Root 'build.ps1')
    if ($LASTEXITCODE -ne 0) { Fail 'build failed' }
}

if ($Scan -and -not $Serial) {
    Step 'Scanning Wi-Fi subnet for ADB port'
    $cfg = Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway } | Select-Object -First 1
    $me  = $cfg.IPv4Address.IPAddress; $base = ($me -replace '\.\d+$', '.')
    $tasks = @{}
    foreach ($i in 1..254) { $h = "$base$i"; if ($h -eq $me) { continue }
        $c = New-Object System.Net.Sockets.TcpClient; $tasks[$h] = @($c, $c.ConnectAsync($h, $Port)) }
    Start-Sleep -Milliseconds 2500
    $found = foreach ($k in $tasks.Keys) { $c, $t = $tasks[$k]; if ($t.Status -eq 'RanToCompletion' -and $c.Connected) { $k }; $c.Close() }
    if (-not $found) { Fail "no device with port $Port open on $base* - is ADB over Wi-Fi enabled on the head unit?" }
    $Ip = @($found)[0]; Write-Host "    found $Ip"
}

if ($Serial) { $script:Target = $Serial }
else {
    $script:Target = "${Ip}:$Port"
    Step "Connecting to $script:Target"
    & $Adb connect $script:Target | Out-Host
}

# Wait for authorization (the head unit shows "Allow USB debugging?" the first time).
$state = ''
foreach ($n in 1..40) {
    $state = ((& $Adb -s $script:Target get-state 2>&1) -join ' ').Trim()
    if ($state -eq 'device') { break }
    if ($n -eq 1 -and $state -match 'unauthorized') { Write-Host '    Tap "Allow" (tick "Always allow") on the head unit screen...' -ForegroundColor Yellow }
    if ($state -match 'offline|not found' -and -not $Serial) { & $Adb connect $script:Target | Out-Null }
    Start-Sleep -Seconds 3
}
if ($state -ne 'device') { Fail "device not ready (state: $state)" }

if ($Revert) {
    Step 'Restoring the original launcher as home'
    AdbS shell cmd package set-home-activity $Original | Out-Host
    AdbS shell am start -a android.intent.action.MAIN -c android.intent.category.HOME | Out-Null
    Write-Host 'Done. DriveHome is still installed (uninstall: adb uninstall com.drivehome.launcher).'
    exit 0
}

if (-not (Test-Path $Apk)) { Fail "APK not found: $Apk (run with -Build)" }

Step "Installing $([math]::Round((Get-Item $Apk).Length/1KB)) KB APK"
$out = AdbS install -r $Apk
$out | Out-Host
if (-not ($out -match 'Success')) {
    if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') { Fail 'signature mismatch: the installed copy was signed with a different key. Uninstall it first (adb uninstall com.drivehome.launcher) - tile layout will reset.' }
    Fail 'install failed'
}

Step 'Granting permissions'
AdbS shell pm grant $Pkg android.permission.ACCESS_FINE_LOCATION   | Out-Host
AdbS shell pm grant $Pkg android.permission.ACCESS_COARSE_LOCATION | Out-Host
AdbS shell cmd notification allow_listener $Listener              | Out-Host
# Let DriveHome install its own updates from GitHub (Android still asks for a one-tap confirm).
AdbS shell appops set $Pkg REQUEST_INSTALL_PACKAGES allow          | Out-Host

if (-not $NoHome) {
    Step 'Setting DriveHome as the default home app'
    AdbS shell cmd package set-home-activity $HomeActivity | Out-Host
}

Step 'Opening home'
AdbS shell am force-stop $Pkg | Out-Null
AdbS shell am start -a android.intent.action.MAIN -c android.intent.category.HOME | Out-Null

Write-Host ''
Write-Host "Deployed to $script:Target." -ForegroundColor Green
Write-Host 'Screenshot: .\tools\screenshot.ps1    Undo: .\deploy.ps1 -Revert'
