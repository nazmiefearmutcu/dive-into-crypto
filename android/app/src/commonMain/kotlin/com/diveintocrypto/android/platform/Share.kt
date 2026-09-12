package com.diveintocrypto.android.platform

import androidx.compose.runtime.Composable

/**
 * Platform-neutral PAYLAŞ payload for one scanner verdict (rendered as a PNG
 * share card on Android via [com.diveintocrypto.android.share.VerdictCardRenderer]).
 */
data class ShareVerdictPayload(
    val symbol: String,
    /** Signal name (BUY / SELL / STRONG_BUY / STRONG_SELL / NEUTRAL). */
    val verdict: String,
    /** 0..100. */
    val confidence: Int,
    /** (timeframe, signal name) pairs in display order; empty = no heat strip. */
    val perTf: List<Pair<String, String>> = emptyList(),
    /** Wall-clock ms of the verdict. */
    val timestampMs: Long,
)

/**
 * Platform-neutral share hook. Android wires the real system share sheet
 * (ShareVerdictRow → PNG → ACTION_SEND) with the bitmap render + disk write on
 * Dispatchers.IO (never on the UI thread); returns true when the async share
 * job was dispatched (the legacy synchronous success signal is gone — failures
 * are contained inside the job, never surfaced to the UI lane).
 */
@Composable
expect fun rememberShareVerdict(): (ShareVerdictPayload) -> Boolean
