package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.abs
import kotlin.math.max

/**
 * Keltner Channel Breakout — EMA midline +/- multiplier * ATR (Wilder-style RMA
 * via ewm(alpha=1/atr_period, adjust=False)). Canonical momentum breakout read:
 * a close beyond a band is a breakout in that direction (opposite regime to the
 * mean-reverting Bollinger indicator). Port of the Python
 * `keltner_breakout.py` — same math, thresholds, and signal mapping.
 */
class KeltnerBreakoutIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "keltner_breakout"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val emaPeriod = config.getInt("ema_period", 20)
        val atrPeriod = config.getInt("atr_period", 10)
        val multiplier = config.getDouble("multiplier", 2.0)
        val innerBand = config.getDouble("inner_band", 0.5)
        val slopeLookback = config.getInt("slope_lookback", 3)

        val needed = max(emaPeriod, atrPeriod) + slopeLookback + 1
        if (candles.size < needed) {
            return result(Signal.NEUTRAL, "Keltner data insufficient")
        }

        val n = candles.size
        val highs = candles.map { it.high }
        val lows = candles.map { it.low }
        val closes = candles.map { it.close }

        // --- EMA midline (recursive, causal) ---
        val ema = Series.ewmAdjustFalse(closes, emaPeriod)

        // --- ATR via Wilder's RMA of True Range (causal) ---
        // First row has no prev close -> TR = high - low (pandas skips the NaNs).
        val tr = DoubleArray(n) { i ->
            if (i == 0) highs[0] - lows[0]
            else {
                val prevClose = closes[i - 1]
                maxOf(highs[i] - lows[i], abs(highs[i] - prevClose), abs(lows[i] - prevClose))
            }
        }
        // ewm(alpha=1/atr_period, adjust=False): seeded at the first value.
        val alpha = 1.0 / atrPeriod
        val atr = DoubleArray(n)
        atr[0] = tr[0]
        for (i in 1 until n) atr[i] = alpha * tr[i] + (1.0 - alpha) * atr[i - 1]

        val curEma = ema[n - 1]
        val curAtr = atr[n - 1]
        val curClose = closes[n - 1]

        if (!curEma.isFinite() || !curAtr.isFinite() || curAtr <= 0.0) {
            return result(Signal.NEUTRAL, "Keltner data insufficient")
        }

        val half = multiplier * curAtr
        val upper = curEma + half
        val lower = curEma - half

        // Channel position: +1 == at upper band, -1 == at lower band, 0 == midline.
        val position = if (half != 0.0) (curClose - curEma) / half else 0.0

        // Midline slope over the lookback window (past data only).
        val pastEma = ema[n - 1 - slopeLookback]
        val slope = curEma - pastEma

        // Was price inside the channel on the previous candle? (breakout freshness)
        val prevCloseV = closes[n - 2]
        val prevEma = ema[n - 2]
        val prevAtr = atr[n - 2]
        val prevUpper = prevEma + multiplier * prevAtr
        val prevLower = prevEma - multiplier * prevAtr

        // Python raw also carries a "state" string; rawValues is numeric-only, so
        // it is dropped here (same convention as the AtrFilter port).
        val raw = mapOf<String, Double?>(
            "ema" to round4(curEma),
            "upper" to round4(upper),
            "lower" to round4(lower),
            "atr" to round4(curAtr),
            "position" to round4(position),
            "slope" to round6(slope),
            "multiplier" to multiplier,
        )

        // --- 5-level breakout mapping ---
        if (position >= 1.0) {
            val tag = if (prevCloseV <= prevUpper) "fresh breakout" else "sustained breakout"
            return result(
                Signal.STRONG_BUY,
                "Close above upper Keltner band ($tag, p=${position.format(2)})",
                raw,
            )
        }

        if (position <= -1.0) {
            val tag = if (prevCloseV >= prevLower) "fresh breakdown" else "sustained breakdown"
            return result(
                Signal.STRONG_SELL,
                "Close below lower Keltner band ($tag, p=${position.format(2)})",
                raw,
            )
        }

        if (position >= innerBand && slope >= 0.0) {
            return result(
                Signal.BUY,
                "Upper Keltner channel with rising midline (p=${position.format(2)})",
                raw,
            )
        }

        if (position <= -innerBand && slope <= 0.0) {
            return result(
                Signal.SELL,
                "Lower Keltner channel with falling midline (p=${position.format(2)})",
                raw,
            )
        }

        return result(
            Signal.NEUTRAL,
            "Price inside Keltner channel (p=${position.format(2)})",
            raw,
        )
    }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
    private fun round6(x: Double): Double = Math.round(x * 1_000_000.0) / 1_000_000.0
}
