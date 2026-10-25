package com.dynamicdroid.app

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette

/**
 * Pulls the main colours out of the user's wallpaper (or a picked image) so the island
 * can tint its accents to match. Reading the current wallpaper needs no permission.
 */
object WallpaperTheme {

    /** System wallpaper: fast WallpaperColors on API 27+, palette of the drawable otherwise. */
    fun fromSystem(context: Context): IslandTheme? {
        val wm = WallpaperManager.getInstance(context) ?: return null
        if (Build.VERSION.SDK_INT >= 27) {
            runCatching { wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull()?.let { colors ->
                val primary = colors.primaryColor?.toArgb()
                if (primary != null) {
                    val secondary = colors.secondaryColor?.toArgb() ?: colors.tertiaryColor?.toArgb() ?: primary
                    return IslandTheme(true, AppStyle.readable(primary), AppStyle.readable(secondary))
                }
            }
        }
        val bmp = runCatching { wm.drawable?.toBitmap(192, 192) }.getOrNull() ?: return null
        return fromBitmap(bmp)
    }

    /** Any image (e.g. picked from the gallery): vivid colour for accents, muted for support. */
    fun fromBitmap(bitmap: Bitmap): IslandTheme? {
        val safe = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val palette = runCatching { Palette.from(safe).maximumColorCount(16).generate() }.getOrNull() ?: return null
        val accent = (palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch)?.rgb
            ?: return null
        val secondary = (palette.mutedSwatch ?: palette.lightMutedSwatch ?: palette.dominantSwatch)?.rgb
            ?: accent
        return IslandTheme(true, AppStyle.readable(accent), AppStyle.readable(secondary))
    }
}
