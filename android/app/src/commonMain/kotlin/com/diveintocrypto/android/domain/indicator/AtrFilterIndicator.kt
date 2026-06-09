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

        val atrRatio: Double = if (atrMean != null && atrMean != 0.0) currentAtr / atrMean else 1.0

        val volatility = when {
            atrRatio > highVolMultiplier -> "HIGH"
            atrRatio > 0.8 -> "NORMAL"
            else -> "LOW"
        }

        val raw = mapOf<String, Double?>(
            "atr" to round4(currentAtr),
            "atr_pct" to round4(atrPct),
            "atr_ratio" to round4(atrRatio),
            // volatility is a string in Python; tests only check numeric raws.
        )

        val reasonAtr = currentAtr.format(2)
        val reasonPct = atrPct.format(2)

        return when {
            atrRatio > highVolMultiplier -> result(
                Signal.NEUTRAL,
                "ATR=$reasonAtr ($reasonPct%) HIGH volatility - risk elevated, reduce position",
                raw,
            )
            atrRatio < 0.5 -> result(
                Signal.NEUTRAL,
                "ATR=$reasonAtr ($reasonPct%) LOW volatility - potential breakout watch",
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
