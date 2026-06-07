package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal

/**
 * Ichimoku Cloud — verbatim port of
 * the original Python reference implementation.
 *
 * Note pandas .shift(period) semantics: senkou_a/senkou_b are shifted forward
 * by kijun_period candles. `iloc[-1]` after shift returns the value that was
 * at index `n-1-kijun_period` in the unshifted series.
 */
class IchimokuIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "ichimoku"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val tenkanPeriod = config.getInt("tenkan_period", 9)
        val kijunPeriod = config.getInt("kijun_period", 26)
        val senkouBPeriod = config.getInt("senkou_b_period", 52)

        val n = candles.size
        if (n < senkouBPeriod + kijunPeriod + 2) {
            return result(Signal.NEUTRAL, "Ichimoku data insufficient (need more candles)")
        }

        val highs = candles.map { it.high }
        val lows = candles.map { it.low }
        val closes = candles.map { it.close }

        val highTenkan = Series.rollingMax(highs, tenkanPeriod)
        val lowTenkan = Series.rollingMin(lows, tenkanPeriod)
        val highKijun = Series.rollingMax(highs, kijunPeriod)
        val lowKijun = Series.rollingMin(lows, kijunPeriod)
        val highSenkouB = Series.rollingMax(highs, senkouBPeriod)
        val lowSenkouB = Series.rollingMin(lows, senkouBPeriod)

        // unshifted base series
        val tenkanBase: List<Double?> = highTenkan.indices.map { i ->
            val h = highTenkan[i]; val l = lowTenkan[i]
            if (h == null || l == null) null else (h + l) / 2.0
        }
        val kijunBase: List<Double?> = highKijun.indices.map { i ->
            val h = highKijun[i]; val l = lowKijun[i]
            if (h == null || l == null) null else (h + l) / 2.0
        }
        val senkouABase: List<Double?> = tenkanBase.indices.map { i ->
            val t = tenkanBase[i]; val k = kijunBase[i]
            if (t == null || k == null) null else (t + k) / 2.0
        }
        val senkouBBase: List<Double?> = highSenkouB.indices.map { i ->
            val h = highSenkouB[i]; val l = lowSenkouB[i]
            if (h == null || l == null) null else (h + l) / 2.0
        }

        // Pandas .shift(kijunPeriod) on senkou_a / senkou_b: value at index -1 is the
        // base value at index n-1-kijunPeriod.
        val shiftIdx = n - 1 - kijunPeriod
        val currentSenkouA = senkouABase.getOrNull(shiftIdx)
        val currentSenkouB = senkouBBase.getOrNull(shiftIdx)
        val currentTenkan = tenkanBase[n - 1]
        val currentKijun = kijunBase[n - 1]
        val currentClose = closes[n - 1]
        val prevTenkan = tenkanBase[n - 2] ?: currentTenkan
        val prevKijun = kijunBase[n - 2] ?: currentKijun

        if (currentTenkan == null || currentKijun == null ||
            currentSenkouA == null || currentSenkouB == null ||
            prevTenkan == null || prevKijun == null
        ) {
            return result(Signal.NEUTRAL, "Ichimoku data insufficient (need more candles)")
        }

        val cloudTop = maxOf(currentSenkouA, currentSenkouB)
        val cloudBottom = minOf(currentSenkouA, currentSenkouB)

        val raw = mapOf<String, Double?>(
            "tenkan" to round2(currentTenkan),
            "kijun" to round2(currentKijun),
            "senkou_a" to round2(currentSenkouA),
            "senkou_b" to round2(currentSenkouB),
            "cloud_top" to round2(cloudTop),
            "cloud_bottom" to round2(cloudBottom),
        )

        val aboveCloud = currentClose > cloudTop
        val belowCloud = currentClose < cloudBottom

        val tkBullishCross = prevTenkan <= prevKijun && currentTenkan > currentKijun
        val tkBearishCross = prevTenkan >= prevKijun && currentTenkan < currentKijun
        val tenkanAboveKijun = currentTenkan > currentKijun

        val signals = mutableListOf<String>()
        var score = 0.0

        when {
            aboveCloud -> {
                signals.add("price above cloud (bullish)")
                score += 1.0
            }
            belowCloud -> {
                signals.add("price below cloud (bearish)")
                score -= 1.0
            }
            else -> signals.add("price in cloud (neutral)")
        }

        when {
            tkBullishCross -> {
                signals.add("TK bullish cross")
                score += 1.0
            }
            tkBearishCross -> {
                signals.add("TK bearish cross")
                score -= 1.0
            }
            tenkanAboveKijun -> score += 0.5
            else -> score -= 0.5
        }

        if (currentSenkouA > currentSenkouB) {
            signals.add("bullish cloud")
            score += 0.5
        } else {
            signals.add("bearish cloud")
            score -= 0.5
        }

        val reason = "Ichimoku: " + signals.joinToString(", ")

        val signal: Signal = when {
            score >= 2.0 -> Signal.STRONG_BUY
            score >= 0.5 -> Signal.BUY
            score <= -2.0 -> Signal.STRONG_SELL
            score <= -0.5 -> Signal.SELL
            else -> Signal.NEUTRAL
        }
        return result(signal, reason, raw)
    }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
}
