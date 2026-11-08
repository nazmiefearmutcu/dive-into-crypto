package com.diveintocrypto.android.ui.common

import com.diveintocrypto.android.data.binance.UpstreamHttpException
import com.diveintocrypto.android.data.binance.isForbidden
import com.diveintocrypto.android.data.binance.isRegionBlocked
import com.diveintocrypto.android.ui.i18n.DiveStrings

/**
 * Failure helpers shared by the Scanner and Signals ViewModels (PURE — no Compose, no I/O).
 *
 * Rule: a user never sees a raw exception message or an HTTP status code. Failures are
 * mapped to the localized catalog: a region block (HTTP 451) gets its own text, a refusal
 * (HTTP 403) gets a "forbidden" text that still offers a retry, and rate limits (429/418)
 * and 5xx answers get worded messages. Region blocks and rate limits are flagged so callers
 * stop retrying instead of looping. The numeric status is for logs only.
 */

/** HTTP status of the first [UpstreamHttpException] in this cause chain, or null. */
fun Throwable.upstreamStatus(): Int? {
    var cur: Throwable? = this
    var depth = 0
    while (cur != null && depth < 8) {
        if (cur is UpstreamHttpException) return cur.status
        cur = cur.cause
        depth++
    }
    return null
}

/** 429 / 418: the venue is throttling or banned this IP. Continuous loops must stop. */
fun Throwable.isRateLimited(): Boolean = upstreamStatus().let { it == 429 || it == 418 }

/** Localized, user-facing text for a data-layer failure. Never the raw exception message or status. */
fun Throwable.userFacingText(s: DiveStrings): String {
    if (isRegionBlocked()) return s.errRegionBlockedBody
    if (isForbidden()) return s.errForbiddenBody
    if (isRateLimited()) return s.scanRateLimited
    return if (upstreamStatus() != null) s.errUpstreamGeneric else s.errNetwork
}
