<#
.SYNOPSIS
    Install a DriveHome APK on the shared emulator and screenshot it. Safe to call from several
    agents at once: a machine-wide mutex serializes access to the emulator.

.PARAMETER Apk      APK to install (e.g. <worktree>\out\drivehome.apk)
.PARAMETER Out      PNG path for the main screenshot
.PARAMETER Actions  Optional adb shell commands run after the home screen is up, each followed by
                    a screenshot named <Out>-<n>.png. Example:
                      -Actions 'input tap 1168 600','input keyevent KEYCODE_BACK'
                    (coordinates are screen pixels, 1280x720, app content is y=58..662)
.PARAMETER Gfx      Also print `dumpsys gfxinfo` frame stats for the home screen (relative only:
                    the emulator is much faster than the head unit)

Emulator = head unit geometry: 1280x720 @160dpi, font scale 0.85, content area 1280x604
(y 58..662). It does NOT have the car apps; tiles fall back to stock apps.
#>
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [Parameter(Mandatory = $true)][string]$Out,
    [string[]]$Actions = @(),
    [switch]$Gfx
)
# 'Continue': adb writes progress to stderr, which PS 5.1 would turn into terminating errors.
$ErrorActionPreference = 'Continue'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$dev = 'emulator-5554'
$pkg = 'com.drivehome.launcher'
if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }
New-Item -ItemType Directory -Force (Split-Path $Out -Parent) | Out-Null

function Shot([string]$path) {
    & $adb -s $dev shell screencap -p /sdcard/__p.png | Out-Null
    & $adb -s $dev pull /sdcard/__p.png $path 2>&1 | Out-Null
    & $adb -s $dev shell rm /sdcard/__p.png | Out-Null
    Write-Host "screenshot: $path"
}

$mutex = New-Object System.Threading.Mutex($false, 'Global\DriveHomeEmulatorPreview')
Write-Host 'waiting for emulator lock...'
try { [void]$mutex.WaitOne() }
catch [System.Threading.AbandonedMutexException] {
    # A previous holder was killed mid-session; we now own the lock, carry on.
    Write-Host 'previous emulator session was interrupted; continuing'
}
try {
    & $adb -s $dev uninstall $pkg 2>&1 | Out-Null          # different worktrees = different versionCodes
    $r = (& $adb -s $dev install $Apk 2>&1) -join ' '
    if ($r -notmatch 'Success') { throw "install failed: $r" }
    & $adb -s $dev shell pm grant $pkg android.permission.ACCESS_FINE_LOCATION 2>&1 | Out-Null
    & $adb -s $dev shell cmd notification allow_listener "$pkg/$pkg.MediaListener" 2>&1 | Out-Null
    & $adb -s $dev shell cmd package set-home-activity "$pkg/.HomeActivity" 2>&1 | Out-Null
    & $adb -s $dev logcat -c
    & $adb -s $dev shell am start -a android.intent.action.MAIN -c android.intent.category.HOME 2>&1 | Out-Null
    Start-Sleep -Milliseconds 3500
    if ($Gfx) { & $adb -s $dev shell dumpsys gfxinfo $pkg reset | Out-Null }
    Shot $Out
    $i = 1
    foreach ($a in $Actions) {
        & $adb -s $dev shell $a 2>&1 | Out-Null
        Start-Sleep -Milliseconds 1200
        Shot ([IO.Path]::ChangeExtension($Out, $null).TrimEnd('.') + "-$i.png")
        $i++
    }
    if ($Gfx) {
        & $adb -s $dev shell dumpsys gfxinfo $pkg | Select-String -Pattern 'Total frames|Janky|percentile|Number' | ForEach-Object { $_.Line.Trim() }
    }
    $crash = & $adb -s $dev logcat -d -b crash 2>&1 | Select-String $pkg
    if ($crash) { Write-Host 'CRASH:' -ForegroundColor Red; & $adb -s $dev logcat -d -b crash | Select-Object -Last 40 }
    # Leave the emulator on its home screen for the next agent.
    & $adb -s $dev shell input keyevent KEYCODE_HOME 2>&1 | Out-Null
}
finally {
    $mutex.ReleaseMutex()
}
