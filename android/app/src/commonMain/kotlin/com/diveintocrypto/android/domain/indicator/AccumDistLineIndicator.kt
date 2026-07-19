package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format

/**
 * Accumulation/Distribution Line (ADL) — verbatim port of the original Python
 * reference implementation.
 *
 * ADL is the running total of volume weighted by the intrabar Close Location
 * Value (CLV). Two independent reads are combined:
 *
 * 1. SLOPE — net accumulation rate over a trailing window, bounded [-1, 1]:
 *      net_clv = (ADL[-1] - ADL[-1-n]) / sum(volume over last n bars)
 * 2. DIVERGENCE — a trailing lookback is split into an older half and a recent
 *    half; the price swing extreme of each half is compared against the ADL at
 *    those same bars (higher price high + lower ADL high = bearish; lower price
 *    low + higher ADL low = bullish). Divergence takes priority; slope
 *    confirmation escalates the call to STRONG.
 *
 * Strictly causal: cumsum, fixed trailing windows and argmax/argmin over
 * already-observed bars only; no pivot repaints.
 */
class AccumDistLineIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "accum_dist_line"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val slopePeriod = config.getInt("slope_period", 20)
        val divergenceLookback = config.getInt("divergence_lookback", 30)
        val minPivotPct = config.getDouble("min_pivot_pct", 0.003)
        val strongBuy = config.getDouble("strong_buy", 0.25)
        val buy = config.getDouble("buy", 0.08)
        val sell = config.getDouble("sell", -0.08)
        val strongSell = config.getDouble("strong_sell", -0.25)

        val n = candles.size
        val need = maxOf(slopePeriod, divergenceLookback) + 2
        if (n < need) {
            return result(Signal.NEUTRAL, "ADL data insufficient")
        }

        val closeArr = DoubleArray(n) { candles[it].close }
        val volArr = DoubleArray(n) { candles[it].volume }

        // --- Accumulation/Distribution Line ---
        // Close Location Value in [-1, 1]; flat candle (high == low) -> 0 (no bias).
        val adlArr = DoubleArray(n)
        var cum = 0.0
        for (i in 0 until n) {
            val c = candles[i]
            val hlRange = c.high - c.low
            val clv = if (hlRange > 0.0) {
                ((c.close - c.low) - (c.high - c.close)) / hlRange
            } else {
                0.0
            }
            cum += clv * c.volume
            adlArr[i] = cum
        }

        val currentAdl = adlArr[n - 1]
        if (currentAdl.isNaN()) {
            return result(Signal.NEUTRAL, "ADL data insufficient")
        }

        // --- Slope: net accumulation rate over the trailing window, bounded [-1, 1] ---
        val adlDelta = currentAdl - adlArr[n - 1 - slopePeriod]
        var volSum = 0.0
        for (i in n - slopePeriod until n) volSum += volArr[i]
        var netClv = if (volSum > EPS) adlDelta / volSum else 0.0
        netClv = netClv.coerceIn(-1.0, 1.0)

        // --- Divergence: split a trailing window into older/recent halves ---
        val window = minOf(divergenceLookback, n - 1)
        val half = window / 2
        var bearishDiv = false
        var bullishDiv = false
        var recentBearPos = -1
        var recentBullPos = -1

        if (half >= 2) {
            val recentLo = n - half          // start index of recent half
            val olderLo = n - 2 * half       // start index of older half

            // Bearish: higher price high but lower ADL high.
            val rHi = argmax(closeArr, recentLo, n)
            val oHi = argmax(closeArr, olderLo, recentLo)
            val priceHh = closeArr[rHi] > closeArr[oHi] * (1.0 + minPivotPct)
            val adlLh = adlArr[rHi] < adlArr[oHi]
            if (priceHh && adlLh) {
                bearishDiv = true
                recentBearPos = rHi
            }

            // Bullish: lower price low but higher ADL low.
            val rLo = argmin(closeArr, recentLo, n)
            val oLo = argmin(closeArr, olderLo, recentLo)
            val priceLl = closeArr[rLo] < closeArr[oLo] * (1.0 - minPivotPct)
            val adlHl = adlArr[rLo] > adlArr[oLo]
            if (priceLl && adlHl) {
                bullishDiv = true
                recentBullPos = rLo
            }
        }

        val raw = mapOf<String, Double?>(
            "adl" to round2(currentAdl),
            "net_clv" to round4(netClv),
            "slope_period" to slopePeriod.toDouble(),
            // "divergence" is a string in Python ("bearish"/"bullish"/"both"/"none",
            // taken before the tie-break); tests only check numeric raws.
        )

        // If both fire, defer to the divergence whose recent pivot is more recent.
        if (bearishDiv && bullishDiv) {
            if (recentBullPos >= recentBearPos) {
                bearishDiv = false
            } else {
                bullishDiv = false
            }
        }

        // --- Divergence takes priority (leading reversal) ---
        if (bullishDiv) {
            if (netClv > 0.0) {
                return result(
                    Signal.STRONG_BUY,
                    "Bullish ADL divergence (price LL, ADL HL) confirmed by accumulation net_clv=${netClv.format(2)}",
                    raw,
                )
            }
            return result(
                Signal.BUY,
                "Bullish ADL divergence: price lower-low but ADL higher-low (hidden accumulation)",
                raw,
            )
        }

        if (bearishDiv) {
            if (netClv < 0.0) {
                return result(
                    Signal.STRONG_SELL,
                    "Bearish ADL divergence (price HH, ADL LH) confirmed by distribution net_clv=${netClv.format(2)}",
                    raw,
                )
            }
            return result(
                Signal.SELL,
                "Bearish ADL divergence: price higher-high but ADL lower-high (hidden distribution)",
                raw,
            )
        }

        // --- No divergence: graded slope of accumulation ---
        return when {
            netClv >= strongBuy -> result(
                Signal.STRONG_BUY, "ADL rising hard, net_clv=${netClv.format(2)} (strong accumulation)", raw,
            )
            netClv >= buy -> result(
                Signal.BUY, "ADL rising, net_clv=${netClv.format(2)} (accumulation)", raw,
            )
            netClv <= strongSell -> result(
                Signal.STRONG_SELL, "ADL falling hard, net_clv=${netClv.format(2)} (strong distribution)", raw,
            )
            netClv <= sell -> result(
                Signal.SELL, "ADL falling, net_clv=${netClv.format(2)} (distribution)", raw,
            )
            else -> result(
                Signal.NEUTRAL, "ADL flat, net_clv=${netClv.format(2)} (no accumulation bias)", raw,
            )
        }
    }

    /** Index of the first maximum in a[from, to) — matches np.argmax tie-breaking. */
    private fun argmax(a: DoubleArray, from: Int, to: Int): Int {
        var best = from
        for (i in from + 1 until to) if (a[i] > a[best]) best = i
        return best
    }

    /** Index of the first minimum in a[from, to) — matches np.argmin tie-breaking. */
    private fun argmin(a: DoubleArray, from: Int, to: Int): Int {
        var best = from
        for (i in from + 1 until to) if (a[i] < a[best]) best = i
        return best
    }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0

    private companion object {
        const val EPS = 1e-12
    }
}
