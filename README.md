# DriveHome

A fast, minimal home screen (launcher) for a **TS18 Android car head unit**
(Unisoc UIS8581A, Android 10, 1280×720 @ 160 dpi). Plain Java, no libraries, no Gradle,
no animations, no live widgets — built to stay smooth on a weak chip.

The home screen is a "cockpit": what the driver glances at is the hero, apps are one tap away.

- Speed gauge (GPS): large numeral inside a 0–160 km/h arc, redrawn only when the value changes.
- Clock + date, and a now-playing card with large prev / play-pause / next (tap the card = open the player).
- Dock of 8 pinned apps + **All apps** drawer.
  - Long-press an app in the drawer → **Pin to home screen** (pick the slot to replace) / App info.
  - Long-press a dock app → choose another app for the slot (an app already on home swaps places),
    or **Remove from home** (the slot goes back to its default).
- Long-press the gauge or the clock → original launcher / choose home app / Android settings /
  check for updates / move the gauge to the other side (left- or right-hand drive).

## Work anywhere, go to the car only to deploy

| Task | Needs the car? | Command |
|---|---|---|
| Edit code | no | `src\com\drivehome\launcher\*.java`, `AndroidManifest.xml`, `res\` |
| Build APK | no | `.\build.ps1` → `out\drivehome.apk` |
| Test on an emulator that matches the unit | no | `.\tools\emulator.ps1` then `.\deploy.ps1 -Serial emulator-5554 -Build` |
| Install on the head unit | **yes** | enable ADB over Wi-Fi ([docs/HEADUNIT.md](docs/HEADUNIT.md#enable-adb-over-wi-fi-needed-for-every-deploy--resets-on-reboot)), then `.\deploy.ps1 -Scan -Build` |
| Screenshot the head unit | yes | `.\tools\screenshot.ps1` (fixes the unit's sideways screencap) |
| Put the original launcher back | yes | `.\deploy.ps1 -Revert` |

Run scripts from this folder in PowerShell. If scripts are blocked:
`powershell -ExecutionPolicy Bypass -File .\deploy.ps1 -Scan -Build`.

## Requirements (this PC already has them)
- Android SDK at `%LOCALAPPDATA%\Android\Sdk` with build-tools 35.0.0, platform android-35, platform-tools.
- JDK: Android Studio's bundled `jbr` (path is a parameter in `build.ps1`).
- Emulator (optional): `system-images/android-29/default/x86_64`.

## Signing — important
Android only accepts an update signed with the **same key** as the installed copy.
`build.ps1` signs with `%USERPROFILE%\.android\drivehome-release.jks` (password in
`drivehome-release.properties` next to it). These live **outside the repo — never commit them.**
Back them up; if they're lost, the next install needs `adb uninstall com.drivehome.launcher` first
(tile layout resets). CI can sign with the same key via the `DRIVEHOME_KEYSTORE` /
`DRIVEHOME_STORE_PASS` environment variables.

## Layout
```
AndroidManifest.xml        app manifest (min/target SDK 29 are passed by build.ps1)
res/                       theme + icon (UI itself is built in code, no layouts)
src/com/drivehome/launcher Java sources
build.ps1                  Gradle-free build: aapt2 → javac → d8 → zipalign → apksigner
deploy.ps1                 connect, install, grant permissions, set as home
tools/screenshot.ps1       correctly rotated screenshots from the unit
tools/emulator.ps1         creates/boots an emulator matching the unit
docs/HEADUNIT.md           device facts, how to enable ADB, tweaks applied + undo commands
```
