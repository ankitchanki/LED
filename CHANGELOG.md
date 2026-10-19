# Glow LED v2.0.3

✅ **Signed build** (official Glow LED key, SHA-256 `5D:04:DE:1A…0C:50:23`). Installs over v1.3.0 and
later and keeps your settings.

## Fix: bright flash before the light on HyperOS / MIUI
The event log from a Xiaomi phone showed the display turning on only once, but the light's lock-screen
window being covered at that moment: HyperOS draws its own lock screen on top for an instant when the
display wakes, and that was the flash before the black screen and the ring.

- On Xiaomi / Redmi / POCO, *Screen-off method: Auto* now uses the **overlay**, which is drawn above the
  lock screen (needs Accessibility, which is how it already works on these phones).
- The overlay now **wakes the screen only after its black frame is drawn**, so the first thing the display
  shows is black, not the lock screen (before, it woke after a fixed 60 ms).
- The event log notes when the lock-screen window is covered ("paused").

If you picked *Lock-screen window* by hand, switch *Screen off → Screen-off method* to **Auto** (or
**Overlay**).

---

# Glow LED v2.0.2 (diagnostic build)

✅ **Signed build** (official Glow LED key, SHA-256 `5D:04:DE:1A…0C:50:23`). Installs over v1.3.0 and
later and keeps your settings.

Some phones (seen on HyperOS) flash the screen on for a moment right after you lock it, before the
light appears. This build helps find out why:

- **Detailed event log:** timestamps in milliseconds, and every step of the screen-off / wake sequence
  is recorded: screen ON / OFF (and who caused it), the display's power state (ON / OFF / DOZE),
  which method was used, when the light window was created, resumed and drew its first frame, every
  time Glow LED wakes the screen (and why) and every time it switches it off. Keeps the last 200
  events.
- **Share the log:** *More → Event log → Share* sends it to any app (WhatsApp, e-mail, …).
- **New option:** *Screen off → Start the light after screen off* (0.8 s / 1.5 s / 3 s). Waiting
  longer gives the system time to finish switching off before the light wakes it again.

---

# Glow LED v2.0.1

✅ **Signed build** (official Glow LED key, SHA-256 `5D:04:DE:1A…0C:50:23`). Installs over v1.3.0 and
later and keeps your settings.

## Fixes
- **Screen no longer wakes and goes straight back off when a notification arrives.** Pocket detection
  held a "proximity screen-off" lock: the hardware cut the panel the instant the proximity sensor said
  "near". Many Xiaomi/Redmi phones use a virtual proximity sensor that reports a false "near" right
  after the screen wakes, so the light died within milliseconds. Pocket detection now only reacts when
  the phone stays covered for over a second (then hides the light and ends after 10 s, as before).
- If the system itself switches the screen off a moment after the light woke it (some ROMs do after a
  notification wake), Glow LED wakes it again once instead of treating it as a power-button press.
  Both cases are written to the event log.

---

# Glow LED v2.0.0

✅ **Signed build** (official Glow LED key, SHA-256 `5D:04:DE:1A…0C:50:23`). Installs over v1.3.0 and
later and keeps your settings.

## New
- **Português (Brasil):** the whole app is translated. It follows the phone's language, or choose one
  in *More → Language* (also in Android 13+ *Settings → Apps → Glow LED → Language*).
- **Charging ring:** while charging, the ring around the camera fills with your battery level, clockwise
  from the top. Pick a colour for each level (0–19 %, 20–79 %, 80–100 %). It shows for a few seconds
  when you plug in, then whenever no notification is waiting; works with the screen on and on the lock
  screen. Switch: *Screen on → Charging ring*.
- **Download ring:** a download, upload or install with a progress bar shows its progress around the
  camera (app colour, or your own). Switch: *Screen on → Download ring*.
