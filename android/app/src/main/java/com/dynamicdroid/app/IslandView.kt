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
 *  - compact: tap opens the app, long-press (or pull down) expands
 *  - expanded: tap content opens the app, controls work in place, swipe up / tap outside collapses
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

    // Shape
    private val widthS = Spring(dp(104f), 430f, 0.76f, 0.5f)
    private val heightS = Spring(dp(32f), 430f, 0.8f, 0.5f)
    private val radiusS = Spring(dp(16f), 430f, 0.9f, 0.5f)
    private val topS = Spring(baseTop, 400f, 0.86f, 0.5f)
    // Content and gesture feedback
    private val expandedA = Spring(0f, 240f, 1f, 0.004f)
    private val compactA = Spring(0f, 320f, 1f, 0.004f)
    private val pressS = Spring(1f, 700f, 0.62f, 0.0008f)
    private val stretchS = Spring(0f, 520f, 0.68f, 0.5f)
    private val absorbS = Spring(0f, 150f, 1f, 0.004f)
    private val shapeSprings = listOf(widthS, heightS, radiusS, topS)
    private val springs = listOf(widthS, heightS, radiusS, topS, expandedA, compactA, pressS, stretchS, absorbS)

    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium; textSize = dp(16f) }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(14f) }
    private val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium; textSize = dp(11.5f) }
    private val bigPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium; textSize = dp(26f) }
    private val letterPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }

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

    // Frame loop
    private var running = false
    private var lastFrame = 0L
    private var settled = true

    init {
        isClickable = true
        isFocusable = false
        refreshContentDescription()
    }

    // ---------------------------------------------------------------- public API

    fun show(next: LiveActivity?, nextPresentation: Presentation, absorb: Boolean = false) {
        val p = if (next == null) Presentation.IDLE else nextPresentation
        activity = next
        presentation = p
        val spec = specFor(p, next)

        if (absorb && next != null) {
            val dm = resources.displayMetrics
            widthS.snap(dm.widthPixels * 0.84f)
            heightS.snap(dm.heightPixels * 0.58f)
            radiusS.snap(dp(46f))
            topS.snap(dm.heightPixels * 0.2f)
            absorbS.snap(1f)
            compactA.snap(0f)
            expandedA.snap(0f)
            shapeSprings.forEach { it.stiffness = 170f; it.dampingRatio = 0.84f }
        }
        widthS.target = spec.w
        heightS.target = spec.h
        radiusS.target = spec.r
        topS.target = baseTop
        absorbS.target = 0f
        expandedA.target = if (p == Presentation.EXPANDED) 1f else 0f
        compactA.target = if (p == Presentation.COMPACT) 1f else 0f
        refreshContentDescription()
        settled = false
        kick()
    }

    /** Pixel size the overlay window needs for the current target state. */
    fun windowSizeFor(next: LiveActivity?, p: Presentation): Pair<Int, Int> {
        val spec = specFor(if (next == null) Presentation.IDLE else p, next)
        return Pair((spec.w + dp(40f)).toInt(), (baseTop + spec.h + dp(46f)).toInt())
    }

    val isAbsorbing: Boolean get() = absorbS.value > 0.002f

    // ---------------------------------------------------------------- layout specs

    private data class Spec(val w: Float, val h: Float, val r: Float)

    private fun expandedWidth() = min(resources.displayMetrics.widthPixels - dp(16f), dp(372f))

    private fun visibleActions(a: LiveActivity) = a.actions.take(3)

    private fun specFor(p: Presentation, a: LiveActivity?): Spec {
        if (p == Presentation.IDLE || a == null) return Spec(dp(104f), dp(32f), dp(16f))
        if (p == Presentation.COMPACT) return Spec(dp(214f), dp(34f), dp(17f))
        val w = expandedWidth()
        return when (a.mode) {
            IslandMode.MUSIC -> Spec(w, dp(178f), dp(46f))
            IslandMode.CALL -> Spec(w, dp(88f), dp(44f))
            IslandMode.MESSAGE -> Spec(w, dp(if (visibleActions(a).isEmpty()) 84f else 134f), dp(40f))
            IslandMode.CHARGING -> Spec(w, dp(64f), dp(32f))
            else -> Spec(w, dp(84f), dp(42f))
        }
    }

    // ---------------------------------------------------------------- frame loop

    private fun kick() {
        if (running) return
        running = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isAttachedToWindow) { running = false; return }
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
            shapeSprings.forEach { it.stiffness = 430f; it.dampingRatio = if (it === widthS) 0.76f else 0.82f }
            callbacks?.onSettled()
        }
        val continuous = continuousDelay()
        when {
            !rest || tracking -> Choreographer.getInstance().postFrameCallback(this)
            continuous != null -> { lastFrame = 0L; Choreographer.getInstance().postFrameCallbackDelayed(this, continuous) }
            else -> running = false
        }
    }

    /** Waveforms and clocks keep redrawing even when the shape is at rest. */
    private fun continuousDelay(): Long? {
        val a = activity ?: return null
        if (presentation == Presentation.IDLE) return null
        return when {
            a.mode == IslandMode.MUSIC && a.isPlaying -> 16L
            a.mode == IslandMode.CALL -> 33L
            a.chronometerBase != null -> 250L
            else -> null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        kick()
    }

    override fun onDetachedFromWindow() {
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
                baseTop = max(dp(4f), cut.exactCenterY() - dp(17f))
                if (!isAbsorbing) topS.target = baseTop
                kick()
            }
        }
        return super.onApplyWindowInsets(insets)
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val a = activity
        val cx = width / 2f
        val press = pressS.value
        val w = widthS.value * press
        val h = max(dp(26f), heightS.value + stretchS.value) * press
        val top = topS.value
        rect.set(cx - w / 2f, top, cx + w / 2f, top + h)
        val r = min(radiusS.value * press, h / 2f)
        val absorb = absorbS.value.coerceIn(0f, 1f)

        shapePaint.alpha = (255 * (1f - absorb * 0.3f)).toInt()
        canvas.drawRoundRect(rect, r, r, shapePaint)
        if (a == null) return

        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)

        val ea = expandedA.value.coerceIn(0f, 1f)
        val ca = compactA.value.coerceIn(0f, 1f)
        actionHits.clear()
        if (ea > 0.01f) {
            drawGlow(canvas, a, ea)
            val alpha = (((ea - 0.35f) / 0.65f).coerceIn(0f, 1f) * 255).toInt()
            if (alpha > 2) when (a.mode) {
                IslandMode.MUSIC -> drawMusicExpanded(canvas, a, alpha)
                IslandMode.MESSAGE -> drawMessageExpanded(canvas, a, alpha)
                IslandMode.CALL -> drawCallExpanded(canvas, a, alpha)
                IslandMode.CHARGING -> drawChargingExpanded(canvas, a, alpha)
                else -> drawGenericExpanded(canvas, a, alpha)
            }
        } else {
            barY = -1000f
        }
        if (ca > 0.01f) {
            val alpha = (((ca - 0.3f) / 0.7f).coerceIn(0f, 1f) * 255).toInt()
            if (alpha > 2) drawCompact(canvas, a, alpha)
        }
        if (absorb > 0.01f) drawAbsorb(canvas, a, absorb)
        canvas.restore()
    }

    /** Soft wash of the app's colour (WhatsApp green, Spotify green, YouTube red...). */
    private fun drawGlow(canvas: Canvas, a: LiveActivity, ea: Float) {
        val color = a.accent
        glowPaint.shader = RadialGradient(
            rect.left + dp(48f), rect.top + dp(44f), max(rect.width() * 0.75f, 1f),
            intArrayOf(withAlpha(color, (0x66 * ea).toInt()), withAlpha(color, (0x14 * ea).toInt()), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, glowPaint)
        glowPaint.shader = null
    }

    private fun drawCompact(canvas: Canvas, a: LiveActivity, alpha: Int) {
        val cy = rect.centerY()
        val s = dp(22f)
        val l = rect.left + dp(7f)
        when (a.mode) {
            IslandMode.MUSIC -> drawArtwork(canvas, a, l, cy - s / 2, s, dp(6f), alpha)
            IslandMode.CALL -> {
                fillPaint.color = withAlpha(AppStyle.GREEN, alpha)
                canvas.drawCircle(l + s / 2, cy, s / 2, fillPaint)
                drawPhone(canvas, l + s / 2, cy, dp(13f), alpha, false)
            }
            IslandMode.CHARGING -> drawBolt(canvas, l + s / 2, cy, dp(14f), withAlpha(AppStyle.GREEN, alpha))
            else -> {
                val img = a.artwork ?: a.appIcon
                if (img != null) drawImage(canvas, img, l, cy - s / 2, s, s / 2, alpha)
                else drawLetterTile(canvas, a, l, cy - s / 2, s, s / 2, alpha)
            }
        }
        val right = rect.right - dp(12f)
        when (a.mode) {
            IslandMode.MUSIC -> drawWave(canvas, right - dp(20f), cy, dp(20f), dp(14f), a.accent, a.isPlaying, alpha)
            IslandMode.CALL -> {
                val t = a.chronometerText()
                if (t != null) text(canvas, t, right, cy + dp(4.5f), dp(80f), smallPaint, AppStyle.GREEN, alpha, alignRight = true)
                else drawWave(canvas, right - dp(20f), cy, dp(20f), dp(12f), AppStyle.GREEN, true, alpha)
            }
            else -> {
                val label = a.chronometerText() ?: a.title
                text(canvas, label, right, cy + dp(4.5f), rect.width() / 2f - dp(24f), smallPaint, a.accent, alpha, alignRight = true)
            }
        }
    }

    private fun drawMusicExpanded(canvas: Canvas, a: LiveActivity, alpha: Int) {
        val pad = dp(22f)
        val l = rect.left + pad
        val t = rect.top + pad
        val rr = rect.right - pad
        val art = dp(58f)
        drawArtwork(canvas, a, l, t, art, dp(14f), alpha)
        val tx = l + art + dp(14f)
        val waveW = dp(24f)
        drawWave(canvas, rr - waveW, t + dp(20f), waveW, dp(18f), a.accent, a.isPlaying, alpha)
        text(canvas, a.title, tx, t + dp(25f), rr - waveW - dp(12f) - tx, titlePaint, Color.WHITE, alpha)
        text(canvas, a.subtitle, tx, t + dp(46f), rr - tx, bodyPaint, GREY, alpha)

        // Progress
        barY = rect.top + dp(108f)
        val pos = if (seeking) (a.durationMs * seekFraction).toLong() else a.livePositionMs()
        val elapsed = formatDuration(pos)
        val remaining = "-" + formatDuration((a.durationMs - pos).coerceAtLeast(0L))
        barLeft = l + dp(44f)
        barRight = rr - dp(48f)
        text(canvas, elapsed, l, barY + dp(4f), dp(40f), smallPaint, GREY, alpha)
        text(canvas, remaining, rr, barY + dp(4f), dp(46f), smallPaint, GREY, alpha, alignRight = true)
        val thick = if (seeking) dp(8f) else dp(5f)
        val fraction = if (a.durationMs > 0L) (pos.toFloat() / a.durationMs).coerceIn(0f, 1f) else 0f
        fillPaint.color = withAlpha(Color.WHITE, alpha * 50 / 255)
        canvas.drawRoundRect(barLeft, barY - thick / 2, barRight, barY + thick / 2, thick / 2, thick / 2, fillPaint)
        fillPaint.color = withAlpha(Color.WHITE, alpha)
        if (fraction > 0f) canvas.drawRoundRect(
            barLeft, barY - thick / 2, barLeft + (barRight - barLeft) * fraction, barY + thick / 2,
            thick / 2, thick / 2, fillPaint
        )

        // Controls
        val cy = rect.top + dp(146f)
        val cx = rect.centerX()
        drawSkip(canvas, cx - dp(72f), cy, false, alpha)
        drawPlayPause(canvas, cx, cy, a.isPlaying, alpha)
        drawSkip(canvas, cx + dp(72f), cy, true, alpha)
    }

    private fun drawMessageExpanded(canvas: Canvas, a: LiveActivity, alpha: Int) {
        val pad = dp(20f)
        val l = rect.left + pad
        val t = rect.top + dp(20f)
        val rr = rect.right - pad
        val av = dp(44f)
        drawAvatar(canvas, a, l, t, av, alpha)
        val tx = l + av + dp(12f)
        text(canvas, "now", rr, t + dp(11f), dp(40f), smallPaint, GREY, alpha, alignRight = true)
        text(canvas, a.appLabel, tx, t + dp(11f), rr - dp(40f) - tx, smallPaint, a.accent, alpha)
        text(canvas, a.title, tx, t + dp(30f), rr - tx, titlePaint, Color.WHITE, alpha)
        text(canvas, a.subtitle, tx, t + dp(48f), rr - tx, bodyPaint, GREY, alpha)
        drawActions(canvas, a, l, rect.top + dp(84f), rr, alpha)
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
            drawCallButton(canvas, x, cy - btn / 2, btn, AppStyle.RED, true, alpha)
            actionHits.add(RectF(x, cy - btn / 2, x + btn, cy + btn / 2) to decline)
            buttonsLeft = x - dp(12f)
        }
        if (accept != null) {
            val x = buttonsLeft - btn
            drawCallButton(canvas, x, cy - btn / 2, btn, AppStyle.GREEN, false, alpha)
            actionHits.add(RectF(x, cy - btn / 2, x + btn, cy + btn / 2) to accept)
            buttonsLeft = x - dp(12f)
        }
        val tx = l + av + dp(12f)
        val sub = a.chronometerText()?.let { "${a.appLabel} · $it" } ?: a.subtitle.ifBlank { a.appLabel }
        text(canvas, sub, tx, cy - dp(6f), buttonsLeft - tx, smallPaint, a.accent, alpha)
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
            text(canvas, clock, rr, cy + dp(10f), dp(140f), bigPaint, a.accent, alpha, alignRight = true)
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
        drawBolt(canvas, l + dp(9f), cy, dp(18f), withAlpha(AppStyle.GREEN, alpha))
        text(canvas, "Charging", l + dp(26f), cy + dp(6f), dp(140f), titlePaint, Color.WHITE, alpha)
        // Battery pill
        val bw = dp(34f)
        val bh = dp(16f)
        val bl = rr - bw
        fillPaint.color = withAlpha(Color.WHITE, alpha * 60 / 255)
        canvas.drawRoundRect(bl, cy - bh / 2, rr, cy + bh / 2, dp(5f), dp(5f), fillPaint)
        val level = a.title.filter { it.isDigit() }.toIntOrNull()?.coerceIn(0, 100) ?: 50
        fillPaint.color = withAlpha(AppStyle.GREEN, alpha)
        canvas.drawRoundRect(bl + dp(2f), cy - bh / 2 + dp(2f), bl + dp(2f) + (bw - dp(4f)) * level / 100f, cy + bh / 2 - dp(2f), dp(3.5f), dp(3.5f), fillPaint)
        text(canvas, a.title, bl - dp(10f), cy + dp(6f), dp(80f), titlePaint, AppStyle.GREEN, alpha, alignRight = true)
    }

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
            fillPaint.color = withAlpha(a.accent, alpha * 60 / 255)
            canvas.drawRoundRect(x, t, x + w, t + h, h / 2, h / 2, fillPaint)
            smallPaint.textAlign = Paint.Align.CENTER
            text(canvas, action.label, x + w / 2, t + h / 2 + dp(4.5f), w - dp(16f), smallPaint, lighten(a.accent), alpha, center = true)
            smallPaint.textAlign = Paint.Align.LEFT
            actionHits.add(RectF(x, t, x + w, t + h) to action)
        }
    }

    // ---------------------------------------------------------------- drawing helpers

    private fun drawArtwork(canvas: Canvas, a: LiveActivity, l: Float, t: Float, size: Float, radius: Float, alpha: Int) {
        val art = a.artwork
        if (art != null) { drawImage(canvas, art, l, t, size, radius, alpha); return }
        fillPaint.shader = LinearGradient(l, t, l + size, t + size, a.accent, ColorUtils.blendARGB(a.accent, Color.BLACK, 0.6f), Shader.TileMode.CLAMP)
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
        fillPaint.color = withAlpha(a.accent, alpha)
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
        when (e.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                if (presentation == Presentation.EXPANDED) callbacks?.onCollapseRequest()
                return false
            }
            MotionEvent.ACTION_DOWN -> {
                hit.set(rect)
                hit.inset(-dp(10f), -dp(10f))
                if (!hit.contains(e.x, e.y)) return false
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
                pressS.target = if (presentation == Presentation.EXPANDED) 0.985f else 0.93f
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
                        handleTap(e.x, e.y)
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
        private val GREY = Color.rgb(152, 152, 159)
        private const val LONG_PRESS_MS = 350L
    }
}
