package com.dynamicdroid.app

import kotlin.math.abs

/** Run with kotlinc ClockTimeline.kt Spring.kt IslandTheme.kt ClockMotionTest.kt -include-runtime -d tests.jar. */
fun main() {
    val fit = IslandFit(scale = 2f, pillWidth = 2f, offsetX = -900f, offsetY = 900f).safe()
    check(fit.scale == 1.3f && fit.pillWidth == 1.5f)
    check(fit.offsetX == -180f && fit.offsetY == 160f)
    check(IslandFit().safe() == IslandFit())
    // Wallpaper theme colours must be opaque so accents read on the black island.
    check(IslandTheme(true, 0x00123456, 0x00ABCDEF).safe().accent == 0xFF123456.toInt())
    check(IslandTheme(true, 0x00123456, 0x00ABCDEF).safe().secondary == 0xFFABCDEF.toInt())
    check(IslandTheme().safe() == IslandTheme())
    // Compact and expanded islands cannot disappear past either edge on narrow phones.
    check(IslandFit.center(360f, 214f, 0f, 4f) == 111f)
    check(IslandFit.center(360f, 214f, 500f, 4f) == 249f)
    check(IslandFit.center(360f, 344f, 40f, 4f) == 176f)
    check(IslandFit.center(360f, 400f, 500f, 4f) == 180f)
    check(ClockTimeline.parseRemaining("Time left 9:00") == 540_000L)
    check(ClockTimeline.parseRemaining("1:02:03 remaining") == 3_723_000L)
    check(ClockTimeline.parseRemaining("Timer paused") == null)
    check(ClockTimeline.parseRemaining("1:99:00") == null)
    check(ClockTimeline.display(60_000L, true) == "1:00")
    check(ClockTimeline.display(59_999L, true) == "1:00")
    check(ClockTimeline.display(59_000L, true) == "0:59")
    check(ClockTimeline.display(1L, true) == "0:01")
    check(ClockTimeline.display(0L, true) == "0:00")
    check(ClockTimeline.remaining(100_000L, 40_000L, true) == 60_000L)
    check(ClockTimeline.remaining(100_000L, 101_000L, true) == 0L)
    check(ClockTimeline.display(1_999L, false) == "0:01")
    check(ClockTimeline.nextTick(59_250L, true) == 258L)
    check(ClockTimeline.nextTick(59_000L, true) == 1008L)
    val spring = Spring(34f, 190f, 0.8f, 0.5f)
    spring.target = 138f
    repeat(12) { spring.step(1f / 60f) }
    val before = spring.value
    spring.target = 34f
    check(spring.value == before) // Interrupting must not snap position.
    spring.step(4f) // A stalled frame stays bounded.
    check(spring.value.isFinite() && spring.value in 0f..180f)
    repeat(240) { spring.step(1f / 120f) }
    check(spring.isAtRest && abs(spring.value - 34f) < 0.5f)
    println("Phone fit, countdown and interrupted spring regressions passed")
}