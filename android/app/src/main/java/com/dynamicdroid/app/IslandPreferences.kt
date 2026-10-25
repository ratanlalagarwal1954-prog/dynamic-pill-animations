package com.dynamicdroid.app

import android.content.Context

/** Device-local calibration, shared by the settings screen and both overlay hosts. */
object IslandPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("island_fit", Context.MODE_PRIVATE)

    fun read(context: Context): IslandFit {
        val p = prefs(context)
        return IslandFit(p.getFloat("scale", 1f), p.getFloat("width", 1f),
            p.getFloat("x", 0f), p.getFloat("y", 0f), p.getBoolean("camera", true)).safe()
    }

    fun save(context: Context, fit: IslandFit) {
        val f = fit.safe()
        prefs(context).edit().putFloat("scale", f.scale).putFloat("width", f.pillWidth)
            .putFloat("x", f.offsetX).putFloat("y", f.offsetY).putBoolean("camera", f.alignCamera).apply()
    }

    fun readTheme(context: Context): IslandTheme {
        val p = prefs(context)
        return IslandTheme(
            p.getBoolean("theme_on", false),
            p.getInt("theme_accent", 0xFFEB3030.toInt()),
            p.getInt("theme_secondary", 0xFF9D9D9D.toInt()),
        ).safe()
    }

    fun saveTheme(context: Context, theme: IslandTheme) {
        val t = theme.safe()
        prefs(context).edit().putBoolean("theme_on", t.enabled)
            .putInt("theme_accent", t.accent).putInt("theme_secondary", t.secondary).apply()
    }
}