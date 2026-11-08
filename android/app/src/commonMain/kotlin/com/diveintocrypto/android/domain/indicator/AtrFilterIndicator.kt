package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.abs

/**
 * ATR (Average True Range) risk filter — verbatim port of
 * the original Python reference implementation.
 *
 * Python uses tr.rolling(period).mean() — plain SMA of True Range, NOT Wilder
 * smoothing. The fixture (atr=440.305) confirms this. Do not "fix" to Wilder.
 *
 * The signal is always NEUTRAL — this indicator only annotates volatility.
 */
class AtrFilterIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "atr_filter"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 14)
        val highVolMultiplier = config.getDouble("high_volatility_multiplier", 2.0)

        if (candles.size < period) {
            return result(Signal.NEUTRAL, "ATR data insufficient")
        }

        val highs = candles.map { it.high }
        val lows = candles.map { it.low }
        val closes = candles.map { it.close }
        val n = candles.size

        // True Range: pandas .shift(1) puts NaN at index 0; we mirror by leaving
        // tr[0] = high-low (tr2/tr3 contribute NaN which max-of-row collapses to tr1).
        val tr = DoubleArray(n)
        tr[0] = highs[0] - lows[0]
        for (i in 1 until n) {
            val tr1 = highs[i] - lows[i]
            val tr2 = abs(highs[i] - closes[i - 1])
            val tr3 = abs(lows[i] - closes[i - 1])
            tr[i] = maxOf(tr1, tr2, tr3)
        }

        val atrSeries = Series.rollingMean(tr.toList(), period)
        val currentAtr = atrSeries.last() ?: return result(Signal.NEUTRAL, "ATR data insufficient")
        val currentPrice = closes.last()

        if (currentPrice == 0.0) return result(Signal.NEUTRAL, "ATR data insufficient")

        val safePrice = if (currentPrice == 0.0) 0.00000001 else currentPrice
        val atrPct = currentAtr / safePrice * 100.0

        // atr_mean = atr.rolling(period*3).mean().iloc[-1]
        // pandas rolling-of-rolling: NaNs at the front of `atr` propagate forward; we
        // emulate by feeding atrSeries with nulls-as-NaN. Use a manual SMA that returns
        // null until the trailing window of size period*3 is fully populated WITH non-null
        // values. Since the rolling mean above first emits at index period-1, a trailing
        // window of period*3 covering the last 42 entries requires index >= period-1 + period*3 - 1 = 55.
        val longWindow = period * 3
        val atrAsValues: List<Double?> = atrSeries
        val atrMean: Double? = run {
            if (atrAsValues.size < longWindow) null
            else {
                val tail = atrAsValues.subList(atrAsValues.size - longWindow, atrAsValues.size)
                if (tail.any { it == null }) null else tail.filterNotNull().average()
            }
        }

        val reasonAtr = currentAtr.format(2)
        val reasonPct = atrPct.format(2)

        // Without a full 3x-period baseline there is no volatility regime. Do not
        // default the ratio to 1.0, which reads as "normal volatility".
        if (atrMean == null || atrMean == 0.0) {
            return result(
                Signal.NEUTRAL,
                "ATR=$reasonAtr ($reasonPct%) baseline warming up",
                mapOf<String, Double?>("atr" to round4(currentAtr), "atr_pct" to round4(atrPct)),
            )
        }

        val atrRatio: Double = currentAtr / atrMean

        // One set of cutoffs drives the label, the numeric encoding and the reason text.
        val volatility = when {
            atrRatio > highVolMultiplier -> "HIGH"
            atrRatio < 0.5 -> "LOW"
            else -> "NORMAL"
        }

        val raw = mapOf<String, Double?>(
            "atr" to round4(currentAtr),
            "atr_pct" to round4(atrPct),
            "atr_ratio" to round4(atrRatio),
            // Numeric encoding read by RiskAssessor (risk.py reads the string label):
            // 2.0 HIGH, 1.0 NORMAL, 0.0 LOW, with the same cutoffs as the label below.
            "volatility" to when {
                atrRatio > highVolMultiplier -> 2.0
                atrRatio < 0.5 -> 0.0
                else -> 1.0
            },
        )

        // One set of cutoffs for the label and the reason text (HIGH above the
        // multiplier, LOW below 0.5, otherwise NORMAL), matching atr_filter.py.
        return when {
            atrRatio > highVolMultiplier -> result(
                Signal.NEUTRAL,
                "High ATR volatility (${atrRatio.format(1)}x average) — wide ranges",
                raw,
            )
            atrRatio < 0.5 -> result(
                Signal.NEUTRAL,
                "Low ATR volatility (${atrRatio.format(1)}x average) — narrow ranges",
                raw,
            )
            else -> result(
                Signal.NEUTRAL,
                "ATR=$reasonAtr ($reasonPct%) normal volatility",
                raw,
            )
        }
    }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
}
