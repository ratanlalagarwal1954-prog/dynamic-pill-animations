package com.dynamicdroid.app

import kotlin.math.abs

/** Run with kotlinc ClockTimeline.kt Spring.kt ClockMotionTest.kt -include-runtime -d tests.jar. */
fun main() {
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
    println("Clock countdown and interrupted spring regressions passed")
}