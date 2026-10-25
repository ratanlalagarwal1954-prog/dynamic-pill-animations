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
