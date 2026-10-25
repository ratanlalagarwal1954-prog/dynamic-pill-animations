package com.dynamicdroid.app

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** Small critically-tunable spring, stepped by IslandView every frame (iOS-style motion). */
class Spring(
    initial: Float,
    var stiffness: Float,
    var dampingRatio: Float,
    private val threshold: Float,
) {
    var value = initial
        private set
    var target = initial
    private var velocity = 0f

    val isAtRest: Boolean
        get() = abs(target - value) < threshold && abs(velocity) < threshold * 10f

    fun snap(v: Float) {
        value = v
        target = v
        velocity = 0f
    }

    fun step(dtSeconds: Float) {
        if (isAtRest) {
            value = target
            velocity = 0f
            return
        }
        var remaining = dtSeconds.coerceIn(0f, 0.064f)
        val damping = 2f * dampingRatio * sqrt(stiffness)
        while (remaining > 0f) {
            val h = min(remaining, 1f / 240f)
            val accel = -stiffness * (value - target) - damping * velocity
            velocity += accel * h
            value += velocity * h
            remaining -= h
        }
        if (isAtRest) {
            value = target
            velocity = 0f
        }
    }
}
