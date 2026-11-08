package com.diveintocrypto.android.ui.performance

import com.diveintocrypto.android.data.binance.UpstreamHttpException
import com.diveintocrypto.android.data.binance.isRegionBlocked
import com.diveintocrypto.android.ui.i18n.DiveStrings

/**
 * Why a market-data load failed. ViewModels store this classification instead of a
 * raw exception string, and screens render it through [DiveStrings] so the text
 * follows the selected language and never shows "HTTP 451" or an endless spinner.
 */
sealed interface MarketDataFailure {
    /** Binance refuses this region (HTTP 451 only). Retrying cannot succeed. */
    object RegionBlocked : MarketDataFailure

    /** The data source refused the request (HTTP 403, e.g. a WAF limit). Not a region block: retry is offered. */
    object Forbidden : MarketDataFailure

    /** The upstream answered with a non-2xx status (rate limit, outage, ...). */
    data class Upstream(val status: Int) : MarketDataFailure

    /** No connectivity, timeout, or any other failure. */
    object Network : MarketDataFailure

    /** Localized, user-facing text for this failure. */
    fun text(strings: DiveStrings): String = when (this) {
        is RegionBlocked -> "${strings.errRegionBlockedTitle}\n${strings.errRegionBlockedBody}"
        is Forbidden -> "${strings.errForbiddenTitle}\n${strings.errForbiddenBody}"
        is Upstream -> strings.errUpstreamGeneric
        is Network -> strings.errNetwork
    }
}

private val HTTP_STATUS_IN_MESSAGE = Regex("""HTTP (\d{3})""")

private fun UpstreamHttpException.toFailure(): MarketDataFailure = when {
    regionBlocked -> MarketDataFailure.RegionBlocked
    forbidden -> MarketDataFailure.Forbidden
    else -> MarketDataFailure.Upstream(status)
}

/**
 * Classifies a load failure. Prefers the typed [UpstreamHttpException]; falls back to an
 * "HTTP <status>" marker in the message for clients that still throw a plain
 * IllegalStateException. Callers must rethrow CancellationException before calling this.
 */
fun Throwable.toMarketDataFailure(): MarketDataFailure {
    if (isRegionBlocked()) return MarketDataFailure.RegionBlocked
    var cause: Throwable? = this
    var depth = 0
    while (cause != null && depth < 8) {
        if (cause is UpstreamHttpException) return cause.toFailure()
        cause = cause.cause
        depth++
    }
    val status = HTTP_STATUS_IN_MESSAGE.find(message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    return when {
        status == null -> MarketDataFailure.Network
        status == 451 -> MarketDataFailure.RegionBlocked
        status == 403 -> MarketDataFailure.Forbidden
        else -> MarketDataFailure.Upstream(status)
    }
}
