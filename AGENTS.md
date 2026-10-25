<!-- LOVABLE:BEGIN -->
> [!IMPORTANT]
> This project is connected to [Lovable](https://lovable.dev). Avoid rewriting
> published git history — force pushing, or rebasing/amending/squashing commits
> that are already pushed — as it rewrites history on Lovable's side and the
> user will likely lose their project history.
>
> Commits you push to the connected branch sync back to Lovable and show up in
> the editor, so keep the branch in a working state.
<!-- LOVABLE:END -->

- The real product is the native Android app in `android/`; island motion is spring-driven in IslandView.kt (no Animator APIs) so every gesture can interrupt any animation.
- The island window is hosted by IslandAccessibilityService (TYPE_ACCESSIBILITY_OVERLAY) when enabled, falling back to TYPE_APPLICATION_OVERLAY; app overlays sit below the status bar, so top-edge taps would otherwise open the notification shade.
- The wake intro is armed on SCREEN_OFF and played only after the keyguard is gone (polled), because overlays animating behind the lock screen are never seen.
- Keep the overlay window full-width with state-sized height so its horizontal drawing origin never jumps during WindowManager relayout.
- ClockTimeline owns platform-independent duration parsing, rounding and tick rules; LiveActivity converts notification wall-clock bases to monotonic deadlines so phone clock changes do not shift live countdowns.
- Clock data refreshes must not restart presentation timeouts, and user interactions must interrupt delayed timer frames immediately.
- Store device-only island calibration in SharedPreferences through IslandPreferences; IslandFit owns safe bounds, and IslandView scales both drawing and touch coordinates together so controls follow the resized island.
- NothingStyle owns native UI theme values and DotHeading renders original local dot lettering; never bundle proprietary phone-brand fonts or logos.
- Wallpaper theming flows WallpaperTheme (extraction) → IslandPreferences (persistence) → IslandView.applyTheme; the island stays monochrome and only accents (timer ring, waves, progress, supporting buttons) take the wallpaper colours.
- The settled idle overlay window must be FLAG_NOT_TOUCHABLE with minimal height so it never deadens the screen strip it covers; render() restores touchability the moment an activity shows.
- Native settings use wrap-content text controls with minimum touch sizes and labelled platform widgets so system font scaling and accessibility services remain usable.
