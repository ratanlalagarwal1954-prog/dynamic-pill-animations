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
}