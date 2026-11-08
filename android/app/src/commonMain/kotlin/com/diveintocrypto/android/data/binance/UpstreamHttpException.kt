package com.diveintocrypto.android.data.binance

/**
 * A non-2xx answer from a market-data REST endpoint, carrying the HTTP status so
 * the UI can tell a *regional block* apart from an ordinary outage.
 *
 * Binance answers HTTP 451 ("Unavailable For Legal Reasons") to requests from
 * restricted regions (e.g. the United States). That is the ONLY region block:
 * retrying cannot succeed, so callers must stop and surface
 * [DiveStrings.errRegionBlockedBody] instead of a raw status string or an
 * endless spinner.
 *
 * HTTP 403 is a separate, non-transient "forbidden" state ([forbidden]): the data
 * source refused the request, which may or may not be regional. It is not a
 * region block, so the UI offers a manual retry instead of claiming a block.
 *
 * Extends [IllegalStateException] so existing `catch (e: IllegalStateException)`
 * / `runCatching` paths keep working unchanged.
 */
class UpstreamHttpException(
    val endpoint: String,
    val status: Int,
) : IllegalStateException("$endpoint HTTP $status") {
    /** True when retrying is pointless because the venue refuses this region (HTTP 451 only). */
    val regionBlocked: Boolean get() = status == 451

    /** True for HTTP 403: refused, not a confirmed region block. Not retried automatically. */
    val forbidden: Boolean get() = status == 403

    /** True for 429/418 (rate limit / IP ban) and 5xx — worth a backed-off retry. */
    val transient: Boolean get() = status == 429 || status == 418 || status >= 500
}

/** The first [UpstreamHttpException] in the cause chain (bounded walk), or null. */
fun Throwable.upstreamHttpException(): UpstreamHttpException? {
    var t: Throwable? = this
    var depth = 0
    while (t != null && depth < 8) {
        if (t is UpstreamHttpException) return t
        t = t.cause
        depth++
    }
    return null
}

/** Walks the cause chain: true if any [UpstreamHttpException] in it is a regional block (HTTP 451). */
fun Throwable.isRegionBlocked(): Boolean {
    var t: Throwable? = this
    var depth = 0
    while (t != null && depth < 8) {
        if (t is UpstreamHttpException && t.regionBlocked) return true
        t = t.cause
        depth++
    }
    return false
}

/** Walks the cause chain: true if any [UpstreamHttpException] in it is a refusal (HTTP 403). */
fun Throwable.isForbidden(): Boolean {
    var t: Throwable? = this
    var depth = 0
    while (t != null && depth < 8) {
        if (t is UpstreamHttpException && t.forbidden) return true
        t = t.cause
        depth++
    }
    return false
}
