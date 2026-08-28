# Gallery Backup

An Android gallery app that browses your phone's photos/videos in their real,
nested folder structure, and backs up whichever folders you choose into a
matching folder tree in your Google Drive -- the opposite of Google Photos'
flat, one-big-library backup.

- **Gallery**: browse folders exactly as they exist on your device, open any
  photo/video full-screen, swipe between items in a folder.
- **Backup**: pick which top-level folders to back up; everything under them
  (subfolders included) is mirrored into a `GalleryBackup` folder in your
  Drive, preserving the same structure.
- **Sync**: a WorkManager job guarantees a catch-up pass every 15 minutes
  (Android's minimum interval for periodic work); while the app's process is
  alive, a `ContentObserver` also triggers a near-immediate backup as soon as
  new media appears.
- Only ever touches files/folders it created itself in your Drive (OAuth
  `drive.file` scope) -- it cannot see or read the rest of your Drive.

This was built and reviewed by hand, but **not compiled** -- the sandbox this
was built in has no Android SDK and its network policy blocks
`dl.google.com`, so `google()`/Android Gradle Plugin resolution is
unreachable here. Open it in Android Studio for the first real build; that's
also where any leftover typo will surface immediately.

## 1. One-time Google Cloud setup (~10 minutes)

Sideloading (installing an APK outside the Play Store) has no bearing on
whether the Drive API works -- OAuth access is controlled entirely by the
Google Cloud project you set up, not by app distribution channel.

1. Go to [Google Cloud Console](https://console.cloud.google.com/) and
   create a new project (or reuse one).
2. **APIs & Services > Library** -- search for "Google Drive API" and enable
   it.
3. **APIs & Services > OAuth consent screen**:
   - User type: External (fine even for personal use).
   - Fill in app name ("Gallery Backup"), your email as support/developer
     contact.
   - Scopes: add `https://www.googleapis.com/auth/drive.file`. This is a
     *non-sensitive* scope (the app only ever sees files/folders it created),
     so it does **not** require Google's verification review.
   - Because the scope isn't sensitive, you can publish the consent screen
     straight to **Production** (Publishing status > Publish App). Do this --
     otherwise it stays in "Testing" mode, which forces you to log in again
     every 7 days.
4. **APIs & Services > Credentials > Create Credentials > OAuth client ID**:
   - Application type: **Android**.
   - Package name: `com.elghayesh.gallerybackup`
   - SHA-1 certificate fingerprint: see below.

### Getting your SHA-1 fingerprint

For a debug build (fastest way to try it out), Android Studio auto-creates a
debug keystore. Get its fingerprint with:

```
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
```

Copy the `SHA1:` value into the OAuth client form. If you later build a
signed release APK with your own keystore (Android Studio: Build > Generate
Signed Bundle / APK > Create new...), get that keystore's SHA-1 the same way
and add it as an **additional** Android OAuth client (you can register
multiple, one per keystore you build with).

No client secret and no redirect URI are needed for this client type --
Google validates the app by matching its signing certificate, not by a
shared secret.

## 2. Build the app

1. Open this project's root folder in Android Studio (Hedgehog/2023.1 or
   newer recommended).
2. Let Gradle sync -- it needs normal internet access to `google()` and
   `mavenCentral()` (not blocked on your own machine, just in the sandbox
   this was authored in).
3. Either:
   - Run it straight to a phone connected over USB with Developer Options /
     USB debugging enabled, or
   - **Build > Build Bundle(s)/APK(s) > Build APK(s)**, then copy the
     resulting `app-debug.apk` to your phone and install it (you'll need to
     allow "install unknown apps" for whichever app you use to open it --
     Files, a browser, etc).

## 3. First run on your phone

1. Grant the photo/video permission prompt.
2. Open the hamburger/settings icon (top right) > **Connect Google Drive**
   and sign in with the Google account whose Drive you want to back up to.
3. Check the top-level folders you want backed up (e.g. `DCIM`, `Pictures`).
   Everything nested inside a checked folder goes too.
4. Toggle **Wi-Fi only** off if you're fine backing up over mobile data.
5. Tap **Back up now** for an immediate first pass, or just leave it -- the
   periodic job picks it up within 15 minutes either way.

### One manual step Android requires

Some phone makers (Samsung, Xiaomi/MIUI, and a few others) apply their own
aggressive battery-killer on top of stock Android that can stop background
apps even when they're following every rule Android provides. There's no way
to code around this -- go to your phone's **Settings > Apps > Gallery
Backup > Battery**, and set it to **Unrestricted** (or disable "battery
optimization" for it). This is the same step apps like WhatsApp or Google
Photos ask for.

## Notes / limitations, honestly stated

- **15-minute floor**: Android's `WorkManager` doesn't allow periodic jobs
  more frequent than every 15 minutes -- that's the guaranteed worst case,
  not the typical case (the `ContentObserver` fast path usually beats it
  while you're actively using your phone).
- **Not "always running" in the literal sense**: nothing on Android is, by
  OS design (Doze mode) -- that's true for Google's own apps too. The
  periodic WorkManager job is what actually guarantees nothing gets missed
  long-term.
- **Disconnecting** in the app only clears its local "connected" flag; to
  fully revoke Drive access, remove it under
  `myaccount.google.com/permissions` as well.
- First backup of a large library will take a while and use meaningful data
  -- turn Wi-Fi only on if that matters to you.
