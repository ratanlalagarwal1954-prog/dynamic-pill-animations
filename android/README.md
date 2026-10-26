# Dynamic Droid — native Android

Native Kotlin Android implementation of the live-activity island.

## Run

1. Open the `android` directory in Android Studio.
2. Let Android Studio install Android SDK 35 and sync Gradle.
3. Run the `app` configuration on Android 8.0+.
4. In the app, grant **Display over other apps**, enable **Notification access**, and turn on
   **Allow tapping over the status bar** (accessibility). On Android 13+ a sideloaded app's
   accessibility switch is greyed out until you open App info → ⋮ → **Allow restricted settings**.
5. Tap **Start Dynamic Island**.

## Build without Android Studio

1. Push the repository to GitHub.
2. Open the repository's **Actions** tab.
3. Select **Build Android APK**, then choose **Run workflow**.
4. Open the completed run and download the **dynamic-droid-debug-apk** artifact.
5. Unzip it and install `app-debug.apk` on the phone. Android may ask you to allow
   installs from the browser or file manager used to open it.

## Native integrations

- `IslandOverlayService`: system-level overlay backed by a foreground service.
- `IslandNotificationListener`: maps call, media, navigation, timer, and message notifications into island states. For media, it reads the active `MediaSession` metadata and playback state, including artwork, duration, and the supported transport controls.
- `IslandAccessibilityService`: owns no logic and reads no screen content; it only lets the island use an accessibility overlay, which sits above the status bar so taps at the top reach the island instead of opening the notification shade. Without it the island falls back to a normal overlay.
- `IslandView`: native Canvas rendering with compact/expanded morphing, album art, animated playback progress, and tap targets for previous, play/pause, next, and seeking.

The idle and compact island is a floating pill around the camera, drawing its own
time, network and battery readout (the compact music layout keeps that readout too).
Expanded activities morph the pill into a card that covers the whole screen width.
When the wallpaper theme is enabled, the pill's body takes a very dark version of the
wallpaper's main colour. It temporarily grows for an expanded activity, automatically
collapses after a few seconds, and the idle pill lets touches fall through to the app
underneath. Tapping an expanded
activity opens its source notification; action buttons invoke actions exposed by
that notification. Music controls use the active media session when the source
app exposes one, with notification-action labels as a fallback. Album art is
scaled before it is passed to the overlay. Charging state is read directly from Android.

Android does not allow third-party apps to replace the OS status bar. The island is therefore an explicitly user-enabled overlay and is subject to device-vendor background-process policies.
