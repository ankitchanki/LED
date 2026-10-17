# Glow LED v1.3.0

**Install:** download `GlowLED-v1.3.0.apk` below, open it, then follow the in-app *Setup* tab.
From this version on, every GitHub release is signed with the same key, so updates install over the
previous version. (If you installed an older GitHub build, uninstall it once first.)

## New compact layout
- Everything fits on one screen: small header with the main switch, live preview, and swipeable tabs
  (Setup, Style, Screen off, Screen on, Alerts, Battery, Log) - swipe left/right instead of scrolling
- Switches are now a 2-column grid of tiles; sliders sit side by side
- Test and Preview buttons are identical and sit in a fixed bottom bar, so nothing overlaps
- Setup opens first (with a badge) until everything needed is granted

## Accessibility is optional
- "Display over other apps" alone is enough to show the screen-off light; Accessibility is now
  clearly marked optional (it adds instant screen-off and makes the light unblockable on HyperOS etc.)
- Clear explanation of Android 13+ "restricted settings" for APK installs

## Releases
- CI no longer publishes APKs signed with a throwaway key (the cause of "invalid package" on update)

---

# Glow LED v1.2.0

## Fixes
- **Screen-off light now works on HyperOS / MIUI** (and other ROMs that block background screens such as ColorOS, OriginOS, Funtouch): with Accessibility on, the light is drawn in an accessibility overlay instead of opening an activity, so the ROM can't block it
- New Setup step for Xiaomi: detects and opens "Show on lock screen" + "Display pop-up windows while running in background"
- Declares the Android 14+ "turn screen on" permission so the wake-up isn't ignored

## UI
- No separate Settings screen any more: every setting is on the home page in clear sections (Look, Screen off, Screen on, Notifications, Battery) with switches, sliders and chips
- Settings that don't apply are dimmed automatically
- New Diagnostics card: shows what the app did (notification received, screen off, light started/skipped and why) with a one-tap "Copy event log" for bug reports
- Smaller APK (removed the preference library)

---

# Glow LED v1.1.0

## New design
- Completely redesigned, one-hand friendly UI: big collapsing header with a live preview, main actions in a bottom dock within thumb reach
- Edge-to-edge: header and screens flow seamlessly under the status bar and navigation bar
- Tap-to-pick animation carousel with live mini previews, one-tap colour row, toggle tiles for screen-off / screen-on light
- Setup checklist with progress bar; finished steps tuck away
- Settings and Apps screens get the same large header; app search moved to the bottom
- Haptic feedback on taps

## Fixes
- Preview ring no longer slides into / gets cut off by the top bar while scrolling
- Buttons no longer show a stray purple tint

---

# Glow LED v1.0.0

First release. Works on Android 10 – 15+, any brand.

## Features
- Notification light around the camera punch-hole, pill cutout, notch, or a classic LED dot
- Auto-detects the camera cutout; fine-tune with drag, sliders and 1 px nudges
- 9 animations: Pulse, Breathe, Blink, Heartbeat, Spinner, Rainbow, Static ring, Edge lighting, Edge pulse
- Per-app colours (custom, the app's notification colour, or picked from the app icon) and per-app on/off
- Several notifications: cycle colours, split the ring into colour segments, or newest only
- Always-on black screen while the phone is "off" (AMOLED: only the light's pixels use power)
- Light also shown while the screen is on / on the lock screen
- Pocket detection, burn-in protection, tap / double-tap gestures, reminder pulses
- Battery: frame-rate cap, lowest refresh rate while off, minimum brightness, low-battery cut-off, quiet hours
- Respects Do Not Disturb, ignores silent / ongoing notifications
- Quick Settings tile
- Brand helpers for Xiaomi / Redmi / POCO, Samsung, OnePlus, OPPO, realme, vivo, Huawei, Honor, ASUS, Tecno / Infinix
