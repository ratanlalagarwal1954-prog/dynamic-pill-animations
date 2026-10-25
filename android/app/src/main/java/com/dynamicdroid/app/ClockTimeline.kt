package com.dynamicdroid.app

/** Platform-independent clock rules; notification timestamps are converted once by LiveActivity. */
object ClockTimeline {
    fun remaining(base: Long, now: Long, countDown: Boolean): Long =
        (if (countDown) base - now else now - base).coerceAtLeast(0L)

    fun display(ms: Long, countDown: Boolean): String {
        // A timer with a fraction of a second left must not announce zero early.
        val seconds = ms.coerceAtLeast(0L) / 1000L +
            if (countDown && ms > 0L && ms % 1000L != 0L) 1L else 0L
        val h = seconds / 3600L
        val m = seconds % 3600L / 60L
        val s = (seconds % 60L).toString().padStart(2, '0')
        return if (h > 0L) "$h:${m.toString().padStart(2, '0')}:$s" else "$m:$s"
    }

    /** Only colon-delimited durations from already-classified timer notifications. */
    private val duration = Regex("(?<![\\d:])(?:(\\d{1,3}):)?(\\d{1,3}):([0-5]\\d)(?![\\d:])")

    fun parseRemaining(text: String): Long? {
        val match = duration.find(text) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        if (match.groupValues[1].isNotEmpty() && minutes > 59L) return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        return (hours * 3600L + minutes * 60L + seconds) * 1000L
    }

    fun nextTick(ms: Long, countDown: Boolean): Long {
        val remainder = ms.coerceAtLeast(0L) % 1000L
        return if (countDown) (if (remainder == 0L) 1000L else remainder) + 8L
        else 1000L - remainder + 8L
    }
}