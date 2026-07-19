package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.max

/**
 * Coppock Curve — long-term momentum turn oscillator.
 *
 *     ROC_long  = 100 * (close / close[-roc_long_period]  - 1)
 *     ROC_short = 100 * (close / close[-roc_short_period] - 1)
 *     Coppock   = WMA(ROC_long + ROC_short, wma_period)   // weights 1..N, newest = N
 *
 * The classic signal is a TURN, not a level cross: below zero turning up is the
 * canonical major buy; above zero turning down is the mirrored sell. Port of
 * the Python `coppock_curve.py` — same math, thresholds, and signal mapping.
 */
class CoppockCurveIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "coppock_curve"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        // --- parameters -----------------------------------------------------
        val rocLongPeriod = config.getInt("roc_long_period", 14)
        val rocShortPeriod = config.getInt("roc_short_period", 11)
        val wmaPeriod = config.getInt("wma_period", 10)
        // Deadband around zero: |curve| <= zero_band counts as "at the line".
        val zeroBand = config.getDouble("zero_band", 0.0)

        // --- guard ----------------------------------------------------------
        // Need the longest ROC shift, then the WMA window, then 3 curve points
        // (-1, -2, -3) for trough / peak detection.
        val minBars = max(rocLongPeriod, rocShortPeriod) + wmaPeriod + 3
        if (candles.size < minBars) {
            return result(Signal.NEUTRAL, "insufficient data (<$minBars candles)")
        }

        val closes = candles.map { it.close }
        val n = closes.size

        val rocLong = roc(closes, rocLongPeriod)
        val rocShort = roc(closes, rocShortPeriod)
        val rocSum = DoubleArray(n) { rocLong[it] + rocShort[it] }
        val coppock = wma(rocSum, wmaPeriod)

        val cur = coppock[n - 1]
        val prev = coppock[n - 2]
        val prev2 = coppock[n - 3]

        if (cur.isNaN() || prev.isNaN() || prev2.isNaN()) {
            return result(Signal.NEUTRAL, "Coppock data insufficient")
        }

        val rising = cur > prev
        val falling = cur < prev
        // A turn is a local extremum at the *previous* closed bar confirmed by
        // the current bar: trough = down-then-up, peak = up-then-down.
        val turnUp = (cur > prev) && (prev <= prev2)
        val turnDown = (cur < prev) && (prev >= prev2)

        val raw = mapOf<String, Double?>(
            "coppock" to round4(cur),
            "coppock_prev" to round4(prev),
            "coppock_prev2" to round4(prev2),
            "roc_long" to round4(rocLong[n - 1]),
            "roc_short" to round4(rocShort[n - 1]),
            "slope" to round4(cur - prev),
        )

        // --- decision tree (priority order) --------------------------------
        // 1) Classic Coppock buy: upturn while the curve is below zero.
        if (turnUp && cur < -zeroBand) {
            return result(
                Signal.STRONG_BUY,
                "Coppock=${cur.format(3)} turned up from below zero " +
                    "(long-term downside momentum exhausted)",
                raw,
            )
        }
        // 2) Mirror sell: downturn while the curve is above zero.
        if (turnDown && cur > zeroBand) {
            return result(
                Signal.STRONG_SELL,
                "Coppock=${cur.format(3)} turned down from above zero " +
                    "(long-term upside momentum exhausted)",
                raw,
            )
        }
        // 3) Bullish turn while already positive: momentum re-accelerating.
        if (turnUp) {
            return result(
                Signal.BUY,
                "Coppock=${cur.format(3)} turned up in positive regime " +
                    "(momentum re-accelerating)",
                raw,
            )
        }
        // 4) Bearish turn while already negative: recovery failing.
        if (turnDown) {
            return result(
                Signal.SELL,
                "Coppock=${cur.format(3)} turned down in negative regime " +
                    "(recovery failing)",
                raw,
            )
        }
        // 5) Trend-intact continuation votes (positive & rising / negative & falling).
        if (cur > zeroBand && rising) {
            return result(
                Signal.BUY,
                "Coppock=${cur.format(3)} positive and rising (momentum intact)",
                raw,
            )
        }
        if (cur < -zeroBand && falling) {
            return result(
                Signal.SELL,
                "Coppock=${cur.format(3)} negative and falling (momentum intact)",
                raw,
            )
        }
        // 6) Everything else: fading momentum without a confirmed turn, or flat.
        return result(
            Signal.NEUTRAL,
            "Coppock=${cur.format(3)} no confirmed turn / flat",
            raw,
        )
    }

    /** 100 * (close / close.shift(period) - 1); NaN for the warm-up region. */
    private fun roc(closes: List<Double>, period: Int): DoubleArray =
        DoubleArray(closes.size) { i ->
            if (i < period) Double.NaN else (closes[i] / closes[i - period] - 1.0) * 100.0
        }

    /**
     * Linearly weighted moving average (weights 1..period, newest heaviest).
     * Trailing window only -> strictly causal. NaN until `period` valid inputs
     * (any NaN inside the window yields NaN, as in pandas rolling.apply).
     */
    private fun wma(values: DoubleArray, period: Int): DoubleArray {
        val wsum = period * (period + 1) / 2.0
        val out = DoubleArray(values.size) { Double.NaN }
        for (i in values.indices) {
            if (i + 1 < period) continue
            var dot = 0.0
            var ok = true
            for (k in 0 until period) {
                val v = values[i + 1 - period + k]
                if (v.isNaN()) {
                    ok = false
                    break
                }
                dot += v * (k + 1)
            }
            if (ok) out[i] = dot / wsum
        }
        return out
    }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
}
