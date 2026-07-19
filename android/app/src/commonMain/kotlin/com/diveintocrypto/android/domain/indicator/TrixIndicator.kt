package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.abs

/**
 * TRIX — 1-bar percent rate-of-change of a *triple* EMA of close, plus a
 * signal line (EMA of TRIX) for MACD-style crossover reads.
 *
 *     ema3   = EMA(EMA(EMA(close, period), period), period)
 *     trix   = 100 * (ema3_t - ema3_{t-1}) / ema3_{t-1}
 *     signal = EMA(trix, signal_period)
 *
 * Event-first mapping (signal-line crosses), then regime + slope + magnitude,
 * all gated by a zero-line dead band. Port of the Python `trix.py` — same
 * math, thresholds, and signal mapping. NaN warm-up values are modelled with
 * Double.NaN so comparisons behave exactly like pandas (any NaN compare is
 * false).
 */
class TrixIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "trix"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 15)
        val signalPeriod = config.getInt("signal_period", 9)
        val strongThreshold = config.getDouble("strong_threshold", 0.10)
        val zeroBand = config.getDouble("zero_band", 0.02)

        // Need enough bars to warm up the triple EMA meaningfully.
        if (candles.size < period * 3) {
            return result(Signal.NEUTRAL, "TRIX data insufficient")
        }

        val closes = candles.map { it.close }
        val n = closes.size

        val ema1 = Series.ewmAdjustFalse(closes, period)
        val ema2 = Series.ewmAdjustFalse(ema1, period)
        val ema3 = Series.ewmAdjustFalse(ema2, period)

        // 1-bar percent ROC of the triple-smoothed line (NaN at index 0 and
        // wherever the previous value is 0, mirroring `.replace(0.0, nan)`).
        val trix = DoubleArray(n) { i ->
            if (i == 0) Double.NaN
            else {
                val prev = ema3[i - 1]
                if (prev == 0.0) Double.NaN else 100.0 * (ema3[i] - prev) / prev
            }
        }

        val signal = ewmAdjustFalseNanAware(trix, signalPeriod)

        val currentTrix = trix[n - 1]
        val prevTrix = if (n >= 2) trix[n - 2] else currentTrix
        val currentSignal = signal[n - 1]
        val prevSignal = if (n >= 2) signal[n - 2] else currentSignal

        if (currentTrix.isNaN() || currentSignal.isNaN()) {
            return result(Signal.NEUTRAL, "TRIX data insufficient")
        }

        val hist = currentTrix - currentSignal
        val raw = mapOf<String, Double?>(
            "trix" to round4(currentTrix),
            "trix_prev" to round4(prevTrix),
            "signal" to round4(currentSignal),
            "histogram" to round4(hist),
        )

        val rising = currentTrix > prevTrix
        val falling = currentTrix < prevTrix

        val bullCross = prevTrix <= prevSignal && currentTrix > currentSignal
        val bearCross = prevTrix >= prevSignal && currentTrix < currentSignal

        // --- Dead-zone: within +/-zero_band of the zero line, TRIX oscillations
        //     (including signal-line crossovers) are noise -> stay flat. ---
        if (abs(currentTrix) <= zeroBand) {
            return result(
                Signal.NEUTRAL, "TRIX=${currentTrix.format(4)} flat / near zero", raw,
            )
        }

        // --- Event-first: signal-line crossovers (now |trix| > zero_band) ---
        if (bullCross) {
            if (currentTrix > 0.0) {
                return result(
                    Signal.STRONG_BUY,
                    "TRIX=${currentTrix.format(4)} bullish signal cross above zero",
                    raw,
                )
            }
            return result(
                Signal.BUY,
                "TRIX=${currentTrix.format(4)} bullish signal cross (below zero, early)",
                raw,
            )
        }
        if (bearCross) {
            if (currentTrix < 0.0) {
                return result(
                    Signal.STRONG_SELL,
                    "TRIX=${currentTrix.format(4)} bearish signal cross below zero",
                    raw,
                )
            }
            return result(
                Signal.SELL,
                "TRIX=${currentTrix.format(4)} bearish signal cross (above zero, early)",
                raw,
            )
        }

        // --- Regime + slope + magnitude (no fresh cross) ---
        if (currentTrix > 0.0) { // positive regime (already > zero_band)
            if (rising) {
                if (currentTrix > strongThreshold) {
                    return result(
                        Signal.STRONG_BUY,
                        "TRIX=${currentTrix.format(4)} strong positive momentum rising",
                        raw,
                    )
                }
                return result(
                    Signal.BUY,
                    "TRIX=${currentTrix.format(4)} positive momentum rising",
                    raw,
                )
            }
            return result(
                Signal.NEUTRAL,
                "TRIX=${currentTrix.format(4)} positive but momentum fading",
                raw,
            )
        }

        // negative regime (already < -zero_band)
        if (falling) {
            if (currentTrix < -strongThreshold) {
                return result(
                    Signal.STRONG_SELL,
                    "TRIX=${currentTrix.format(4)} strong negative momentum falling",
                    raw,
                )
            }
            return result(
                Signal.SELL,
                "TRIX=${currentTrix.format(4)} negative momentum falling",
                raw,
            )
        }
        return result(
            Signal.NEUTRAL,
            "TRIX=${currentTrix.format(4)} negative but momentum recovering",
            raw,
        )
    }

    /**
     * pandas `ewm(span=n, adjust=False).mean()` over a series that may contain
     * NaN warm-up values: output is NaN until the first valid observation,
     * which seeds the recursion; NaN rows after the seed carry the previous
     * mean forward (only the leading warm-up NaN is reachable here).
     */
    private fun ewmAdjustFalseNanAware(values: DoubleArray, span: Int): DoubleArray {
        val alpha = 2.0 / (span + 1)
        val out = DoubleArray(values.size) { Double.NaN }
        var prev = Double.NaN
        for (i in values.indices) {
            val x = values[i]
            prev = when {
                x.isNaN() -> prev
                prev.isNaN() -> x
                else -> alpha * x + (1.0 - alpha) * prev
            }
            out[i] = prev
        }
        return out
    }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
}
