package com.dynamicdroid.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import androidx.core.graphics.ColorUtils
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

enum class Presentation { IDLE, COMPACT, EXPANDED }

/**
 * The island. Shape and content are driven by springs stepped on every display frame, so
 * every change (new activity, expand, collapse, press, drag) morphs smoothly instead of
 * jumping. Gestures follow iOS:
 *  - compact: tap opens the app, double-tap / long-press / pull down expands
 *  - expanded: tap content opens the app, controls work in place, double-tap / swipe up / tap outside collapses
 */
class IslandView(context: Context) : View(context), Choreographer.FrameCallback {

    interface Callbacks {
        fun onOpen(activity: LiveActivity) {}
        fun onAction(activity: LiveActivity, action: IslandAction) {}
        fun onMedia(activity: LiveActivity, command: String, positionMs: Long? = null) {}
        fun onExpandRequest() {}
        fun onCollapseRequest() {}
        fun onDismiss(activity: LiveActivity) {}
        fun onInteraction() {}
        fun onSettled() {}
        /** Another app went fullscreen (video, photos, games) or came back out of it. */
        fun onFullscreenChanged(fullscreen: Boolean) {}
    }

    var callbacks: Callbacks? = null
    var anchorToCutout = true

    var activity: LiveActivity? = null
        private set
    var presentation = Presentation.IDLE
        private set

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var baseTop = dp(8f)
    private var fit = IslandFit()
    private var cameraX: Float? = null
    private var cameraTop: Float? = null

    fun applyFit(next: IslandFit) {
        fit = next.safe()
        updateAnchor()
        show(activity, presentation)
        invalidate()
    }

    // Wallpaper theme: the island stays monochrome, but accents (timer ring, waves,
    // progress, supporting buttons) take the wallpaper's colours when enabled.
    private var accent = NothingStyle.red
    private var accentSoft = NothingStyle.muted
    private var waveColor = NothingStyle.foreground

    fun applyTheme(theme: IslandTheme) {
        val t = theme.safe()
        accent = if (t.enabled) t.accent else NothingStyle.red
        accentSoft = if (t.enabled) t.secondary else NothingStyle.muted
        waveColor = if (t.enabled) t.accent else NothingStyle.foreground
        invalidate()
    }

