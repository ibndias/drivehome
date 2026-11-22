# The head unit (TS18) — facts, access, and tweaks applied

Everything here was measured on the actual unit over ADB on 2026-11-22.

## Hardware / OS
| | |
|---|---|
| Platform | Topway **TS18**, Unisoc **UIS8581A** (`sp9863a`, board `uis8581a2h10`) |
| CPU | 8× Cortex-A55 (4 × 1.6 GHz + 4 × 1.2 GHz), PowerVR GPU — weak; avoid animations/overdraw |
| RAM | 3 GB (2.8 GB visible) + 1.5 GB zram swap |
| Android | 10 (API 29), build `QP1A.190711.020`, dated 2025-05-20 |
| Screen | **1280×720 landscape**, density **160** (1 dp = 1 px), font scale 0.85 |
| System bars | status bar 58 px top, nav bar 58 px bottom → app area **1280×604** |
| Model string | `s9863a1h10_Natv` |

### Screenshot quirk
`persist.sf.forcerotation=2`, `ro.sf.swrotation=90`: SurfaceFlinger rotates in software for a
portrait-native panel. Apps see a normal 1280×720 landscape display, but `screencap` returns the
portrait buffer squeezed into 1280×720 (looks sideways). `tools/screenshot.ps1` fixes it.
**Don't force `screenOrientation` in the manifest** — leave it `unspecified`.

## Enable ADB over Wi-Fi (needed for every deploy — resets on reboot)
No USB: the unit's USB ports are host-only. Over Wi-Fi instead:

1. Head unit and PC on the same network (phone hotspot works).
2. Settings → **Factory settings** → code **`123456`**
   (alternatives seen on TS10/TS18: `8888`, or `7890` + current hour in 24 h, e.g. `789014`).
3. **Engineer Mode → Debug & Log → ADB shell**, type, press **Start**:
   `setprop service.adb.tcp.port 5555`
4. Developer options → toggle **USB debugging** off → on (restarts adbd on port 5555).
   The shell only shows output for read commands like `getprop`; `setprop` printing nothing is normal.
   Check: `getprop service.adb.tcp.port` → `5555`, `getprop init.svc.adbd` → `running`.
5. On the PC: `.\deploy.ps1 -Scan` (or `-Ip <address>`). First time from a new PC, tap **Allow**
   ("Always allow from this computer") on the head unit.
6. When finished, turn USB debugging off (or just reboot).

## Default apps on this unit (component names)
| App | Component |
|---|---|
| Original launcher (Dofun) | `com.dofun.variety/com.dofun.overseasvariety.Launcher` |
| Google Maps (user's nav app) | `com.google.android.apps.maps/com.google.android.maps.MapsActivity` |
| Music | `com.tw.music/.MusicActivity` |
| Radio | `com.tw.radio/.RadioActivity` |
| Bluetooth / phone | `com.tw.bt/.ATBluetoothActivity` |
| Zlink (wireless CarPlay / Android Auto) | `com.zjinnova.zlink/com.zjinnova.android.zlink.features.main.MainActivity` |
| YouTube | `com.google.android.youtube/.app.honeycomb.Shell$HomeActivity` |
| 360 / front+rear cameras | `com.baony.tw360/com.baony.ui.activity.TopwayBVActivity` |
| Video | `com.tw.video/.VideoActivity` |
| Car settings | `com.dofun.carsetting/.ui.MainActivity` |
| EQ / DSP | `com.tw.eq/.DSPActivity` |
| Steering-wheel keys | `com.tw.keypad/.SteeringWheelActivity` |

## Why it was slow (findings)
- **360 camera app** (`com.baony.tw360`) keeps camera 0 open and streams 24/7 (~60 % of one core via
  `camera.provider`). It drives the **front + rear cameras**, so it's intentionally left running.
- **Log spam**: ~480 lines/s (camera frame combiner + GNSS) → logd CPU.
- **Dofun launcher embeds a live Google Maps view** (~300 MB) + itself ~130 MB.
- Google app, Android Auto app, Play Store background, factory test tools all auto-start.
- Uptime of days without a real reboot → 780 MB in zram swap.
- The `load average` of ~10 is misleading: Wi-Fi/BT driver threads (`sdiohal_*`, `marlin3e_*`)
  sit in D-state; real CPU use is ~20 %.

## Tweaks applied on 2026-11-22 (and how to undo)
| Change | Undo (in `adb shell`) |
|---|---|
| `setprop persist.log.tag W` (and `log.tag W`) — drops debug/info logs | `setprop persist.log.tag ""` |
| Disabled `com.dofun.market` (Dofun app store) | `pm enable com.dofun.market` |
| Disabled `com.abupdate.fota_demo_iot` (FOTA demo updater) | `pm enable com.abupdate.fota_demo_iot` |
| Disabled `com.google.android.googlequicksearchbox` (Google app / Assistant; Maps mic search may not work) | `pm enable com.google.android.googlequicksearchbox` |
| Disabled `com.google.android.projection.gearhead` (Android Auto app; Zlink doesn't need it) | `pm enable com.google.android.projection.gearhead` |
| Disabled `com.sprd.autoslt`, `com.sprd.validationtools` (factory tests) | `pm enable <pkg>` |
| Uninstalled Angry Birds Friends, Baba Is You, Netflix, PicFolder, GPS Test, Waze | reinstall from Play Store |
| Animation scales 0.5× (done by hand in Developer options) | Developer options |
