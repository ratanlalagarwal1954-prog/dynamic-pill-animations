package com.dynamicdroid.app

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.SeekBar
import androidx.appcompat.widget.SwitchCompat
import android.content.res.ColorStateList
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private lateinit var preview: IslandView
    private var demo: LiveActivity? = null
    private var demoPresentation = Presentation.EXPANDED
    private val buttons = HashMap<String, Button>()
    private var fit = IslandFit()
    private val fitSliders = ArrayList<SeekBar>()
    private var cameraSwitch: SwitchCompat? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fit = IslandPreferences.read(this)
        window.statusBarColor = BG
        setContentView(buildScreen())
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        showDemo(demoMusic(true), Presentation.EXPANDED)
    }

    override fun onResume() {
        super.onResume()
        val overlay = Settings.canDrawOverlays(this)
        buttons["overlay"]?.text = if (overlay) "✓  Island is on" else "Allow display over other apps"
        buttons["usage"]?.text = if (ForegroundWatcher.hasPermission(this)) "✓  Swipe-into-island is on" else "Allow usage access (swipe into island)"
        val a11y = IslandAccessibilityService.isEnabled(this)
        buttons["a11y"]?.text = if (a11y) "✓  Tapping at the very top works" else "Allow tapping over the status bar"
        if (overlay || a11y) startIsland()
    }

    // ---------------------------------------------------------------- live preview

    private fun showDemo(a: LiveActivity, p: Presentation) {
        demo = a
        demoPresentation = p
        preview.show(a, p)
    }

    private val previewCallbacks = object : IslandView.Callbacks {
        override fun onOpen(activity: LiveActivity) = toast("Opens ${activity.appLabel}")
        override fun onAction(activity: LiveActivity, action: IslandAction) = toast(action.label)
        override fun onMedia(activity: LiveActivity, command: String, positionMs: Long?) {
            val a = demo ?: return
            if (command == IslandBus.MEDIA_TOGGLE) showDemo(demoMusic(!a.isPlaying, a.livePositionMs()), demoPresentation)
            if (command == IslandBus.MEDIA_SEEK && positionMs != null) showDemo(demoMusic(a.isPlaying, positionMs), demoPresentation)
            if (command == IslandBus.MEDIA_NEXT || command == IslandBus.MEDIA_PREVIOUS) showDemo(demoMusic(a.isPlaying, 0L), demoPresentation)
        }
        override fun onExpandRequest() { demo?.let { showDemo(it, Presentation.EXPANDED) } }
        override fun onCollapseRequest() { demo?.let { showDemo(it, Presentation.COMPACT) } }
        override fun onDismiss(activity: LiveActivity) = onCollapseRequest()
    }

    private fun demoMusic(playing: Boolean, position: Long = 107_000L): LiveActivity {
        val art = gradientArt(NothingStyle.red, NothingStyle.surface)
        return LiveActivity(
            key = "demo-music", packageName = "com.spotify.music", mode = IslandMode.MUSIC,
            title = "Midnight City", subtitle = "M83", appLabel = "Spotify",
            appIcon = AppStyle.icon(this, "com.spotify.music"), artwork = art,
            accent = NothingStyle.foreground,
            isPlaying = playing, positionMs = position, positionUpdatedAt = SystemClock.elapsedRealtime(), durationMs = 243_000L,
        )
    }

    private fun demoMessage() = LiveActivity(
        key = "demo-msg", packageName = "com.whatsapp", mode = IslandMode.MESSAGE,
        title = "Aisha", subtitle = "Are we still on for dinner at 8? 🍝", appLabel = "WhatsApp",
        appIcon = AppStyle.icon(this, "com.whatsapp"), artwork = gradientArt(Color.rgb(255, 196, 120), Color.rgb(220, 90, 120)),
        accent = AppStyle.accent(this, "com.whatsapp"),
        actions = listOf(IslandAction("Mark as read", selfIntent())),
    )

    private fun demoCall() = LiveActivity(
        key = "demo-call", packageName = "com.whatsapp", mode = IslandMode.CALL,
        title = "Rahul", subtitle = "WhatsApp voice call", appLabel = "WhatsApp",
        appIcon = AppStyle.icon(this, "com.whatsapp"), artwork = gradientArt(Color.rgb(90, 200, 250), Color.rgb(40, 80, 200)),
        accent = AppStyle.accent(this, "com.whatsapp"),
        actions = listOf(IslandAction("Decline", selfIntent()), IslandAction("Answer", selfIntent())),
    )

    private fun demoTimer() = LiveActivity(
        key = "demo-timer", packageName = "com.google.android.deskclock", mode = IslandMode.TIMER,
        title = "Pasta", subtitle = "Timer", appLabel = "Clock",
        appIcon = AppStyle.icon(this, "com.google.android.deskclock"), artwork = null,
        accent = AppStyle.accent(this, "com.google.android.deskclock"),
        actions = listOf(IslandAction("Pause", selfIntent()), IslandAction("Cancel", selfIntent())),
        chronometerBase = System.currentTimeMillis() + 9 * 60_000L, chronometerCountDown = true,
    )

    private fun selfIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun gradientArt(c1: Int, c2: Int): Bitmap {
        val b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 256f, 256f, c1, c2, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 256f, 256f, p)
        p.shader = null
        p.color = 0x40FFFFFF
        canvas.drawCircle(184f, 76f, 58f, p)
        return b
    }

    // ---------------------------------------------------------------- screen

    private fun buildScreen(): ScrollView {
        val pad = dp(20)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, dp(20), pad, pad)
        }
        column.addView(label("( 01 )  DROID / NOTHING INSPIRED", 11f, NothingStyle.red), row(-2))
        column.addView(DotHeading(this), row(dp(64)))
        preview = IslandView(this).apply {
            anchorToCutout = false
            callbacks = previewCallbacks
            applyFit(fit.copy(offsetY = 0f))
        }
        column.addView(FrameLayout(this).apply { addView(preview, FrameLayout.LayoutParams(-1, -1)) }, row(dp(270)))
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        chips.addView(chip("Music") { showDemo(demoMusic(true), Presentation.EXPANDED) })
        chips.addView(chip("Chat") { showDemo(demoMessage(), Presentation.EXPANDED) })
        chips.addView(chip("Call") { showDemo(demoCall(), Presentation.EXPANDED) })
        chips.addView(chip("Timer") { showDemo(demoTimer(), Presentation.EXPANDED) })
        column.addView(chips, row(dp(44)).apply { topMargin = dp(14) })

        column.addView(label("( 02 )  PHONE FIT", 13f, NothingStyle.red), row(-2).apply { topMargin = dp(24) })
        column.addView(fitControl("Size", 70, 130, (fit.scale * 100).toInt(), "%") { fit = fit.copy(scale = it / 100f); updateFit() }, row(-2))
        column.addView(fitControl("Pill width", 70, 150, (fit.pillWidth * 100).toInt(), "%") { fit = fit.copy(pillWidth = it / 100f); updateFit() }, row(-2))
        column.addView(fitControl("Left / right", -180, 180, fit.offsetX.toInt(), "dp") { fit = fit.copy(offsetX = it.toFloat()); updateFit() }, row(-2))
        column.addView(fitControl("Up / down", -24, 160, fit.offsetY.toInt(), "dp") { fit = fit.copy(offsetY = it.toFloat()); updateFit() }, row(-2))
        cameraSwitch = SwitchCompat(this).apply {
            text = "Align with camera"
            setTextColor(NothingStyle.foreground)
            typeface = NothingStyle.mono
            isChecked = fit.alignCamera
            buttonTintList = ColorStateList.valueOf(NothingStyle.red)
            setOnCheckedChangeListener { _, checked -> fit = fit.copy(alignCamera = checked); updateFit() }
        }
        column.addView(cameraSwitch, row(dp(56)))
        column.addView(action("reset", "↺  Reset phone fit") {
            fit = IslandFit()
            val defaults = listOf(30, 30, 180, 24)
            fitSliders.forEachIndexed { index, slider -> slider.progress = defaults[index] }
            cameraSwitch?.isChecked = true
            updateFit()
        }, row(dp(54)))
        column.addView(label("( 03 )  CONNECTIONS", 13f, NothingStyle.red), row(-2).apply { topMargin = dp(28) })

        column.addView(action("overlay", "Allow display over other apps") {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else startIsland()
        }, row(dp(54)).apply { topMargin = dp(28) })
        column.addView(action("notifications", "Allow notification access") {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }, row(dp(54)).apply { topMargin = dp(10) })
        column.addView(action("a11y", "Allow tapping over the status bar") {
            toast("Find Dynamic Droid under Installed apps. If it's greyed out: App info → ⋮ → Allow restricted settings.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, row(dp(54)).apply { topMargin = dp(10) })
        column.addView(action("usage", "Allow usage access (swipe into island)") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }, row(dp(54)).apply { topMargin = dp(10) })

        return ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(column)
        }
    }

    private fun chip(text: String, click: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        setTextColor(NothingStyle.foreground)
        typeface = NothingStyle.mono
        background = NothingStyle.panel()
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = dp(3); marginEnd = dp(3)
        }
    }

    private fun action(key: String, text: String, click: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        setTextColor(NothingStyle.foreground)
        typeface = NothingStyle.mono
        background = NothingStyle.panel()
        setOnClickListener { click() }
        buttons[key] = this
    }

    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = NothingStyle.mono
    }

    private fun fitControl(name: String, min: Int, max: Int, value: Int, unit: String, change: (Int) -> Unit): LinearLayout {
        val title = label("$name  ·  $value$unit", 13f, NothingStyle.foreground)
        val slider = SeekBar(this).apply {
            this.max = max - min
            progress = value - min
            contentDescription = name
            progressTintList = ColorStateList.valueOf(NothingStyle.red)
            thumbTintList = ColorStateList.valueOf(NothingStyle.foreground)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    title.text = "$name  ·  ${progress + min}$unit"
                    if (fromUser) change(progress + min)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        fitSliders.add(slider)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
            addView(title, row(-2))
            addView(slider, row(dp(48)))
        }
    }

    private fun updateFit() {
        IslandPreferences.save(this, fit)
        preview.applyFit(fit.copy(offsetY = 0f))
        if (Settings.canDrawOverlays(this) || IslandAccessibilityService.isEnabled(this)) {
            ContextCompat.startForegroundService(this, Intent(this, IslandOverlayService::class.java).setAction(IslandOverlayService.ACTION_FIT))
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun row(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun startIsland() {
        ContextCompat.startForegroundService(this, Intent(this, IslandOverlayService::class.java))
    }

    companion object {
        private val BG = NothingStyle.background
        private val SURFACE = NothingStyle.surface
        private val MUTED = NothingStyle.muted
    }
}
