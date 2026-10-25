package com.dynamicdroid.app

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette

/** App icons, names and brand colours so each island matches the app it came from. */
object AppStyle {
    const val DEFAULT_ACCENT = 0xFF0A84FF.toInt()
    const val GREEN = 0xFF30D158.toInt()
    const val RED = 0xFFFF453A.toInt()

    private val brand: Map<String, Int> = mapOf(
        "com.whatsapp" to 0xFF25D366,
        "com.whatsapp.w4b" to 0xFF25D366,
        "org.telegram.messenger" to 0xFF2AABEE,
        "org.telegram.messenger.web" to 0xFF2AABEE,
        "org.thoughtcrime.securesms" to 0xFF3A76F0,
        "com.instagram.android" to 0xFFE1306C,
        "com.facebook.orca" to 0xFF0084FF,
        "com.facebook.katana" to 0xFF1877F2,
        "com.snapchat.android" to 0xFFFFFC00,
        "com.discord" to 0xFF5865F2,
        "com.Slack" to 0xFFE01E5A,
        "com.twitter.android" to 0xFF1D9BF0,
        "com.linkedin.android" to 0xFF0A66C2,
        "com.google.android.gm" to 0xFFEA4335,
        "com.google.android.apps.messaging" to 0xFF1A73E8,
        "com.samsung.android.messaging" to 0xFF3E91FF,
        "com.google.android.dialer" to 0xFF34C759,
        "com.samsung.android.dialer" to 0xFF34C759,
        "com.spotify.music" to 0xFF1DB954,
        "com.google.android.youtube" to 0xFFFF0033,
        "com.google.android.apps.youtube.music" to 0xFFFF0033,
        "com.apple.android.music" to 0xFFFA2D48,
        "com.soundcloud.android" to 0xFFFF5500,
        "deezer.android.app" to 0xFFA238FF,
        "com.amazon.mp3" to 0xFF25D1DA,
        "com.netflix.mediaclient" to 0xFFE50914,
        "com.google.android.apps.maps" to 0xFF34A853,
        "com.waze" to 0xFF33CCFF,
        "com.ubercab" to 0xFFFFFFFF,
        "com.google.android.deskclock" to 0xFFFF9F0A,
        "com.sec.android.app.clockpackage" to 0xFFFF9F0A,
    ).mapValues { it.value.toInt() }

    private val icons = HashMap<String, Bitmap?>()
    private val labels = HashMap<String, String>()
    private val accents = HashMap<String, Int>()

    fun icon(context: Context, pkg: String): Bitmap? = icons.getOrPut(pkg) {
        runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(144, 144) }.getOrNull()
    }

    fun label(context: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() })
    }

    fun accent(context: Context, pkg: String): Int = accents.getOrPut(pkg) {
        brand[pkg]?.let(::readable) ?: icon(context, pkg)?.let(::fromBitmap) ?: DEFAULT_ACCENT
    }

    /** Most vivid colour of an image, lifted so it reads well on the black island. */
    fun fromBitmap(bitmap: Bitmap): Int? = runCatching {
        val safe = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val p = Palette.from(safe).maximumColorCount(16).generate()
        val swatch = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.mutedSwatch ?: p.dominantSwatch
        swatch?.rgb?.let(::readable)
    }.getOrNull()

    fun readable(color: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        if (hsl[2] < 0.5f) hsl[2] = 0.58f
        if (hsl[2] > 0.8f) hsl[2] = 0.74f
        return ColorUtils.HSLToColor(hsl)
    }
}
