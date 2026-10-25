package com.dynamicdroid.app

import kotlin.math.max

/** Device-independent phone fit values, also used by the regression checks. */
data class IslandFit(
    val scale: Float = 1f,
    val pillWidth: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val alignCamera: Boolean = true,
) {
    fun safe() = copy(
        scale = scale.coerceIn(0.7f, 1.3f),
        pillWidth = pillWidth.coerceIn(0.7f, 1.5f),
        offsetX = offsetX.coerceIn(-180f, 180f),
        offsetY = offsetY.coerceIn(-24f, 160f),
    )

    companion object {
        fun center(screenWidth: Float, islandWidth: Float, desired: Float, margin: Float): Float {
            val half = islandWidth / 2f + margin
            return desired.coerceIn(half.coerceAtMost(screenWidth / 2f), max(screenWidth / 2f, screenWidth - half))
        }
    }
}