- **Reply and Mark as read** on the always-on screen: tap an app icon, then **Reply** (a small sheet
  opens, Android asks to unlock, the message is sent through the app's own reply button) or
  **Mark as read** (the app's own action; **Dismiss** if it has none). Switch: *Screen off → Reply buttons*.
- **One-handed mode:** pull the settings down and the tabs and tiles slide into thumb reach, like
  One UI. Swipe up or press back to return; it remembers your choice.
- **More tab without scrolling:** a grid of tiles side by side - build check, **Get latest** (downloads
  the newest signed APK directly), Language, Back up, Restore, Event log.

## Design
- Every switch tile has the **same size** and a **single line** of text.
- **Setup** lists the important steps first (Notification access, Display over other apps, …);
  Autostart and the Shizuku/root one-tap setup are now last.
- The message peek is **left-aligned** like a notification: app and time, sender, messages, media label,
  buttons - no more centred, scattered text.
- Removed the "Live preview on your camera" label (the preview is still on the camera), so the tabs
  get that space.
- *Camera position:* **Auto-detect**, Reset and Save stay on one line and line up.

## Under the hood
- The battery level is only watched while plugged in and the charging ring is on, so it costs nothing
  on battery.
- The screen-on window is only moved when its position really changes (gauge updates are drawn in place).

---

# Glow LED v1.10.0

✅ **Signed build** (official Glow LED key, SHA-256 `5D:04:DE:1A…0C:50:23`). Installs over v1.3.0 and
later and keeps your settings.

## Fixes
- **Test / notifications no longer show "0 unread".** With *Clear after I unlock* on, a notification
  that was posted again with the same ID stayed marked as read. That affected pressing Test twice, and
  a WhatsApp chat getting a new message. Every new alert now counts as unread again.

## AOD-style icons
- The icons under the camera are now monochrome glyphs, like Samsung's One UI always-on display:
  soft grey, using each notification's own status-bar icon (or the app's themed icon).
- The newest app's glyph is in **its notification colour**: the same colour as the droplet that turns
  into it and as its part of the ring.
- Prefer full-colour icons? *Screen off → AOD-style icons* off.

## Battery
- **Still ring after 12 s** (default on): the light animates when it appears, then holds still, so the
  screen isn't redrawn until something changes. A new notification animates again.
- **Dim older notifications** (default on): a new notification glows at full strength and fades to
  45 % over an hour. You can tell new from old at a glance, and fewer pixels are lit.
- **Light up when phone moves** (default on): after the light times out, Glow LED waits on the phone's
  significant-motion sensor. It is a hardware trigger, so the CPU and screen stay asleep while waiting.
  When you pick up or carry the phone, the light shows again for a few seconds (at most every 2 min).
  This replaces the need for timed reminders that wake the screen blindly.
- All three are in the **Battery** tab.

---

# Glow LED v1.9.1

✅ **Signed build** (official Glow LED key, SHA-256 `5D:04:DE:1A…0C:50:23`). Installs over v1.3.0 and
later and keeps your settings.

## Fixes
- **Preview no longer force-closes.** With no notifications waiting, the preview crashed while it was
  setting up the sample app icons. It now opens every time.
- **Test works every time:**
  - If the light was still shutting down from an earlier run, a new test was ignored. The new test now
    starts straight away.
  - Some phones still report the screen as on for a moment after you lock it, which made Glow LED skip
    the light. It now checks again a little later.
  - If the system had dropped Glow LED's notification access (for example after a crash), Test now
    reconnects it.
- The *More → event log* now says why a light was skipped ("already showing", "screen came back on").

---

# Glow LED v1.9.0

✅ **Signed build** (official Glow LED key, SHA-256 `5D:04:DE:1A…0C:50:23`). Installs over v1.3.0 and
later and keeps your settings.

## Changes
- **Rotating colour ring:** the ring split into each app's colour now turns slowly around the camera
  (toggle: *Style → Rotate the colour ring*).
- **Newest app in the middle:** the newest notification's icon sits right under the camera, older ones
  alternate left and right - and the droplet falls straight down into it.
- **No greying:** icons stay at full brightness (no dimming when one is tapped, no grey circles, no
  focus square).
- **Icons, never text:** no letters and no "+N" label. If an app's icon can't be loaded, the
  notification's own status-bar icon is shown in the app's colour.

---

# Glow LED v1.8.2

✅ **Signed build** (official Glow LED key, SHA-256 `5D:04:DE:1A…0C:50:23`). Installs over v1.3.0 and
later and keeps your settings.

## Fixes
- **App icons now always sit below the camera, not on it.** The icons and message card were placed by
  shifting them down, and their own pop-in / fade animations reset that shift - so on a new notification
  they slid back onto the camera. They are now laid out at a fixed spot below the ring as part of the
  screen's layout pass; animations only move them slightly around that spot.
- **Droplet works end to end:** the drop now aims at the icon's real position, is bigger and slower
  (easier to see), and starts when the ring's entrance animation finishes instead of on a timer.
  Verified by rendering the real always-on code frame by frame (see the GIF in the README).
- Saving the camera position no longer chains editor calls (more robust).

---

# Glow LED v1.8.1

✅ **Signed build** (official Glow LED key, SHA-256 `5D:04:DE:1A…0C:50:23`). Installs over v1.3.0 and
later and keeps your settings.

## Fixes
- **Text and icons no longer land on the camera.** On some HyperOS lock screens the window starts below
  the camera, which pushed everything up. Glow LED now notices this straight away and, with Accessibility
  on, hands over to the overlay in the same session (not just next time). Without Accessibility, the ring
  moves just under the top edge so it stays visible.
- The app icons and the message peek always start **below the status bar / camera band** (read from the
  system's window insets), so nothing is cropped.
- Removed the coloured dot under each app icon - the split ring already shows each app's colour.
- **Preview** now shows messaging apps actually installed on your phone (WhatsApp, Telegram, Instagram…)
  instead of Glow LED's own icon.

## Clean-up
- Removed a test-only hook and settings keys that no version reads any more (cleared automatically).
- Status-bar height now comes from window insets instead of an internal Android resource.

---

# Glow LED v1.8.0

✅ **Signed build** (official Glow LED key, SHA-256 `5D:04:DE:1A…0C:50:23`). Installs over v1.3.0 and
later and keeps your settings.

## Changes
- **Ring in every app's colour:** with WhatsApp, Telegram and Instagram notifications waiting, the ring
  now splits into green, blue and pink arcs (new default "Split ring by app"; the old "cycle colours"
  default is switched over once).
- **Droplet becomes the icon:** a new notification's drop falls from the camera onto that app's spot in the
  icon row and turns into the WhatsApp (or other) icon.
- **Icons only:** the app name / "N notifications" text under the icons is gone.
- **Samsung AOD-style message peek:** pitch black (no grey box), soft dimmed text, centred.

---

# Glow LED v1.7.0

✅ **Signed build** (official Glow LED key, SHA-256 `5D:04:DE:1A…0C:50:23`). Installs over v1.3.0 and
later and keeps your settings.

## Fixes
- **Status bar no longer shows on the always-on screen, and the ring sits on the camera again.**
  On HyperOS the lock-screen window started below the status bar, which pushed the ring out of view
  and moved the app icon onto the camera. The window now uses the full-screen flags HyperOS respects;
  if a phone still keeps the status bar, Glow LED detects it and switches to the overlay automatically.
- App icons under the camera no longer have a circle around them: plain icons with a small dot in the
  app's LED colour, always below the ring.

## Tap an icon to read
- Tap an app icon on the always-on screen to see **who it's from and what they sent**: the latest
  messages, or a label for media (📷 Photo, 🎥 Video, 🎤 Voice message, 📄 Document).
- Tap the card to open the chat (Android asks you to unlock first). Tap the black area to close it.
- Message text is kept in memory only - never stored or sent. Turn it off with
  *Screen off → Tap icon to read message*. Notifications marked secret never show content.

## Droplet animation
- When a notification arrives, a liquid drop in the app's colour stretches out of the camera, breaks off,
  falls and splashes - and the app icons pop in where it lands.
- While you're using the phone, a smaller drop drips out of the camera and springs back.
- Toggle: *Screen off → Droplet animation*.

---

# Glow LED v1.6.0

✅ **Signed build** (official Glow LED key, SHA-256 `5D:04:DE:1A…0C:50:23`). Installs over v1.3.0 and
later and keeps your settings.

## Live preview on your real camera
- Open the app and the ring grows out of **your phone's actual camera hole** (auto-detected, or your
  saved Camera position) instead of a picture of a lens in the middle of the screen.
- Change the animation, colour or "several notifications" mode and the ring on the camera replays its
  entrance; sliders (speed, thickness, gap, intensity) update it live while you drag.
- Phones without a camera hole get the classic LED dot at the top.
- While the app is open, the screen-on ring is hidden so you never see two rings.
- The old preview box is gone, so the settings tabs get more room.

## Performance
- The ring no longer uses an offscreen GPU layer: about 10 MB less memory and one less composition
  pass per frame, on the always-on screen too.

---

# Glow LED v1.5.1

✅ **Signed build** (official Glow LED key, SHA-256 `5D:04:DE:1A…0C:50:23`). Installs over v1.3.0 and
later. If you see "App not installed" / "Invalid package", your phone has an early build with a
different key: back up settings (More tab), uninstall once, reinstall, restore. Updates install
normally after that.

## Easy updates
- Signed APKs now live in the repo's `releases/` folder (`GlowLED-latest.apk` is always the newest).
- **More** tab (was Log): shows whether you have the official signed build, **Check for updates**,
  **Back up settings** and **Restore settings** (all settings, per-app colours and the camera
  position, saved to a file you choose - no storage permission needed).

---

# Glow LED v1.5.0

**Install:** download `GlowLED-v1.5.0.apk` below, open it, then follow the in-app *Setup* tab.

## Smooth screen-off
- **No more flicker when you lock the phone.** The light now prefers a lock-screen window: Android
  waits for its first (black, dimmed) frame before turning the panel on, so the lock screen never
  flashes. If your ROM blocks that window, Glow LED notices within ~1 s, switches to the overlay and
  remembers the choice.
- New *Screen-off method* option (Auto / Lock-screen window / Overlay) in the Screen off tab.
- The ring now grows smoothly out of the camera instead of popping in at full size.

## App under the camera (One UI style)
- A strip of app icons drops in under the ring (up to 4 + "+N"), each in a ring of its LED colour,
  with the newest app's name and the notification count.
- Staggered pop-in motion; fades away after 8 s to protect the screen (option to keep it visible).
- Single tap brings it back; a new notification refreshes it.

## Gestures
- **Double-tap to wake** (shows the lock screen), with more reliable double-tap detection.

## Fixes
- Guard against a rare crash when the display isn't available yet.

---

# Glow LED v1.4.0

**Install:** download `GlowLED-v1.4.0.apk` below, open it, then follow the in-app *Setup* tab.

## Shizuku & root support (optional)
- **One-tap setup** in the Setup tab: with Shizuku (no root needed) or root, Glow LED grants its own
  permissions in one go - notification access, display over apps, notifications, battery
  unrestricted, accessibility, Android 14+ turn-screen-on / full-screen alerts, and on Xiaomi the
  lock-screen, background pop-up and autostart permissions. No Settings hunting and no
  "restricted settings" step. Every grant is approved by you in Shizuku / your root manager and only
  touches Glow LED itself.
- **Instant screen-off without Accessibility**: when the light times out (or you double-tap), Shizuku/root
  presses the power key for you.
- Get Shizuku: https://shizuku.rikka.app/download/

---

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