    // Idle status bar data (time, network, battery). Redrawn only when it actually changes.
    private var status = StatusInfo()
    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = NothingStyle.mono; textSize = v(13f); fontFeatureSettings = "tnum"
    }
    private fun v(x: Float) = x * resources.displayMetrics.density

    fun applyStatus(next: StatusInfo) {
        if (next == status) return
        status = next
        if (presentation == Presentation.IDLE || idleA.value > 0.01f) invalidate()
    }

    /** Hide (fullscreen media) or bring back the island with a shrink-and-fade. */
    var isConcealed = false
        private set

    fun setConcealed(hidden: Boolean) {
        if (hidden == isConcealed) return
        isConcealed = hidden
        if (hidden) {
            tracking = false; seeking = false
            removeCallbacks(longPress)
            pendingTap?.let { removeCallbacks(it) }; pendingTap = null
        }
        concealS.target = if (hidden) 0f else 1f
        settled = false
        kick()
    }

    private var sawStatusBar = false
    private var lastFullscreen: Boolean? = null
    private fun reportFullscreen(full: Boolean) {
        if (lastFullscreen == full) return
        lastFullscreen = full
        callbacks?.onFullscreenChanged(full)
    }

    private fun updateAnchor() {
        val physicalTop = if (anchorToCutout && fit.alignCamera) cameraTop ?: dp(8f) else dp(8f)
        baseTop = max(0f, physicalTop + dp(fit.offsetY)) / fit.scale
        if (!isAbsorbing) topS.target = baseTop
    }

    // Shape. Softer, slower springs (~0.5s response) with a touch of bounce, like iOS.
    private val widthS = Spring(dp(104f), SHAPE_K, WIDTH_DAMP, 0.5f)
    private val heightS = Spring(dp(32f), SHAPE_K, HEIGHT_DAMP, 0.5f)
    private val radiusS = Spring(dp(16f), SHAPE_K, 0.9f, 0.5f)
    private val topS = Spring(baseTop, SHAPE_K, 0.86f, 0.5f)
    // Content and gesture feedback
    private val expandedA = Spring(0f, CONTENT_IN_K, 1f, 0.004f)
    private val compactA = Spring(0f, CONTENT_IN_K, 1f, 0.004f)
    // Stiffer press so the squish lands on the finger's first frame.
    private val pressS = Spring(1f, 760f, 0.62f, 0.0008f)
    private val stretchS = Spring(0f, 360f, 0.62f, 0.5f)
    private val absorbS = Spring(0f, 120f, 1f, 0.004f)
    // Wake/unlock intro: 0 = hidden dot, 1 = fully present
    private val introS = Spring(1f, 160f, 0.7f, 0.002f)
    // Status-bar content of the idle pill
    private val idleA = Spring(0f, CONTENT_IN_K, 1f, 0.004f)
    // 1 = shown, 0 = tucked away (fullscreen media)
    private val concealS = Spring(1f, 230f, 0.9f, 0.003f)
    // Sheen that sweeps across the pill once the intro lands
    private var sheenStart = 0L
    // While the intro's first stage (dot -> circle) runs, show() must not widen the shape yet.
    private var introHoldUntil = 0L
    private val introWiden = Runnable { widenIntro() }
    private val shapeSprings = listOf(widthS, heightS, radiusS, topS)
    private val springs = listOf(widthS, heightS, radiusS, topS, expandedA, compactA, pressS, stretchS, absorbS, introS, idleA, concealS)

    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val medium = NothingStyle.mono
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium; textSize = dp(16f) }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(14f) }
    private val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium; textSize = dp(11.5f) }
    private val bigPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium; textSize = dp(26f) }
    private val letterPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    // Timer digits: tabular figures so the numbers don't wobble as they tick.
    private val digitPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium; fontFeatureSettings = "tnum" }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arcRect = RectF()

    // Timer live state: rolling digits and the ring's total duration per timer
    private var rollKey: String? = null
    private var rollText = ""
    private var rollPrev = ""
    private var rollAt = 0L
    private val timerTotals = HashMap<String, Long>()

    private val rect = RectF()
    private val hit = RectF()
    private val clip = Path()
    private val iconPath = Path()
    private val matrix = Matrix()
    private val shaders = WeakHashMap<Bitmap, BitmapShader>()
    private val phoneIcon = context.getDrawable(android.R.drawable.ic_menu_call)?.mutate()

    // Hit-test geometry captured while drawing
    private var barLeft = 0f
    private var barRight = 0f
    private var barY = -1000f
    private val actionHits = ArrayList<Pair<RectF, IslandAction>>()

    // Touch state
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var tracking = false
    private var dragging = false
    private var longFired = false
    private var seeking = false
    private var seekFraction = 0f
    private var downX = 0f
    private var downY = 0f
    private val longPress = Runnable {
        if (tracking && !dragging && presentation == Presentation.COMPACT) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            pressS.target = 1f
            callbacks?.onExpandRequest()
        }
    }

    // Double tap: a second tap within the window toggles expand/collapse instead of opening.
    // Kept short so a single tap still feels immediate.
    private val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong().coerceIn(160L, 210L)
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var pendingTap: Runnable? = null

    // Cached glow shader (rebuilding a RadialGradient every frame churns the GC)
    private var glowShader: RadialGradient? = null
    private var glowColor = 0
    private var glowRadius = 0f

    // Frame loop
    private var running = false
    private var delayedFrame = false
    private var lastFrame = 0L
    private var settled = true

    init {
        isClickable = true
        isFocusable = false
        refreshContentDescription()
        // Pre-Android 11 fallback: legacy flag still reports when the foreground app hides the status bar.
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 30) setOnSystemUiVisibilityChangeListener { flags ->
            if (anchorToCutout) reportFullscreen(flags and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
        }
    }

    // ---------------------------------------------------------------- public API

    fun show(next: LiveActivity?, nextPresentation: Presentation, absorb: Boolean = false) {
        val p = if (next == null) Presentation.IDLE else nextPresentation
        if (activity?.key != next?.key || presentation != p) {
            pendingTap?.let { removeCallbacks(it) }; pendingTap = null
        }
        activity = next
        presentation = p
        refreshContentDescription()
        // Mid-intro: the widen stage picks up the latest state itself.
        if (!absorb && SystemClock.uptimeMillis() < introHoldUntil) return
        if (introS.value < 0.001f && introS.target == 0f) return
        if (introHoldUntil == 0L && !sheenActive() && !isAbsorbing) restoreShapeSprings()
        val spec = specFor(p, next)

        if (absorb && next != null) {
            removeCallbacks(introWiden); introHoldUntil = 0L; introS.snap(1f)
            val dm = resources.displayMetrics
            widthS.snap(dm.widthPixels * 0.84f)
            heightS.snap(dm.heightPixels * 0.58f)
            radiusS.snap(dp(46f))
            topS.snap(dm.heightPixels * 0.2f)
            absorbS.snap(1f)
            compactA.snap(0f)
            expandedA.snap(0f)
            shapeSprings.forEach { it.stiffness = 120f; it.dampingRatio = 0.82f }
        }
        widthS.target = spec.w
        heightS.target = spec.h
        radiusS.target = spec.r
        topS.target = baseTop
        absorbS.target = 0f
        setContentTargets(p)
        settled = false
        kick()
    }

    /** Content fades in slowly once the shape has room, and fades out fast so it never squashes. */
    private fun setContentTargets(p: Presentation) {
        expandedA.target = if (p == Presentation.EXPANDED) 1f else 0f
        expandedA.stiffness = if (p == Presentation.EXPANDED) CONTENT_IN_K else CONTENT_OUT_K
        compactA.target = if (p == Presentation.COMPACT) 1f else 0f
        compactA.stiffness = if (p == Presentation.COMPACT) CONTENT_IN_K else CONTENT_OUT_K
        idleA.target = if (p == Presentation.IDLE) 1f else 0f
        idleA.stiffness = if (p == Presentation.IDLE) CONTENT_IN_K else CONTENT_OUT_K
    }

    /** Screen went off: collapse into an invisible dot so the next wake can grow out of it. */
    fun hideForIntro() {
        Choreographer.getInstance().removeFrameCallback(this)
        running = false; delayedFrame = false; lastFrame = 0L
        tracking = false; seeking = false
        removeCallbacks(longPress)
        pendingTap?.let { removeCallbacks(it) }; pendingTap = null
        pressS.snap(1f); stretchS.snap(0f); absorbS.snap(0f)
        removeCallbacks(introWiden)
        introHoldUntil = 0L
        introS.snap(0f)
        widthS.snap(dp(10f)); heightS.snap(dp(10f)); radiusS.snap(dp(5f))
        topS.snap(baseTop + dp(12f))
        compactA.snap(0f); expandedA.snap(0f); idleA.snap(0f)
        sheenStart = 0L
        invalidate()
    }

    /**
     * Wake / unlock animation, in two stages:
     *  1. a dot appears in the camera hole and pops into a circle,
     *  2. the circle stretches sideways into the pill with a soft overshoot, content fades in
     *     and a sheen sweeps across.
     */
    fun playIntro() {
        if (isAbsorbing) return
        hideForIntro()
        val spec = specFor(presentation, activity)
        val d = min(spec.h, dp(34f))
        shapeSprings.forEach { it.stiffness = 260f; it.dampingRatio = 0.55f }
        widthS.target = d; heightS.target = d; radiusS.target = d / 2f
        topS.target = baseTop
        introS.target = 1f
        introHoldUntil = SystemClock.uptimeMillis() + INTRO_HOLD_MS
        postDelayed(introWiden, INTRO_HOLD_MS)
        settled = false
        kick()
    }

    private fun widenIntro() {
        introHoldUntil = 0L
        val spec = specFor(presentation, activity)
        widthS.stiffness = 150f; widthS.dampingRatio = 0.6f
        heightS.stiffness = 170f; heightS.dampingRatio = 0.72f
        radiusS.stiffness = 170f; radiusS.dampingRatio = 0.85f
        widthS.target = spec.w; heightS.target = spec.h; radiusS.target = spec.r; topS.target = baseTop
        setContentTargets(presentation)
        sheenStart = SystemClock.uptimeMillis() + 300L
        settled = false
        kick()
    }

    private fun restoreShapeSprings() {
        shapeSprings.forEach { it.stiffness = SHAPE_K }
        widthS.dampingRatio = WIDTH_DAMP
        heightS.dampingRatio = HEIGHT_DAMP
        radiusS.dampingRatio = 0.9f
        topS.dampingRatio = 0.86f
    }

    /** Pixel size the overlay window needs for the current target state. */
    fun windowSizeFor(next: LiveActivity?, p: Presentation): Pair<Int, Int> {
        val spec = specFor(if (next == null) Presentation.IDLE else p, next)
        // A fixed horizontal origin avoids one-frame jumps when WindowManager relayouts.
        // Keep the bottom slack tiny: the window eats touches wherever it covers, so a tall
        // idle window would deaden a strip of the screen under the island.
        return Pair(resources.displayMetrics.widthPixels, ((baseTop + spec.h + dp(14f)) * fit.scale).toInt())
    }

    val isAbsorbing: Boolean get() = absorbS.value > 0.002f

    // ---------------------------------------------------------------- layout specs

    private data class Spec(val w: Float, val h: Float, val r: Float)

    private fun expandedWidth() = min((resources.displayMetrics.widthPixels - dp(16f)) / fit.scale, dp(372f))

    private fun visibleActions(a: LiveActivity) = a.actions.take(3)

    private fun specFor(p: Presentation, a: LiveActivity?): Spec {
        val maxWidth = (resources.displayMetrics.widthPixels - dp(16f)) / fit.scale
        // Idle = mini status bar, wide enough for time | camera | network + battery.
        if (p == Presentation.IDLE || a == null) return Spec(min(dp(176f) * fit.pillWidth, maxWidth), dp(30f), dp(15f))
        if (p == Presentation.COMPACT) return Spec(min(dp(214f) * fit.pillWidth, maxWidth), dp(34f), dp(17f))
        val w = expandedWidth()
        return when (a.mode) {
            IslandMode.MUSIC -> Spec(w, dp(178f), dp(46f))
            IslandMode.CALL -> Spec(w, dp(88f), dp(44f))
            IslandMode.MESSAGE -> Spec(w, dp(if (visibleActions(a).isEmpty()) 84f else 134f), dp(40f))
            IslandMode.CHARGING -> Spec(w, dp(64f), dp(32f))
            IslandMode.TIMER -> Spec(w, dp(if (visibleActions(a).isEmpty()) 88f else 138f), dp(44f))
            else -> Spec(w, dp(84f), dp(42f))
        }
    }

    // ---------------------------------------------------------------- frame loop

    private fun kick() {
        if (delayedFrame) {
            Choreographer.getInstance().removeFrameCallback(this)
            delayedFrame = false
            running = false
        }
        if (running) return
        running = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        delayedFrame = false
        if (!isAttachedToWindow || !isShown) { running = false; lastFrame = 0L; return }
        val dt = if (lastFrame == 0L) 1f / 60f else (frameTimeNanos - lastFrame) / 1_000_000_000f
        lastFrame = frameTimeNanos
        var rest = true
        for (s in springs) {
            s.step(dt)
            if (!s.isAtRest) rest = false
        }
        invalidate()
        if (rest && !settled && !tracking) {
            settled = true
            restoreShapeSprings()
            callbacks?.onSettled()
        }
        val continuous = continuousDelay()
        when {
            !rest || tracking || sheenActive() -> Choreographer.getInstance().postFrameCallback(this)
            continuous != null -> {
                lastFrame = 0L; delayedFrame = true
                Choreographer.getInstance().postFrameCallbackDelayed(this, continuous)
            }
            else -> running = false
        }
    }

    private fun sheenActive(): Boolean {
        if (sheenStart == 0L) return false
        val t = SystemClock.uptimeMillis() - sheenStart
        if (t > SHEEN_MS) { sheenStart = 0L; return false }
        return true
    }

    /** Waveforms and clocks keep redrawing even when the shape is at rest. */
    private fun continuousDelay(): Long? {
        val a = activity ?: return null
        if (presentation == Presentation.IDLE) return null
        return when {
            a.mode == IslandMode.MUSIC && a.isPlaying -> 16L
            a.mode == IslandMode.CALL -> 33L
            a.clockBaseElapsed != null && a.pausedTimerMs == null -> {
                // Smooth frames while a digit rolls, otherwise wake exactly at the next second.
                if (rollAt != 0L && SystemClock.uptimeMillis() - rollAt < ROLL_MS) return 16L
                val ms = a.clockMs() ?: return null
                if (a.chronometerCountDown && ms == 0L) return null
                ClockTimeline.nextTick(ms, a.chronometerCountDown).coerceIn(16L, 1000L)
            }
            else -> null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        kick()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) kick()
    }

    override fun onDetachedFromWindow() {
        pendingTap?.let { removeCallbacks(it) }; pendingTap = null
        removeCallbacks(introWiden); introHoldUntil = 0L
        Choreographer.getInstance().removeFrameCallback(this)
        running = false
        super.onDetachedFromWindow()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (anchorToCutout && Build.VERSION.SDK_INT >= 28) {
            val dm = resources.displayMetrics
            val cut = insets.displayCutout?.boundingRects
                ?.minByOrNull { abs(it.exactCenterX() - dm.widthPixels / 2f) }
            if (cut != null && cut.top < dp(80f)) {
                cameraTop = max(dp(4f), cut.exactCenterY() - dp(17f))
                cameraX = cut.exactCenterX()
                updateAnchor()
                kick()
            }
        }
        // Videos, photos and games hide the system status bar; overlays see that in their insets.
        if (anchorToCutout && Build.VERSION.SDK_INT >= 30) {
            // Some windows (accessibility overlays, no-limits overlays) never see the status bar
            // at all. Only trust "hidden" after this window has seen it visible, otherwise the
            // island would stay tucked away forever.
            val visible = insets.isVisible(WindowInsets.Type.statusBars())
            if (visible) sawStatusBar = true
            reportFullscreen(sawStatusBar && !visible)
        }
        return super.onApplyWindowInsets(insets)
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        // Fully hidden (fullscreen video/photos): draw nothing at all.
        val conceal = concealS.value.coerceIn(0f, 1f)
        if (conceal < 0.01f) return
        val layered = conceal < 0.995f
        if (layered) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (conceal * 255).toInt())
        canvas.save()
        canvas.scale(fit.scale, fit.scale)
        val a = activity
        val desired = (if (anchorToCutout && fit.alignCamera) cameraX ?: width / 2f else width / 2f) + dp(fit.offsetX)
        val cx = IslandFit.center(width.toFloat(), widthS.value * fit.scale, desired, dp(4f)) / fit.scale
        val intro = introS.value.coerceIn(0f, 1f)
        val press = pressS.value
        // Hiding shrinks the island back into the camera as it fades.
        val shrink = 0.35f + 0.65f * conceal
        val w = widthS.value * press * shrink
        val h = max(dp(if (intro < 1f) 1f else 26f), heightS.value + stretchS.value) * press * (0.6f + 0.4f * conceal)
        val top = topS.value
        rect.set(cx - w / 2f, top, cx + w / 2f, top + h)
        val r = min(radiusS.value * press, h / 2f)
        val absorb = absorbS.value.coerceIn(0f, 1f)

        shapePaint.alpha = (255 * (1f - absorb * 0.3f) * min(1f, intro * 2.5f)).toInt()
        canvas.drawRoundRect(rect, r, r, shapePaint)
        drawSheen(canvas, r)
        val sa = idleA.value.coerceIn(0f, 1f)
        if (sa > 0.01f) {
            val alpha = (((sa - 0.25f) / 0.75f).coerceIn(0f, 1f) * 255 * intro).toInt()
            if (alpha > 2) drawStatus(canvas, alpha, sa)
        }
        if (a == null) { canvas.restore(); if (layered) canvas.restore(); return }

        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)

        val ea = expandedA.value.coerceIn(0f, 1f)
        val ca = compactA.value.coerceIn(0f, 1f)
        val liveTimer = isLiveTimer(a)
        actionHits.clear()
        if (ea > 0.01f) {
            // Nothing-inspired island stays monochrome; no coloured content glow.
            val alpha = (((ea - 0.35f) / 0.65f).coerceIn(0f, 1f) * 255 * intro).toInt()
            // Content rises and scales in slightly as the shape opens, like iOS.
            val lift = (1f - ea) * dp(10f)
            val sc = 0.94f + 0.06f * ea
            canvas.save()
            canvas.translate(0f, -lift)
            canvas.scale(sc, sc, rect.centerX(), rect.top)
            if (alpha > 2) when (a.mode) {
                // Secondary elements (progress, buttons) trail the header by a beat.
                IslandMode.MUSIC -> drawMusicExpanded(canvas, a, alpha, stagger(ea, 0.3f))
                IslandMode.MESSAGE -> drawMessageExpanded(canvas, a, alpha, stagger(ea, 0.22f))
                IslandMode.CALL -> drawCallExpanded(canvas, a, alpha)
                IslandMode.CHARGING -> drawChargingExpanded(canvas, a, alpha)
                IslandMode.TIMER -> if (liveTimer) drawTimerExpanded(canvas, a, alpha, stagger(ea, 0.22f)) else drawGenericExpanded(canvas, a, alpha)
                else -> drawGenericExpanded(canvas, a, alpha)
            }
            canvas.restore()
        } else {
            barY = -1000f
        }
        if (ca > 0.01f && !liveTimer) {
            val alpha = (((ca - 0.3f) / 0.7f).coerceIn(0f, 1f) * 255).toInt()
            if (alpha > 2) {
                // Compact glyphs slide in from the centre as the pill widens.
                canvas.save()
                canvas.scale(1f - (1f - ca) * 0.08f, 1f, rect.centerX(), rect.centerY())
                drawCompact(canvas, a, (alpha * intro).toInt())
                canvas.restore()
            }
        }
        // The timer's ring and digits are shared between compact and expanded and morph between them.
        if (liveTimer) drawTimerMorph(canvas, a, ea, ca, intro)
        if (absorb > 0.01f) drawAbsorb(canvas, a, absorb)
        canvas.restore()
        canvas.restore()
        if (layered) canvas.restore()
    }

    /** Idle island as a mini status bar: time on the left, network and battery on the right. */
    private fun drawStatus(canvas: Canvas, alpha: Int, t: Float) {
        val s = status
        val cy = rect.centerY()
        // Glyphs drift in from the centre (where the camera is) as the pill widens.
        val inset = dp(13f) + (1f - t) * dp(10f)
        if (s.time.isNotEmpty()) {
            statusPaint.color = withAlpha(NothingStyle.foreground, alpha)
            canvas.drawText(s.time, rect.left + inset, cy + statusPaint.textSize * 0.36f, statusPaint)
        }
        // Battery: outline, cap, level fill.
        var right = rect.right - inset
        if (s.battery >= 0) {
            val bw = dp(21f); val bh = dp(10.5f)
            val l = right - bw - dp(2f)
            arcRect.set(l, cy - bh / 2f, l + bw, cy + bh / 2f)
            fillPaint.style = Paint.Style.STROKE
            fillPaint.strokeWidth = dp(1.2f)
            fillPaint.color = withAlpha(NothingStyle.muted, alpha)
            canvas.drawRoundRect(arcRect, dp(3f), dp(3f), fillPaint)
            fillPaint.style = Paint.Style.FILL
            canvas.drawRoundRect(l + bw + dp(0.6f), cy - dp(2.2f), l + bw + dp(2f), cy + dp(2.2f), dp(1f), dp(1f), fillPaint)
            val level = s.battery.coerceIn(0, 100) / 100f
            val color = when {
                s.charging -> accentSoft
                s.battery <= 15 -> NothingStyle.red
                else -> NothingStyle.foreground
            }
            fillPaint.color = withAlpha(color, alpha)
            val pad = dp(2.1f)
            canvas.drawRoundRect(l + pad, cy - bh / 2f + pad, l + pad + (bw - pad * 2) * max(0.08f, level), cy + bh / 2f - pad, dp(1.5f), dp(1.5f), fillPaint)
            if (s.charging) drawBolt(canvas, l + bw / 2f, cy, dp(8f), withAlpha(NothingStyle.background, alpha))
            right = l - dp(7f)
        }
        // Network: wifi fan or four signal bars; a dim "x" style empty bars when offline.
        if (s.wifi) {
            ringPaint.strokeWidth = dp(1.6f)
            val ox = right - dp(7f); val oy = cy + dp(4.5f)
            for (i in 1..3) {
                val rr = dp(3.2f) * i
                val lit = s.bars >= i + 1 || (i == 1 && s.bars >= 0)
                ringPaint.color = withAlpha(if (lit) NothingStyle.foreground else NothingStyle.line, alpha)
                arcRect.set(ox - rr, oy - rr, ox + rr, oy + rr)
                canvas.drawArc(arcRect, 225f, 90f, false, ringPaint)
            }
            fillPaint.color = withAlpha(NothingStyle.foreground, alpha)
            canvas.drawCircle(ox, oy - dp(0.4f), dp(1.3f), fillPaint)
        } else {
            val bw = dp(2.6f); val gap = dp(1.5f)
            for (i in 0 until 4) {
                val l = right - (4 - i) * (bw + gap)
                val bh = dp(3.5f) + i * dp(2.2f)
                fillPaint.color = withAlpha(if (s.bars > i) NothingStyle.foreground else NothingStyle.line, alpha)
                canvas.drawRoundRect(l, cy + dp(5f) - bh, l + bw, cy + dp(5f), dp(1f), dp(1f), fillPaint)
            }
        }
    }

    /** Soft wash of the app's colour (WhatsApp green, Spotify green, YouTube red...). */
    private fun drawGlow(canvas: Canvas, a: LiveActivity, ea: Float) {
        val color = NothingStyle.foreground
        val radius = max(expandedWidth() * 0.75f, 1f)
        var shader = glowShader
        if (shader == null || glowColor != color || glowRadius != radius) {
            shader = RadialGradient(
                0f, 0f, radius,
                intArrayOf(withAlpha(color, 0x66), withAlpha(color, 0x14), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            glowShader = shader; glowColor = color; glowRadius = radius
        }
        matrix.reset()
        matrix.setTranslate(rect.left + dp(48f), rect.top + dp(44f))
        shader.setLocalMatrix(matrix)
        glowPaint.shader = shader
        glowPaint.alpha = (255 * ea).toInt()
        canvas.drawRect(rect, glowPaint)
        glowPaint.shader = null
        glowPaint.alpha = 255
    }

    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sheenShader: LinearGradient? = null

    private fun drawSheen(canvas: Canvas, r: Float) {
        if (sheenStart == 0L) return
        val t = (SystemClock.uptimeMillis() - sheenStart).toFloat() / SHEEN_MS
        if (t < 0f || t > 1f) return
        val band = dp(60f)
        val shader = sheenShader ?: LinearGradient(
            0f, 0f, band, 0f,
            intArrayOf(Color.TRANSPARENT, Color.argb(46, 255, 255, 255), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        ).also { sheenShader = it }
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)
        val x = rect.left - band + (rect.width() + band) * eased
        matrix.reset(); matrix.setTranslate(x, 0f)
        shader.setLocalMatrix(matrix)
        sheenPaint.shader = shader
        sheenPaint.alpha = (255 * sin(Math.PI * t).toFloat()).toInt()
        canvas.drawRoundRect(rect, r, r, sheenPaint)
    }

    private fun drawCompact(canvas: Canvas, a: LiveActivity, alpha: Int) {
        val cy = rect.centerY()
        val s = dp(22f)
        val l = rect.left + dp(7f)
        when (a.mode) {
            IslandMode.MUSIC -> drawArtwork(canvas, a, l, cy - s / 2, s, dp(6f), alpha)
            IslandMode.CALL -> {
                fillPaint.color = withAlpha(NothingStyle.muted, alpha)
                canvas.drawCircle(l + s / 2, cy, s / 2, fillPaint)
                drawPhone(canvas, l + s / 2, cy, dp(13f), alpha, false)
            }
            IslandMode.CHARGING -> drawBolt(canvas, l + s / 2, cy, dp(14f), withAlpha(accentSoft, alpha))
            else -> {
                val img = a.artwork ?: a.appIcon
                if (img != null) drawImage(canvas, img, l, cy - s / 2, s, s / 2, alpha)
                else drawLetterTile(canvas, a, l, cy - s / 2, s, s / 2, alpha)
            }
        }
        val right = rect.right - dp(12f)
        when (a.mode) {
            IslandMode.MUSIC -> drawWave(canvas, right - dp(20f), cy, dp(20f), dp(14f), waveColor, a.isPlaying, alpha)
            IslandMode.CALL -> {
                val t = a.chronometerText()
                if (t != null) text(canvas, t, right, cy + dp(4.5f), dp(80f), smallPaint, NothingStyle.muted, alpha, alignRight = true)
                else drawWave(canvas, right - dp(20f), cy, dp(20f), dp(12f), NothingStyle.muted, true, alpha)
            }
            else -> {
                val label = a.chronometerText() ?: a.title
                text(canvas, label, right, cy + dp(4.5f), rect.width() / 2f - dp(24f), smallPaint, NothingStyle.foreground, alpha, alignRight = true)
            }
        }
    }

    private fun drawMusicExpanded(canvas: Canvas, a: LiveActivity, alpha: Int, late: Int) {
        val pad = dp(22f)
        val l = rect.left + pad
        val t = rect.top + pad
        val rr = rect.right - pad
        val art = dp(58f)
        drawArtwork(canvas, a, l, t, art, dp(14f), alpha)
        val tx = l + art + dp(14f)
        val waveW = dp(24f)
        drawWave(canvas, rr - waveW, t + dp(20f), waveW, dp(18f), waveColor, a.isPlaying, alpha)
        text(canvas, a.title, tx, t + dp(25f), rr - waveW - dp(12f) - tx, titlePaint, Color.WHITE, alpha)
        text(canvas, a.subtitle, tx, t + dp(46f), rr - tx, bodyPaint, GREY, alpha)

        // Progress (fades in a beat after the header, so the open doesn't pop all at once)
        barY = rect.top + dp(108f)
        val pos = if (seeking) (a.durationMs * seekFraction).toLong() else a.livePositionMs()
        val elapsed = formatDuration(pos)
        val remaining = "-" + formatDuration((a.durationMs - pos).coerceAtLeast(0L))
        barLeft = l + dp(44f)
        barRight = rr - dp(48f)
        text(canvas, elapsed, l, barY + dp(4f), dp(40f), smallPaint, GREY, late)
        text(canvas, remaining, rr, barY + dp(4f), dp(46f), smallPaint, GREY, late, alignRight = true)
        val thick = if (seeking) dp(8f) else dp(5f)
        val fraction = if (a.durationMs > 0L) (pos.toFloat() / a.durationMs).coerceIn(0f, 1f) else 0f
        fillPaint.color = withAlpha(waveColor, late * 50 / 255)
        canvas.drawRoundRect(barLeft, barY - thick / 2, barRight, barY + thick / 2, thick / 2, thick / 2, fillPaint)
        fillPaint.color = withAlpha(waveColor, late)
        if (fraction > 0f) canvas.drawRoundRect(
            barLeft, barY - thick / 2, barLeft + (barRight - barLeft) * fraction, barY + thick / 2,
            thick / 2, thick / 2, fillPaint
        )

        // Controls
        val cy = rect.top + dp(146f)
        val cx = rect.centerX()
        drawSkip(canvas, cx - dp(72f), cy, false, late)
        drawPlayPause(canvas, cx, cy, a.isPlaying, late)
        drawSkip(canvas, cx + dp(72f), cy, true, late)
    }

    private fun drawMessageExpanded(canvas: Canvas, a: LiveActivity, alpha: Int, late: Int) {
        val pad = dp(20f)
        val l = rect.left + pad
        val t = rect.top + dp(20f)
        val rr = rect.right - pad
        val av = dp(44f)
        drawAvatar(canvas, a, l, t, av, alpha)
        val tx = l + av + dp(12f)
        text(canvas, "now", rr, t + dp(11f), dp(40f), smallPaint, GREY, alpha, alignRight = true)
        text(canvas, a.appLabel, tx, t + dp(11f), rr - dp(40f) - tx, smallPaint, NothingStyle.foreground, alpha)
        text(canvas, a.title, tx, t + dp(30f), rr - tx, titlePaint, Color.WHITE, alpha)
        text(canvas, a.subtitle, tx, t + dp(48f), rr - tx, bodyPaint, GREY, alpha)
        drawActions(canvas, a, l, rect.top + dp(84f), rr, late)
    }

    private fun drawCallExpanded(canvas: Canvas, a: LiveActivity, alpha: Int) {
        val pad = dp(20f)
        val l = rect.left + pad
        val rr = rect.right - pad
        val av = dp(48f)
        val cy = rect.centerY()
        drawAvatar(canvas, a, l, cy - av / 2, av, alpha)
        val decline = findAction(a, "decline", "reject", "hang up", "end", "dismiss")
        val accept = findAction(a, "answer", "accept")
        val btn = dp(44f)
        var buttonsLeft = rr
        if (decline != null) {
            val x = buttonsLeft - btn
            drawCallButton(canvas, x, cy - btn / 2, btn, NothingStyle.red, true, alpha)
            actionHits.add(RectF(x, cy - btn / 2, x + btn, cy + btn / 2) to decline)
            buttonsLeft = x - dp(12f)
        }
        if (accept != null) {
            val x = buttonsLeft - btn
            drawCallButton(canvas, x, cy - btn / 2, btn, accentSoft, false, alpha)
            actionHits.add(RectF(x, cy - btn / 2, x + btn, cy + btn / 2) to accept)
            buttonsLeft = x - dp(12f)
        }
        val tx = l + av + dp(12f)
        val sub = a.chronometerText()?.let { "${a.appLabel} · $it" } ?: a.subtitle.ifBlank { a.appLabel }
        text(canvas, sub, tx, cy - dp(6f), buttonsLeft - tx, smallPaint, NothingStyle.foreground, alpha)
        text(canvas, a.title, tx, cy + dp(14f), buttonsLeft - tx, titlePaint, Color.WHITE, alpha)
    }

    private fun drawGenericExpanded(canvas: Canvas, a: LiveActivity, alpha: Int) {
        val pad = dp(20f)
        val l = rect.left + pad
        val rr = rect.right - pad
        val s = dp(44f)
        val cy = rect.centerY()
        val img = a.artwork ?: a.appIcon
        if (img != null) drawImage(canvas, img, l, cy - s / 2, s, dp(12f), alpha)
        else drawLetterTile(canvas, a, l, cy - s / 2, s, dp(12f), alpha)
        val tx = l + s + dp(14f)
        val clock = a.chronometerText()
        if (clock != null) {
            text(canvas, clock, rr, cy + dp(10f), dp(140f), bigPaint, NothingStyle.foreground, alpha, alignRight = true)
            text(canvas, a.title, tx, cy - dp(3f), rr - dp(120f) - tx, titlePaint, Color.WHITE, alpha)
            text(canvas, a.appLabel, tx, cy + dp(16f), rr - dp(120f) - tx, bodyPaint, GREY, alpha)
        } else {
            text(canvas, a.title, tx, cy - dp(3f), rr - tx, titlePaint, Color.WHITE, alpha)
            text(canvas, a.subtitle.ifBlank { a.appLabel }, tx, cy + dp(16f), rr - tx, bodyPaint, GREY, alpha)
        }
    }

    private fun drawChargingExpanded(canvas: Canvas, a: LiveActivity, alpha: Int) {
        val cy = rect.centerY()
        val l = rect.left + dp(22f)
        val rr = rect.right - dp(22f)
        drawBolt(canvas, l + dp(9f), cy, dp(18f), withAlpha(accentSoft, alpha))
        text(canvas, "Charging", l + dp(26f), cy + dp(6f), dp(140f), titlePaint, Color.WHITE, alpha)
        // Battery pill
        val bw = dp(34f)
        val bh = dp(16f)
        val bl = rr - bw
        fillPaint.color = withAlpha(Color.WHITE, alpha * 60 / 255)
        canvas.drawRoundRect(bl, cy - bh / 2, rr, cy + bh / 2, dp(5f), dp(5f), fillPaint)
        val level = a.title.filter { it.isDigit() }.toIntOrNull()?.coerceIn(0, 100) ?: 50
        fillPaint.color = withAlpha(NothingStyle.muted, alpha)
        canvas.drawRoundRect(bl + dp(2f), cy - bh / 2 + dp(2f), bl + dp(2f) + (bw - dp(4f)) * level / 100f, cy + bh / 2 - dp(2f), dp(3.5f), dp(3.5f), fillPaint)
        text(canvas, a.title, bl - dp(10f), cy + dp(6f), dp(80f), titlePaint, NothingStyle.muted, alpha, alignRight = true)
    }

    // ---------------------------------------------------------------- timer

    private fun isLiveTimer(a: LiveActivity) = a.mode == IslandMode.TIMER && a.clockMs() != null

    /** Title and buttons; the ring and digits are drawn by drawTimerMorph. */
    private fun drawTimerExpanded(canvas: Canvas, a: LiveActivity, alpha: Int, late: Int) {
        val l = rect.left + dp(20f)
        val rr = rect.right - dp(20f)
        val cy = rect.top + dp(44f)
        val tx = l + dp(42f) + dp(14f)
        val maxW = rr - dp(132f) - tx
        text(canvas, a.title, tx, cy - dp(3f), maxW, titlePaint, Color.WHITE, alpha)
        text(canvas, if (a.pausedTimerMs != null) "Paused" else if (a.chronometerCountDown) "Timer" else a.appLabel, tx, cy + dp(16f), maxW, bodyPaint, GREY, alpha)
        drawActions(canvas, a, l, rect.top + dp(88f), rr, late)
    }

    private fun drawTimerMorph(canvas: Canvas, a: LiveActivity, ea: Float, ca: Float, intro: Float) {
        val vis = (ea + ca).coerceIn(0f, 1f)
        val alpha = (((vis - 0.2f) / 0.8f).coerceIn(0f, 1f) * 255 * intro).toInt()
        if (alpha < 3) return
        // 0 = compact layout, 1 = expanded layout
        val expandedHeight = specFor(Presentation.EXPANDED, a).h
        val m = ((heightS.value - dp(34f)) / (expandedHeight - dp(34f))).coerceIn(0f, 1f)
        val eRing = dp(21f)
        val ringR = lerp(dp(8.5f), eRing, m)
        val ringX = lerp(rect.left + dp(18f), rect.left + dp(20f) + eRing, m)
        val ringY = lerp(rect.centerY(), rect.top + dp(44f), m)
        drawRing(canvas, ringX, ringY, ringR, lerp(dp(2.6f), dp(4f), m), timerFraction(a), alpha)

        updateRoll(a)
        digitPaint.textSize = lerp(dp(13.5f), dp(32f), m)
        val right = lerp(rect.right - dp(13f), rect.right - dp(20f), m)
        val baseline = lerp(rect.centerY() + dp(4.8f), rect.top + dp(55f), m)
        drawRolling(canvas, right, baseline, alpha, a.chronometerCountDown)
    }

    private fun drawRing(canvas: Canvas, cx: Float, cy: Float, r: Float, stroke: Float, fraction: Float, alpha: Int) {
        ringPaint.strokeWidth = stroke
        arcRect.set(cx - r, cy - r, cx + r, cy + r)
        ringPaint.color = withAlpha(accent, alpha * 70 / 255)
        canvas.drawOval(arcRect, ringPaint)
        if (fraction > 0.001f) {
            ringPaint.color = withAlpha(accent, alpha)
            canvas.drawArc(arcRect, -90f, 360f * fraction, false, ringPaint)
        }
    }

    /** Countdown: remaining share of the longest duration seen. Stopwatch: sweeps once a minute. */
    private fun timerFraction(a: LiveActivity): Float {
        val remaining = a.clockMs() ?: return 0f
        if (!a.chronometerCountDown) return remaining.mod(60_000L) / 60_000f
        if (timerTotals.size > 16 && a.key !in timerTotals) timerTotals.clear()
        val total = max(timerTotals[a.key] ?: 0L, remaining)
        timerTotals[a.key] = total
        return if (total <= 0L) 0f else remaining.toFloat() / total
    }

    private fun updateRoll(a: LiveActivity) {
        val t = a.chronometerText().orEmpty()
        if (a.key != rollKey) {
            rollKey = a.key; rollText = t; rollPrev = t; rollAt = 0L
        } else if (t != rollText) {
            rollPrev = rollText; rollText = t; rollAt = SystemClock.uptimeMillis()
        }
    }

    /** Digits that change roll in (from above when counting down, from below when counting up). */
    private fun drawRolling(canvas: Canvas, right: Float, baseline: Float, alpha: Int, countDown: Boolean) {
        val p = digitPaint
        val cur = rollText
        val prev = rollPrev
        val progress = if (rollAt == 0L) 1f
        else ((SystemClock.uptimeMillis() - rollAt).toFloat() / ROLL_MS).coerceIn(0f, 1f)
        val e = 1f - (1f - progress) * (1f - progress) * (1f - progress)
        val shift = p.textSize * 0.75f
        val dir = if (countDown) 1f else -1f
        val sameShape = prev.length == cur.length
        var x = right - p.measureText(cur)
        for (i in cur.indices) {
            val ch = cur[i]
            val s = ch.toString()
            val w = p.measureText(s)
            val old = if (sameShape) prev[i] else ch
            if (old != ch && e < 1f) {
                p.color = withAlpha(accent, (alpha * (1f - e)).toInt())
                canvas.drawText(old.toString(), x, baseline + dir * e * shift, p)
                p.color = withAlpha(accent, (alpha * e).toInt())
                canvas.drawText(s, x, baseline - dir * (1f - e) * shift, p)
            } else {
                p.color = withAlpha(accent, alpha)
                canvas.drawText(s, x, baseline, p)
            }
            x += w
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Alpha that starts rising only after [at] of the open progress, for staggered content. */
    private fun stagger(ea: Float, at: Float) = (((ea - at) / (1f - at)).coerceIn(0f, 1f) * 255).toInt()



    /** While swiping away from an app, its art shrinks with the shape into the island. */
    private fun drawAbsorb(canvas: Canvas, a: LiveActivity, absorb: Float) {
        val img = a.artwork ?: a.appIcon
        val size = min(min(rect.width(), rect.height()) * 0.42f, dp(120f))
        val l = rect.centerX() - size / 2
        val t = rect.centerY() - size / 2
        val alpha = (absorb.coerceIn(0f, 1f) * 255).toInt()
        if (img != null) drawImage(canvas, img, l, t, size, size * 0.24f, alpha)
        else drawLetterTile(canvas, a, l, t, size, size * 0.24f, alpha)
    }

    private fun drawActions(canvas: Canvas, a: LiveActivity, l: Float, t: Float, r: Float, alpha: Int) {
        val actions = visibleActions(a)
        if (actions.isEmpty()) return
        val gap = dp(8f)
        val h = dp(36f)
        val w = (r - l - gap * (actions.size - 1)) / actions.size
        actions.forEachIndexed { i, action ->
            val x = l + i * (w + gap)
            fillPaint.color = withAlpha(NothingStyle.foreground, alpha * 60 / 255)
            canvas.drawRoundRect(x, t, x + w, t + h, h / 2, h / 2, fillPaint)
            smallPaint.textAlign = Paint.Align.CENTER
            text(canvas, action.label, x + w / 2, t + h / 2 + dp(4.5f), w - dp(16f), smallPaint, lighten(NothingStyle.foreground), alpha, center = true)
            smallPaint.textAlign = Paint.Align.LEFT
            actionHits.add(RectF(x, t, x + w, t + h) to action)
        }
    }

    // ---------------------------------------------------------------- drawing helpers

    private fun drawArtwork(canvas: Canvas, a: LiveActivity, l: Float, t: Float, size: Float, radius: Float, alpha: Int) {
        val art = a.artwork
        if (art != null) { drawImage(canvas, art, l, t, size, radius, alpha); return }
        fillPaint.shader = LinearGradient(l, t, l + size, t + size, NothingStyle.foreground, ColorUtils.blendARGB(NothingStyle.foreground, Color.BLACK, 0.6f), Shader.TileMode.CLAMP)
        fillPaint.alpha = alpha
        canvas.drawRoundRect(l, t, l + size, t + size, radius, radius, fillPaint)
        fillPaint.shader = null
        a.appIcon?.let { drawImage(canvas, it, l + size * 0.22f, t + size * 0.22f, size * 0.56f, size * 0.16f, alpha) }
    }

    /** Sender / caller photo with the app's icon as a badge, like iOS communication notifications. */
    private fun drawAvatar(canvas: Canvas, a: LiveActivity, l: Float, t: Float, size: Float, alpha: Int) {
        val photo = a.artwork
        if (photo != null) {
            drawImage(canvas, photo, l, t, size, size / 2, alpha)
            val icon = a.appIcon ?: return
            val b = size * 0.42f
            val bx = l + size - b * 0.8f
            val by = t + size - b * 0.8f
            fillPaint.color = withAlpha(Color.BLACK, alpha)
            canvas.drawCircle(bx + b / 2, by + b / 2, b / 2 + dp(2f), fillPaint)
            drawImage(canvas, icon, bx, by, b, b / 2, alpha)
        } else {
            val icon = a.appIcon
            if (icon != null) drawImage(canvas, icon, l, t, size, size * 0.24f, alpha)
            else drawLetterTile(canvas, a, l, t, size, size * 0.24f, alpha)
        }
    }

    private fun drawLetterTile(canvas: Canvas, a: LiveActivity, l: Float, t: Float, size: Float, radius: Float, alpha: Int) {
        fillPaint.color = withAlpha(NothingStyle.foreground, alpha)
        canvas.drawRoundRect(l, t, l + size, t + size, radius, radius, fillPaint)
        letterPaint.textSize = size * 0.46f
        letterPaint.color = withAlpha(Color.WHITE, alpha)
        val letter = (a.title.firstOrNull { it.isLetterOrDigit() } ?: a.appLabel.firstOrNull() ?: '•').uppercase()
        canvas.drawText(letter, l + size / 2, t + size / 2 + size * 0.16f, letterPaint)
    }

    private fun drawImage(canvas: Canvas, bmp: Bitmap, l: Float, t: Float, size: Float, radius: Float, alpha: Int) {
        if (bmp.isRecycled || bmp.width == 0 || bmp.height == 0) return
        val shader = shaders.getOrPut(bmp) { BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        val scale = size / min(bmp.width, bmp.height).toFloat()
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(l - (bmp.width * scale - size) / 2f, t - (bmp.height * scale - size) / 2f)
        shader.setLocalMatrix(matrix)
        imagePaint.shader = shader
        imagePaint.alpha = alpha
        canvas.drawRoundRect(l, t, l + size, t + size, radius, radius, imagePaint)
        imagePaint.shader = null
    }

    private fun drawWave(canvas: Canvas, left: Float, cy: Float, w: Float, maxH: Float, color: Int, active: Boolean, alpha: Int) {
        val bars = 5
        val bw = w / (bars * 2 - 1)
        fillPaint.color = withAlpha(color, alpha)
        val t = SystemClock.uptimeMillis() / 1000f
        for (i in 0 until bars) {
            val phase = sin(t * (6f + i * 1.7f) + i * 1.3f) * 0.5f + 0.5f
            val h = if (active) maxH * (0.25f + 0.75f * phase) else bw
            val x = left + i * bw * 2
            canvas.drawRoundRect(x, cy - h / 2, x + bw, cy + h / 2, bw / 2, bw / 2, fillPaint)
        }
    }

    private fun drawPlayPause(canvas: Canvas, cx: Float, cy: Float, playing: Boolean, alpha: Int) {
        fillPaint.color = withAlpha(Color.WHITE, alpha)
        val s = dp(15f)
        if (playing) {
            val bw = dp(6f)
            canvas.drawRoundRect(cx - dp(9f), cy - s, cx - dp(9f) + bw, cy + s, dp(2f), dp(2f), fillPaint)
            canvas.drawRoundRect(cx + dp(3f), cy - s, cx + dp(3f) + bw, cy + s, dp(2f), dp(2f), fillPaint)
        } else {
            iconPath.reset()
            iconPath.moveTo(cx - dp(9f), cy - s)
            iconPath.lineTo(cx + dp(13f), cy)
            iconPath.lineTo(cx - dp(9f), cy + s)
            iconPath.close()
            canvas.drawPath(iconPath, fillPaint)
        }
    }

    /** iOS-style double triangle. */
    private fun drawSkip(canvas: Canvas, cx: Float, cy: Float, forward: Boolean, alpha: Int) {
        fillPaint.color = withAlpha(Color.WHITE, alpha)
        val d = if (forward) 1f else -1f
        val s = dp(10f)
        iconPath.reset()
        for (offset in floatArrayOf(-s, 0f)) {
            val base = cx + d * offset
            iconPath.moveTo(base, cy - s)
            iconPath.lineTo(base + d * s, cy)
            iconPath.lineTo(base, cy + s)
            iconPath.close()
        }
        canvas.drawPath(iconPath, fillPaint)
    }

    private fun drawCallButton(canvas: Canvas, l: Float, t: Float, size: Float, color: Int, decline: Boolean, alpha: Int) {
        fillPaint.color = withAlpha(color, alpha)
        canvas.drawCircle(l + size / 2, t + size / 2, size / 2, fillPaint)
        drawPhone(canvas, l + size / 2, t + size / 2, size * 0.55f, alpha, decline)
    }

    private fun drawPhone(canvas: Canvas, cx: Float, cy: Float, size: Float, alpha: Int, rotated: Boolean) {
        val d = phoneIcon ?: return
        d.setTint(Color.WHITE)
        d.alpha = alpha
        d.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
        canvas.save()
        if (rotated) canvas.rotate(135f, cx, cy)
        d.draw(canvas)
        canvas.restore()
    }

    private fun drawBolt(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        fillPaint.color = color
        val s = size / 2
        iconPath.reset()
        iconPath.moveTo(cx + s * 0.15f, cy - s)
        iconPath.lineTo(cx - s * 0.6f, cy + s * 0.12f)
        iconPath.lineTo(cx - s * 0.02f, cy + s * 0.12f)
        iconPath.lineTo(cx - s * 0.15f, cy + s)
        iconPath.lineTo(cx + s * 0.6f, cy - s * 0.12f)
        iconPath.lineTo(cx + s * 0.02f, cy - s * 0.12f)
        iconPath.close()
        canvas.drawPath(iconPath, fillPaint)
    }

    private fun text(
        canvas: Canvas, s: String, x: Float, y: Float, maxW: Float, p: TextPaint, color: Int, alpha: Int,
        alignRight: Boolean = false, center: Boolean = false,
    ) {
        if (maxW <= 0f || s.isBlank()) return
        val out = TextUtils.ellipsize(s.replace('\n', ' '), p, maxW, TextUtils.TruncateAt.END).toString()
        p.color = withAlpha(color, alpha * Color.alpha(color) / 255)
        val dx = if (alignRight && !center) x - p.measureText(out) else x
        canvas.drawText(out, dx, y, p)
    }

    private fun withAlpha(color: Int, alpha: Int) = ColorUtils.setAlphaComponent(color, alpha.coerceIn(0, 255))
    private fun lighten(color: Int) = ColorUtils.blendARGB(color, Color.WHITE, 0.25f)

    private fun findAction(a: LiveActivity, vararg words: String) =
        a.actions.firstOrNull { act -> words.any { act.label.contains(it, ignoreCase = true) } }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val local = MotionEvent.obtain(e)
        local.setLocation(e.x / fit.scale, e.y / fit.scale)
        return try { handleTouch(local) } finally { local.recycle() }
    }

    private fun handleTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                if (presentation == Presentation.EXPANDED) callbacks?.onCollapseRequest()
                return false
            }
            MotionEvent.ACTION_DOWN -> {
                hit.set(rect)
                hit.inset(-dp(10f), -dp(10f))
                if (isConcealed || !hit.contains(e.x, e.y)) return false
                tracking = true
                dragging = false
                longFired = false
                downX = e.x
                downY = e.y
                val a = activity
                seeking = presentation == Presentation.EXPANDED && a?.mode == IslandMode.MUSIC &&
                    a.durationMs > 0L && abs(e.y - barY) < dp(16f) &&
                    e.x >= barLeft - dp(12f) && e.x <= barRight + dp(12f)
                if (seeking) seekFraction = fractionAt(e.x)
                pressS.target = if (presentation == Presentation.EXPANDED) 0.985f else 0.94f
                if (presentation == Presentation.COMPACT) postDelayed(longPress, LONG_PRESS_MS)
                callbacks?.onInteraction()
                settled = false
                kick()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val dx = e.x - downX
                val dy = e.y - downY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                    removeCallbacks(longPress)
                }
                if (seeking) {
                    seekFraction = fractionAt(e.x)
                    invalidate()
                } else if (dragging) {
                    stretchS.snap(rubber(dy))
                    kick()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                finishTouch()
                val dy = e.y - downY
                val a = activity
                when {
                    longFired -> Unit
                    seeking && a != null -> {
                        val pos = (a.durationMs * seekFraction).toLong()
                        activity = a.copy(positionMs = pos, positionUpdatedAt = SystemClock.elapsedRealtime())
                        callbacks?.onMedia(a, IslandBus.MEDIA_SEEK, pos)
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    dragging && dy < -dp(26f) -> swipeUp()
                    dragging && dy > dp(26f) && presentation == Presentation.COMPACT -> callbacks?.onExpandRequest()
                    !dragging -> {
                        performClick()
                        onTapUp(e.x, e.y)
                    }
                }
                seeking = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                finishTouch()
                seeking = false
                return true
            }
        }
        return tracking
    }

    private fun finishTouch() {
        tracking = false
        removeCallbacks(longPress)
        pressS.target = 1f
        stretchS.target = 0f
        settled = false
        kick()
    }

    private fun rubber(dy: Float): Float {
        val limit = dp(34f)
        return sign(dy) * limit * (1f - exp(-abs(dy) / dp(140f)))
    }

    private fun fractionAt(x: Float) = ((x - barLeft) / max(1f, barRight - barLeft)).coerceIn(0f, 1f)

    private fun swipeUp() {
        val a = activity ?: return
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (presentation == Presentation.EXPANDED && !a.isTransient) callbacks?.onCollapseRequest()
        else callbacks?.onDismiss(a)
    }

    /** Hit on interactive controls (buttons, transport) must stay instant — no double-tap wait. */
    private fun isControlHit(x: Float, y: Float): Boolean {
        if (presentation != Presentation.EXPANDED) return false
        if (actionHits.any { it.first.contains(x, y) }) return true
        val a = activity ?: return false
        return a.mode == IslandMode.MUSIC && y > barY + dp(16f)
    }

    private fun onTapUp(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (isControlHit(x, y) || presentation == Presentation.IDLE) { handleTap(x, y); return }
        val near = abs(x - lastTapX) < dp(48f) && abs(y - lastTapY) < dp(48f)
        if (pendingTap != null && now - lastTapAt <= doubleTapMs && near) {
            removeCallbacks(pendingTap); pendingTap = null; lastTapAt = 0L
            onDoubleTap()
            return
        }
        lastTapAt = now; lastTapX = x; lastTapY = y
        val r = Runnable { pendingTap = null; handleTap(x, y) }
        pendingTap = r
        postDelayed(r, doubleTapMs)
    }

    private fun onDoubleTap() {
        val a = activity ?: return
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        // Little squish so the gesture registers visually before the morph.
        // Preserve velocity rather than teleporting the whole island on the second tap.
        pressS.target = 1f
        when (presentation) {
            Presentation.COMPACT -> callbacks?.onExpandRequest()
            Presentation.EXPANDED -> if (!a.isTransient) callbacks?.onCollapseRequest() else callbacks?.onDismiss(a)
            Presentation.IDLE -> Unit
        }
        settled = false
        kick()
    }

    private fun handleTap(x: Float, y: Float) {
        val a = activity ?: return
        when (presentation) {
            Presentation.IDLE -> return
            Presentation.COMPACT -> callbacks?.onOpen(a)
            Presentation.EXPANDED -> {
                actionHits.firstOrNull { it.first.contains(x, y) }?.let {
                    callbacks?.onAction(a, it.second)
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    return
                }
                if (a.mode == IslandMode.MUSIC && y > barY + dp(16f)) {
                    val cx = rect.centerX()
                    when {
                        x < cx - dp(36f) -> callbacks?.onMedia(a, IslandBus.MEDIA_PREVIOUS)
                        x > cx + dp(36f) -> callbacks?.onMedia(a, IslandBus.MEDIA_NEXT)
                        else -> {
                            // Flip instantly so the button feels responsive; the real state follows.
                            activity = a.copy(
                                isPlaying = !a.isPlaying,
                                positionMs = a.livePositionMs(),
                                positionUpdatedAt = SystemClock.elapsedRealtime(),
                            )
                            callbacks?.onMedia(a, IslandBus.MEDIA_TOGGLE)
                            kick()
                        }
                    }
                } else {
                    callbacks?.onOpen(a)
                }
            }
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun refreshContentDescription() {
        val a = activity
        contentDescription = when {
            a == null -> "Dynamic Island"
            a.mode == IslandMode.MUSIC -> "Now playing ${a.title} by ${a.subtitle}"
            else -> "${a.appLabel}: ${a.title}"
        }
    }

    companion object {
        private val GREY = NothingStyle.muted
        private const val LONG_PRESS_MS = 350L
        private const val SHEEN_MS = 800L
        private const val INTRO_HOLD_MS = 190L
        private const val ROLL_MS = 320L
        private const val SHAPE_K = 190f
        private const val WIDTH_DAMP = 0.72f
        private const val HEIGHT_DAMP = 0.8f
        private const val CONTENT_IN_K = 150f
        private const val CONTENT_OUT_K = 420f
    }
}
