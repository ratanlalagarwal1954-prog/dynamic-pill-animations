package com.dynamicdroid.app

/**
 * Wallpaper-derived colours for the island. Pure data (no Android imports) so the
 * regression checks can exercise it on the JVM.
 */
data class IslandTheme(
    val enabled: Boolean = false,
    val accent: Int = 0xFFEB3030.toInt(),
    val secondary: Int = 0xFF9D9D9D.toInt(),
) {
    /** Colours must be opaque; a translucent accent would wash out on the black pill. */
    fun safe() = copy(
        accent = accent or 0xFF000000.toInt(),
        secondary = secondary or 0xFF000000.toInt(),
    )
}
