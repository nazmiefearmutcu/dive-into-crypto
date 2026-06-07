package com.diveintocrypto.android.platform

import kotlinx.atomicfu.atomic
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Multiplatform replacement for `System.currentTimeMillis()`. */
fun nowMillis(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

private val idCounter = atomic(0L)

/**
 * Monotonic unique id (replaces `java.util.UUID.randomUUID()` for the network
 * log). Uniqueness matters: these ids key a LazyColumn, and a duplicate key
 * crashes Compose (the bug fixed in commit 5d7bfb4).
 */
fun randomId(): String = "${nowMillis()}-${idCounter.incrementAndGet()}"

/**
 * Multiplatform replacement for `SimpleDateFormat`. Supports the tokens the app
 * actually uses: `yyyy`, `MM`, `dd`, `HH`, `mm`, `ss`. Formats in the device's
 * local time zone, matching `Locale.getDefault()` behaviour of the original.
 */
fun formatTime(ms: Long, pattern: String): String {
    val dt = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
    fun p2(n: Int) = n.toString().padStart(2, '0')
    return pattern
        .replace("yyyy", dt.year.toString().padStart(4, '0'))
        .replace("MM", p2(dt.monthNumber))
        .replace("dd", p2(dt.dayOfMonth))
        .replace("HH", p2(dt.hour))
        .replace("mm", p2(dt.minute))
        .replace("ss", p2(dt.second))
}
