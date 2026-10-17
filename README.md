<p align="center">
  <img src="docs/icon.png" width="128" alt="Glow LED icon" />
</p>

<h1 align="center">Glow LED</h1>

<p align="center">
  A notification light for AMOLED phones. It glows around your camera hole, works on any brand, and uses very little battery.<br/>
  Android 10 and up.
</p>

<p align="center">
  <a href="../../releases/latest"><b>⬇ Download the latest APK</b></a>
</p>

---

Most phones no longer have a notification LED. Glow LED draws one around the front camera
(punch-hole, pill, notch, or a plain dot on phones without a cutout). It shows while the screen is on,
on the lock screen, and while the phone is "off", using a black always-on screen. On AMOLED, black pixels
are switched off, so only the light itself uses power.

## Features

| | |
|---|---|
| **Cutout aware** | Detects the camera punch-hole, pill or notch automatically (exact shape on Android 12+). Fine-tune by dragging, with sliders, or in 1 px steps. Keeps its position when the screen resolution changes (Samsung FHD+/QHD+). |
| **9 animations** | Pulse, Breathe, Blink, Heartbeat, Spinner, Rainbow, Static ring, Edge lighting, Edge pulse. Adjustable speed, thickness, gap, intensity and glow. |
| **Colours** | Per-app colour you pick, or the app's own notification colour, or a colour taken from its icon. Several notifications can take turns, split the ring into colour segments, or show only the newest. |
| **Screen off (always-on)** | Black screen at minimum brightness and the lowest refresh rate. Timeout, optional reminder pulses, pocket detection, burn-in protection. Tap to show the lock screen, double-tap to turn the screen off. |
| **Screen on** | A small light around the camera, also over the lock screen. |
| **Filters** | Turn individual apps on or off. Ignores silent, ongoing and progress notifications and group summaries. Respects Do Not Disturb. Can clear after you unlock. |
| **Battery** | Event-driven, so nothing runs in the background checking for notifications. 30 fps cap (configurable). Static and blink styles only redraw when they change. No foreground service. Low-battery cut-off, quiet hours, pause during calls. |
| **Any brand** | Shortcuts to the autostart/background settings on Xiaomi, Redmi, POCO, Samsung, OnePlus, OPPO, realme, vivo, iQOO, Huawei, Honor, ASUS, Tecno and Infinix. Reconnects itself if the system drops it. |
| **Extras** | Quick Settings tile, preview, test notification, themed (monochrome) icon. |

## Install

1. Download `GlowLED-vX.Y.Z.apk` from [Releases](../../releases/latest) and open it (allow "install unknown apps" for your browser or file manager).
2. Open **Glow LED** and work through the **Setup** list:
   - **Notification access** (required): lets the app know a notification arrived. Nothing leaves your phone.
   - **Accessibility overlay** (recommended): shows the light on the lock screen, wakes the always-on screen and turns the screen off again afterwards. It reads no screen content.
   - **Display over other apps**: alternative if you don't want accessibility (doesn't show over the lock screen on every phone).
   - **Unrestricted battery** and, on Xiaomi/OPPO/vivo/Huawei and similar, **Autostart**.
3. Tap **Camera position**, then **Auto-detect**, adjust if needed, and **Save**.
4. Tap **Test notification**, then turn the screen off.

> **Android 13+ "Restricted setting" dialog:** apps installed from an APK can't enable accessibility straight away.
> Open *Settings → Apps → Glow LED → ⋮ (top right) → Allow restricted settings*, then enable it again.

## How it works

```
NotificationListenerService ──► NotificationStore ──► LedController
   (system-bound, no polling)     (unseen notifications,     │
                                   one colour each)          ├── screen ON  → OverlayHost → LedView (small window around the camera)
                                                             └── screen OFF → AodActivity → LedView (black, min brightness, low Hz)
```

* **Turning the screen on and off:** `AodActivity` uses `showWhenLocked` and `turnScreenOn`. It is launched
  through the accessibility service or overlay permission (Android 10+ blocks background activity starts
  otherwise), with a full-screen-intent fallback. When the light times out, the accessibility service locks
  the screen (`GLOBAL_ACTION_LOCK_SCREEN`) so the panel switches off straight away.
* **Rendering:** `LedView` builds one `Path` for the cutout shape and animates with `PathMeasure` segments.
  It allocates nothing per frame and schedules frames only as often as needed: never for static styles,
  only on the on/off switch for blink, and at the fps cap for the rest.
* **Cutout detection:** `Display.getCutout()` (API 29), plus `DisplayCutout.getCutoutPath()` on API 31+ for
  the exact hole. Punch-hole bounding boxes that reach the top edge are corrected heuristically.
  The position is saved in pixels together with the screen width at the time, so it scales when the resolution changes.

## Build

Requirements: JDK 17+, Android SDK 35.

```bash
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # unit tests
./gradlew lintDebug              # lint
./gradlew assembleRelease        # release APK (see signing below)
```

### Release signing

Locally, create `keystore.properties` in the project root (it is git-ignored):

```properties
storeFile=/absolute/path/to/glowled-release.jks
storePassword=...
keyAlias=glowled
keyPassword=...
```

On GitHub Actions, add these repository secrets (*Settings → Secrets and variables → Actions*):

| Secret | Value |
|---|---|
| `GLOWLED_KEYSTORE_BASE64` | `base64 -w0 glowled-release.jks` |
| `GLOWLED_KEYSTORE_PASSWORD` | keystore password |
| `GLOWLED_KEY_ALIAS` | `glowled` |
| `GLOWLED_KEY_PASSWORD` | key password |

Without these secrets, CI signs the APK with a throwaway debug key. Such APKs can't be installed over a properly signed release.

### Making a release

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts` and update `CHANGELOG.md`.
2. Push to `main`. The **Build & Release** workflow tests, builds and signs the app, then creates the
   `v<versionName>` tag and GitHub Release with the APK and a SHA-256 checksum.
3. To rebuild an existing release (e.g. after adding the signing secrets), run the workflow from the
   *Actions* tab → *Build & Release* → *Run workflow*. The APK on the release is replaced.

## Troubleshooting

| Problem | Fix |
|---|---|
| Light stops after a while | Set battery to *Unrestricted* and allow *Autostart* (Setup list). On Xiaomi also lock the app in Recents. |
| Screen doesn't turn on when a notification arrives | Enable the accessibility overlay. On Xiaomi, also allow *Show on lock screen* and *Display pop-up windows while running in background* (the Autostart button opens that page). |
| Ring is slightly off-centre | *Camera position*, then nudge with the arrows. Some phones report a cutout box larger than the hole. |
| Screen stays on after the light times out | That happens without the accessibility service: the screen then turns off at your normal screen timeout. Enable accessibility for an instant switch-off. |
| Nothing for one app | *Apps & colours*: check the app is on. Silent notifications are ignored unless you turn that off in Settings. |

## Privacy

Glow LED has **no internet permission**. It reads only the app name and colour of each notification and
keeps them in memory. Notification text is never read or stored.

## License

[MIT](LICENSE)
