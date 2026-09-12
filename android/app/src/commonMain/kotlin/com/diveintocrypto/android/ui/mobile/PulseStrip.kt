package com.diveintocrypto.android.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.platform.format
import com.diveintocrypto.android.platform.nowMillis
import com.diveintocrypto.android.ui.common.UiLabels
import com.diveintocrypto.android.ui.theme.DiveColors
import com.diveintocrypto.android.ui.theme.DiveFonts
import kotlinx.coroutines.delay

/**
 * PULSE STRIP — one compact hairline row under the [MobileTopBar]:
 *   - active symbol + LIVE last price (all-market mini-ticker engine)
 *   - 24h % chip (REST ticker/24hr refresh; "—" until the first refresh)
 *   - funding countdown for the active symbol (premiumIndex polled every
 *     60s via [com.diveintocrypto.android.engine.MarketDataEngine.premiumIndex],
 *     ticking locally once per second against the fetched settlement time)
 *
 * HONESTY: every cell degrades to "—" when the data isn't there — the strip
 * never invents a price, a percent or a clock.
 */
private const val PREMIUM_POLL_MS = 60_000L

@Composable
fun PulseStrip(container: AppContainer, modifier: Modifier = Modifier) {
    val symbol by container.activeSymbol.collectAsStateWithLifecycle()
    val tickers by container.liveTickerEngine.tickers.collectAsStateWithLifecycle()

    // Idempotent — one shared socket for the whole app.
    LaunchedEffect(Unit) { container.liveTickerEngine.ensureStarted() }

    // Next-funding wall-clock for the active symbol (null = unknown → "—").
    var fundingAtMs by remember(symbol) { mutableStateOf<Long?>(null) }
    LaunchedEffect(symbol) {
        while (true) {
            val dto = runCatching { container.repository.premiumIndex(symbol) }.getOrNull()
            fundingAtMs = dto?.nextFundingTime?.takeIf { it > 0 }
            delay(PREMIUM_POLL_MS)
        }
    }

    // 1s local tick so the countdown actually ticks (tabular numerics).
    var nowMs by remember { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowMs = nowMillis()
        }
    }

    val ticker = tickers[symbol]
    val price = ticker?.price
    val changePct = ticker?.changePercent
    val secondsToFunding = fundingAtMs?.let { (it - nowMs) / 1000 }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(DiveColors.BgCard)
            .border(1.dp, DiveColors.Border, RoundedCornerShape(0.dp))
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = symbol,
            color = DiveColors.Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            fontFamily = DiveFonts.Mono,
        )
        Text(
            text = price?.let { "${'$'}${it.format(ChartDecimalsFor(it))}" } ?: "—",
            color = if (price != null) DiveColors.Text else DiveColors.TextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = DiveFonts.Mono,
        )
        Text(
            text = when {
                changePct == null -> "—"
                else -> changePct.format(2, plus = true) + "%"
            },
            color = when {
                changePct == null -> DiveColors.TextDim
                changePct > 0 -> DiveColors.Green
                changePct < 0 -> DiveColors.Red
                else -> DiveColors.TextMuted
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = DiveFonts.Mono,
            modifier = Modifier
                .background(
                    when {
                        changePct == null -> DiveColors.BgCardHover
                        changePct > 0 -> DiveColors.GreenTint15
                        changePct < 0 -> DiveColors.RedTint15
                        else -> DiveColors.BgCardHover
                    },
                )
                .border(
                    1.dp,
                    when {
                        changePct == null -> DiveColors.Border
                        changePct > 0 -> DiveColors.Green.copy(alpha = 0.35f)
                        changePct < 0 -> DiveColors.Red.copy(alpha = 0.35f)
                        else -> DiveColors.Border
                    },
                    RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = com.diveintocrypto.android.ui.i18n.LocalDiveStrings.current.funding,
            color = DiveColors.TextDim,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            fontFamily = DiveFonts.body,
        )
        Text(
            text = UiLabels.countdownLabel(secondsToFunding),
            color = if (secondsToFunding != null && secondsToFunding >= 0) DiveColors.Text else DiveColors.TextDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = DiveFonts.Mono,
        )
    }
}

/** Magnitude-adaptive display decimals (small helper, mirrors the chart axis rule). */
private fun ChartDecimalsFor(price: Double): Int = when {
    price >= 1000.0 -> 2
    price >= 1.0 -> 3
    else -> 5
}
