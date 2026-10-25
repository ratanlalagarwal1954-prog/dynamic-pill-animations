package com.dynamicdroid.app

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * Hosts the island window and decides what it shows.
 *
 * - One ongoing activity (music, call, timer, navigation) lives in the compact island.
 * - Transient ones (messages, charging) briefly expand on top, then hand back.
 * - The ongoing activity hides while its app is open and flies into the island when you leave it.
 * - The window only ever grows before an animation and shrinks after it settles, so it never
 *   clips mid-animation and never blocks touches on the apps underneath.
 */
class IslandOverlayService : Service(), IslandBus.Listener, IslandView.Callbacks {
    private var windowManager: WindowManager? = null
    private var island: IslandView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    private var ongoing: LiveActivity? = null
    private var transient: LiveActivity? = null
    private var presentation = Presentation.COMPACT
    private var foregroundPkg: String? = null
    private var watcher: ForegroundWatcher? = null
    /** A newly started timer opens briefly, then morphs up into the pill. */
    private var timerPeek = false

    private val clearTransient = Runnable { transient = null; render() }
    private val autoCollapse = Runnable {
        timerPeek = false
        if (transient == null && presentation == Presentation.EXPANDED) {
            presentation = Presentation.COMPACT
            render()
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_POWER_CONNECTED) return
            val level = getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return
            onActivity(LiveActivity(
                key = "charging", packageName = packageName, mode = IslandMode.CHARGING,
                title = "$level%", subtitle = "Charging", appLabel = "Battery",
                appIcon = null, artwork = null, accent = AppStyle.GREEN,
            ))
        }
    }

    // ---------------------------------------------------------------- wake / unlock intro
    //
    // The intro used to fire on SCREEN_ON, i.e. behind the lock screen, so it was never seen.
    // Now the island hides as a dot when the screen turns off and grows out once the phone is
    // actually unlocked and the unlock animation has cleared.

    private var introArmed = false
    private val playIntro = Runnable { island?.playIntro() }
    private val waitForUnlock = object : Runnable {
        override fun run() {
            if (!introArmed) return
            val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
            val screenOn = getSystemService(PowerManager::class.java)?.isInteractive != false
            when {
                !screenOn -> Unit
                !locked -> {
                    introArmed = false
                    handler.postDelayed(playIntro, UNLOCK_SETTLE_MS)
                }
                else -> handler.postDelayed(this, 200L)
            }
        }
    }

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    handler.removeCallbacks(waitForUnlock)
                    handler.removeCallbacks(playIntro)
                    introArmed = true
                    island?.hideForIntro()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    handler.removeCallbacks(waitForUnlock)
                    handler.post(waitForUnlock)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Dynamic Island is on")
            .setContentText("Music, calls and messages appear at the top of your screen")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build())
        ContextCompat.registerReceiver(
            this, powerReceiver, IntentFilter(Intent.ACTION_POWER_CONNECTED), ContextCompat.RECEIVER_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, wakeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        watcher = ForegroundWatcher(this, ::onForegroundChanged)
        IslandAccessibilityService.onChanged = { handler.post { reattach() } }
        attachWindow()
        IslandBus.listener = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (island == null && attachWindow()) render()
        if (ongoing != null) watcher?.start()
        return START_STICKY
    }

    /**
     * Prefers an accessibility overlay (above the status bar, so taps at the top reach the island)
     * and falls back to a normal overlay (below the status bar) when that isn't switched on.
     */
    private fun attachWindow(): Boolean {
        val a11y = IslandAccessibilityService.instance
        if (a11y == null && !Settings.canDrawOverlays(this)) return false
        val host: Context = a11y ?: this
        val wm = host.getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val view = IslandView(host).also { it.callbacks = this }
        island = view
        val (w, h) = view.windowSizeFor(null, Presentation.IDLE)
        val type = if (a11y != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val lp = WindowManager.LayoutParams(
            w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 0
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        params = lp
        if (runCatching { wm.addView(view, lp) }.isFailure) {
            island = null; params = null
            return false
        }
        view.hideForIntro()
        view.post { view.playIntro() }
        return true
    }

    private fun detachWindow() {
        island?.let { v -> runCatching { windowManager?.removeView(v) } }
        island = null
        params = null
    }

    private fun reattach() {
        detachWindow()
        if (attachWindow()) render()
    }

    // ---------------------------------------------------------------- state

    private fun render(absorb: Boolean = false) {
        val view = island ?: return
        val t = transient
        val o = ongoing?.takeIf { it.packageName != foregroundPkg }
        val (a, p) = when {
            t != null -> t to Presentation.EXPANDED
            o != null -> o to presentation
            else -> null to Presentation.IDLE
        }
        val changedPresentation = view.activity?.key != a?.key || view.presentation != p
        // Per-second notification updates must not keep restarting the timer's peek.
        if (changedPresentation) {
            handler.removeCallbacks(autoCollapse)
            if (t == null && p == Presentation.EXPANDED && a?.mode != IslandMode.CALL) {
                val delay = if (timerPeek && a?.mode == IslandMode.TIMER) TIMER_PEEK_MS else EXPANDED_TIMEOUT_MS
                handler.postDelayed(autoCollapse, delay)
            }
        }
        growWindow(view.windowSizeFor(a, p), absorb)
        view.show(a, p, absorb)
    }

    override fun onActivity(activity: LiveActivity) {
        if (activity.isTransient) {
            transient = activity
            handler.removeCallbacks(clearTransient)
            handler.postDelayed(clearTransient, if (activity.mode == IslandMode.CHARGING) 3_000L else MESSAGE_TIMEOUT_MS)
        } else {
            val isNew = ongoing?.key != activity.key
            ongoing = activity
            if (isNew) {
                timerPeek = activity.mode == IslandMode.TIMER
                presentation = when (activity.mode) {
                    IslandMode.CALL, IslandMode.TIMER -> Presentation.EXPANDED
                    else -> Presentation.COMPACT
                }
            }
            watcher?.start()
        }
        render()
    }

    override fun onRemoved(key: String) {
        if (transient?.key == key) {
            transient = null
            handler.removeCallbacks(clearTransient)
        }
        if (ongoing?.key == key) {
            ongoing = IslandBus.latestOngoing()
            presentation = Presentation.COMPACT
            if (ongoing == null) watcher?.stop()
        }
        render()
    }

    private fun onForegroundChanged(pkg: String) {
        val previous = foregroundPkg
        foregroundPkg = pkg
        val o = ongoing ?: return
        if (previous == o.packageName && pkg != o.packageName && transient == null) {
            // Swiped away from the app: fly it into the island.
            presentation = Presentation.COMPACT
            render(absorb = island?.activity == null &&
                getSystemService(PowerManager::class.java)?.isInteractive == true &&
                getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false)
        } else if (pkg == o.packageName || previous == o.packageName) {
            render()
        }
    }

    // ---------------------------------------------------------------- island callbacks

    override fun onOpen(activity: LiveActivity) {
        launch(activity.contentIntent, activity.packageName)
        if (activity === transient || activity.key == transient?.key) {
            transient = null
            handler.removeCallbacks(clearTransient)
        } else {
            presentation = Presentation.COMPACT
        }
        render()
    }

    override fun onAction(activity: LiveActivity, action: IslandAction) {
        send(action.intent)
        if (activity.key == transient?.key) {
            transient = null
            handler.removeCallbacks(clearTransient)
            render()
        }
    }

    override fun onMedia(activity: LiveActivity, command: String, positionMs: Long?) {
        if (IslandBus.mediaControl?.invoke(command, positionMs) == true) return
        // No media session: fall back to the notification's own buttons.
        val words = when (command) {
            IslandBus.MEDIA_PREVIOUS -> listOf("previous", "prev", "rewind", "back")
            IslandBus.MEDIA_NEXT -> listOf("next", "skip", "forward")
            IslandBus.MEDIA_TOGGLE -> listOf("pause", "play")
            else -> emptyList()
        }
        activity.actions.firstOrNull { a -> words.any { a.label.contains(it, ignoreCase = true) } }
            ?.let { send(it.intent) }
    }

    override fun onExpandRequest() {
        if (transient != null || ongoing == null) return
        timerPeek = false
        presentation = Presentation.EXPANDED
        render()
    }

    override fun onCollapseRequest() {
        if (transient != null) {
            transient = null
            handler.removeCallbacks(clearTransient)
        } else {
            presentation = Presentation.COMPACT
        }
        render()
    }

    override fun onDismiss(activity: LiveActivity) = onCollapseRequest()

    override fun onInteraction() {
        // Keep things open while the user is touching them.
        transient?.let {
            handler.removeCallbacks(clearTransient)
            handler.postDelayed(clearTransient, MESSAGE_TIMEOUT_MS)
        }
        if (presentation == Presentation.EXPANDED && transient == null) {
            handler.removeCallbacks(autoCollapse)
            if (ongoing?.mode != IslandMode.CALL) handler.postDelayed(autoCollapse, EXPANDED_TIMEOUT_MS)
        }
    }

    override fun onSettled() {
        val view = island ?: return
        val lp = params ?: return
        val a = view.activity
        val (w, h) = view.windowSizeFor(a, view.presentation)
        var changed = false
        if (lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0) {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            changed = true
        }
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            changed = true
        }
        if (changed) runCatching { windowManager?.updateViewLayout(view, lp) }
    }

    // ---------------------------------------------------------------- window

    private fun growWindow(target: Pair<Int, Int>, absorb: Boolean) {
        val view = island ?: return
        val lp = params ?: return
        val dm = resources.displayMetrics
        val (w, h) = if (absorb || view.isAbsorbing) {
            dm.widthPixels to dm.heightPixels
        } else {
            max(lp.width, target.first) to max(lp.height, target.second)
        }
        var flags = lp.flags
        // During the fly-in the window covers the screen, so let every touch pass through.
        if (absorb) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (lp.width == w && lp.height == h && lp.flags == flags) return
        lp.width = w
        lp.height = h
        lp.flags = flags
        runCatching { windowManager?.updateViewLayout(view, lp) }
    }

    // ---------------------------------------------------------------- launching

    private fun launch(pendingIntent: PendingIntent?, pkg: String) {
        if (send(pendingIntent)) return
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(it) }
        }
    }

    /**
     * Android 14+ blocks activity launches from a PendingIntent sent by a background app unless
     * the sender opts in. This was why tapping "open" often did nothing.
     */
    private fun send(pendingIntent: PendingIntent?): Boolean {
        pendingIntent ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pendingIntent.send(this, 0, null, null, null, null, options.toBundle())
            } else {
                pendingIntent.send()
            }
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    override fun onDestroy() {
        if (IslandBus.listener === this) IslandBus.listener = null
        IslandAccessibilityService.onChanged = null
        watcher?.stop()
        handler.removeCallbacksAndMessages(null)
        detachWindow()
        runCatching { unregisterReceiver(powerReceiver) }
        runCatching { unregisterReceiver(wakeReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Dynamic Island service", NotificationManager.IMPORTANCE_MIN)
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "dynamic_island"
        private const val NOTIFICATION_ID = 7104
        private const val EXPANDED_TIMEOUT_MS = 6_000L
        private const val MESSAGE_TIMEOUT_MS = 5_000L
        private const val TIMER_PEEK_MS = 2_600L
        /** Lets the system's unlock animation finish so the intro is actually visible. */
        private const val UNLOCK_SETTLE_MS = 320L
    }
}
