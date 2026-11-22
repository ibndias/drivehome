<#
.SYNOPSIS
    Take a correctly oriented screenshot from the head unit (or emulator).

.DESCRIPTION
    The TS18 head unit uses a software-rotation hack (persist.sf.forcerotation=2,
    ro.sf.swrotation=90): `screencap` returns the 720x1280 portrait-native panel buffer
    squeezed into a 1280x720 PNG, so raw screenshots look sideways and stretched.
    This rotates 90 deg clockwise and resizes back to the logical 1280x720.
    Use -Raw for devices without the quirk (e.g. the emulator).
#>
param(
    [string]$Out = '',
    [string]$Device = '10.37.78.13:5555',
    [switch]$Raw
)
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not $Out) {
    $dir = Join-Path (Split-Path $PSScriptRoot -Parent) 'screenshots'
    New-Item -ItemType Directory -Force $dir | Out-Null
    $Out = Join-Path $dir ("shot-{0:yyyyMMdd-HHmmss}.png" -f (Get-Date))
}
$tmp = [IO.Path]::ChangeExtension($Out, '.raw.png')
& $adb -s $Device shell screencap -p /sdcard/__shot.png | Out-Null
& $adb -s $Device pull /sdcard/__shot.png $tmp | Out-Null
& $adb -s $Device shell rm /sdcard/__shot.png | Out-Null
if (-not (Test-Path $tmp)) { Write-Error "screenshot failed (is $Device connected?)"; exit 1 }
if ($Raw) { Move-Item -Force $tmp $Out; $Out; exit 0 }

Add-Type -AssemblyName System.Drawing
$img = [System.Drawing.Image]::FromFile($tmp)
$img.RotateFlip([System.Drawing.RotateFlipType]::Rotate90FlipNone)
$dst = New-Object System.Drawing.Bitmap 1280, 720
$g = [System.Drawing.Graphics]::FromImage($dst)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.DrawImage($img, 0, 0, 1280, 720)
$g.Dispose(); $img.Dispose()
$dst.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png); $dst.Dispose()
Remove-Item $tmp
$Out
