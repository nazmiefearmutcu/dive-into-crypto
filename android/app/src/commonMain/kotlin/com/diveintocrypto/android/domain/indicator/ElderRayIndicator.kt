package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal

/**
 * Elder-Ray Bull/Bear Power around an EMA value baseline.
 *
 *     Bull Power = high - EMA(close)
 *     Bear Power = low  - EMA(close)
 *
 * Fires only on the trend-aligned pullback-exhaustion entry (and its stronger,
 * deeper-recovery variant); otherwise NEUTRAL, so it contributes a *timing*
 * view (mean-reversion within a trend). Port of the Python `elder_ray.py` —
 * same math, thresholds, and signal mapping.
 */
class ElderRayIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "elder_ray"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        // --- parameters -----------------------------------------------------
        val emaPeriod = config.getInt("ema_period", 13)
        val slopeLookback = config.getInt("slope_lookback", 3)
        // EMA slope smaller than this (fraction of price) counts as "flat".
        val flatSlopePct = config.getDouble("flat_slope_pct", 0.0005)
        // |power| as a fraction of price that qualifies a move as "strong".
        val strongPowerPct = config.getDouble("strong_power_pct", 0.01)

        // --- guard ----------------------------------------------------------
        val minBars = emaPeriod + slopeLookback + 2
        if (candles.size < minBars) {
            return result(Signal.NEUTRAL, "insufficient data (<$minBars candles)")
        }

        val n = candles.size
        val highs = candles.map { it.high }
        val lows = candles.map { it.low }
        val closes = candles.map { it.close }

        val ema = Series.ewmAdjustFalse(closes, emaPeriod)

        val emaNow = ema[n - 1]
        val emaRef = ema[n - 1 - slopeLookback]
        if (emaNow == 0.0 || emaRef == 0.0 || emaNow.isNaN() || emaRef.isNaN()) {
            return result(Signal.NEUTRAL, "EMA baseline unavailable")
        }

        // Bull/Bear power (causal: high/low minus same-bar EMA).
        val curBull = highs[n - 1] - ema[n - 1]
        val curBear = lows[n - 1] - ema[n - 1]
        val prevBull = highs[n - 2] - ema[n - 2]
        val prevBear = lows[n - 2] - ema[n - 2]

        // Trend from EMA slope over slope_lookback bars (fraction of price).
        val slopePct = (emaNow - emaRef) / emaRef
        val up = slopePct > flatSlopePct
        val down = slopePct < -flatSlopePct

        // Power dynamics (direction of change vs prior closed candle).
        val bearRising = curBear > prevBear
        val bearFalling = curBear < prevBear
        val bullRising = curBull > prevBull
        val bullFalling = curBull < prevBull

        // Normalise powers to fractions of price for scale-free thresholds.
        val bullFrac = curBull / emaNow
        val bearFrac = curBear / emaNow

        val raw = mapOf<String, Double?>(
            "ema" to round4(emaNow),
            "bull_power" to round4(curBull),
            "bear_power" to round4(curBear),
            "bull_frac" to round5(bullFrac),
            "bear_frac" to round5(bearFrac),
            "slope_pct" to round5(slopePct),
        )

        // --- bullish pullback exhaustion inside an uptrend ------------------
        if (up && curBear < 0.0 && bearRising) {
            if (bullRising && bullFrac >= strongPowerPct) {
                return result(
                    Signal.STRONG_BUY,
                    "Uptrend + bears exhausting (bear power rising from below 0) " +
                        "with strong bull thrust",
                    raw,
                )
            }
            return result(
                Signal.BUY,
                "Uptrend + bear power turning up from below baseline " +
                    "(pullback exhausting)",
                raw,
            )
        }

        // --- bearish rally exhaustion inside a downtrend -------------------
        if (down && curBull > 0.0 && bullFalling) {
            if (bearFalling && bearFrac <= -strongPowerPct) {
                return result(
                    Signal.STRONG_SELL,
                    "Downtrend + bulls exhausting (bull power falling from above 0) " +
                        "with strong bear thrust",
                    raw,
                )
            }
            return result(
                Signal.SELL,
                "Downtrend + bull power turning down from above baseline " +
                    "(rally exhausting)",
                raw,
            )
        }

        // --- otherwise: no disciplined Elder-Ray entry ---------------------
        return result(Signal.NEUTRAL, "No trend-aligned power reversal", raw)
    }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
    private fun round5(x: Double): Double = Math.round(x * 100_000.0) / 100_000.0
}
