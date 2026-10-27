# Download Glow LED

| File | What it is |
|---|---|
| [`GlowLED-latest.apk`](GlowLED-latest.apk) | Always the newest version. **Download this one.** |
| `GlowLED-v2.8.0.apk` | The same build, named by version. |
| [`SHA256SUMS.txt`](SHA256SUMS.txt) | Checksums, if you want to verify the download. |

## ✅ This is a signed build

Every APK in this folder is signed with the official Glow LED release key:

```
SHA-256: 5D:04:DE:1A:96:A8:01:07:EC:87:65:83:82:B0:63:F1:5C:53:CF:EA:80:43:F5:54:A3:39:F2:85:FE:0C:50:23
```

Android only installs an update over an app signed with the **same** key. All builds here use
this key, so **from now on every new version installs straight over the old one** and keeps
your settings.

## Install or update

1. Download **`GlowLED-latest.apk`** (on GitHub tap the file, then **Download raw file** / the ⬇ icon).
2. Open it and tap **Install** (or **Update**). Allow "Install unknown apps" for your browser or
   file manager if asked.
3. Open Glow LED and follow the **Setup** tab.

## "App not installed" / "Invalid package" / "Package conflicts"?

This happens **only once**, if the Glow LED on your phone came from an older build signed with a
different key (the early v1.0.0–v1.2.0 GitHub releases). Fix it like this:

1. *(Optional)* Save your settings first: Glow LED → **Settings** tab → **Back up settings**.
2. Uninstall the old app: long-press the **Glow LED** icon → **App info** → **Uninstall**
   (or *Settings → Apps → Glow LED → Uninstall*).
3. Install `GlowLED-latest.apk` again.
4. *(Optional)* Glow LED → **Settings** → **Restore settings** and pick the file you saved.

After this one-time reinstall, every future update installs over it normally.

**Check which build you have:** Glow LED → **Settings** tab. It says *Official signed build* when you
have the right one.

## Android 13+ "Restricted setting"

Apps installed from an APK file can't turn on Notification access / Accessibility until you allow
it: *App info → ⋮ (top right) → Allow restricted settings*.

## "App blocked to protect your device"

Play Protect's fraud protection (India and some other countries) blocks apps installed from a
browser or file manager when they ask for Notification access, which Glow LED needs. Install with
`adb install GlowLED-latest.apk` from a computer, or pause *Play Store → profile → Play Protect →
⚙ → Scan apps with Play Protect*, install, and turn it back on right away.
