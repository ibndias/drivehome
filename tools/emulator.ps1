<#
.SYNOPSIS
    Create (once) and start an emulator that matches the TS18 head unit, for working without the car.

.DESCRIPTION
    Head unit: Android 10 (API 29), 1280x720 landscape, 160 dpi, font scale 0.85.
    The emulator won't have the car apps (com.tw.*, Zlink, 360 camera...) — DriveHome
    skips missing default tiles and backfills with whatever is installed.
    Needs the image (new Android CLI uses slashes; semicolons break in cmd.exe):
        cmd /c "echo y| %LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat --install system-images/android-29/default/x86_64"

.EXAMPLE
    .\tools\emulator.ps1            # create if missing, then boot
.EXAMPLE
    .\tools\emulator.ps1 -Recreate  # rebuild the AVD from scratch
#>
param([string]$Name = 'HeadUnit_TS18', [switch]$Recreate)
$ErrorActionPreference = 'Stop'
$sdk  = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$img  = 'system-images;android-29;default;x86_64'
$avd  = Join-Path $env:USERPROFILE ".android\avd\$Name.avd"
$adb  = Join-Path $sdk 'platform-tools\adb.exe'
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

if (-not (Test-Path (Join-Path $sdk 'system-images\android-29\default\x86_64'))) {
    throw "Missing emulator image. Install it with:`n  cmd /c `"echo y| `"$sdk\cmdline-tools\latest\bin\sdkmanager.bat`" --install system-images/android-29/default/x86_64`""
}

if ($Recreate -or -not (Test-Path $avd)) {
    Write-Host "Creating AVD $Name"
    'no' | & (Join-Path $sdk 'cmdline-tools\latest\bin\avdmanager.bat') create avd -n $Name -k $img -d 'tv_720p' --force | Out-Host
    $ini = Join-Path $avd 'config.ini'
    $cfg = Get-Content $ini | Where-Object { $_ -notmatch '^(hw\.lcd\.|hw\.initialOrientation|hw\.keyboard|hw\.ramSize|hw\.gpu\.|showDeviceFrame|skin\.|hw\.mainKeys|hw\.dPad)' }
    $cfg += @(
        'hw.lcd.width=1280', 'hw.lcd.height=720', 'hw.lcd.density=160',
        'hw.initialOrientation=landscape', 'hw.keyboard=yes', 'hw.ramSize=3072',
        'hw.gpu.enabled=yes', 'hw.gpu.mode=auto', 'hw.mainKeys=no', 'hw.dPad=no',
        'showDeviceFrame=no', 'skin.dynamic=yes', 'skin.name=1280x720', 'skin.path=_no_skin'
    )
    Set-Content -Encoding ascii $ini $cfg
}

Write-Host "Starting $Name (first boot takes a minute or two)..."
Start-Process (Join-Path $sdk 'emulator\emulator.exe') -ArgumentList @('-avd', $Name, '-no-snapshot-save', '-no-boot-anim')
& $adb wait-for-device
do { Start-Sleep 2; $b = (& $adb -e shell getprop sys.boot_completed 2>$null) } until ("$b".Trim() -eq '1')
& $adb -e shell settings put system font_scale 0.85 | Out-Null
& $adb -e shell settings put system show_touches 1 | Out-Null
Write-Host 'Emulator ready. Deploy with:  .\deploy.ps1 -Serial emulator-5554 -Build' -ForegroundColor Green
