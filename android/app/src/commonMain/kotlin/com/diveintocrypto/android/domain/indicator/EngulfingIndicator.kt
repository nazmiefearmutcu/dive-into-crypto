package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format

/**
 * Engulfing candlestick pattern — verbatim port of the Python reference
 * `engulfing.py`.
 *
 * Classic two-bar reversal pattern. The current bar's REAL BODY
 * (|close - open|) fully engulfs the prior bar's body, and the two bars have
 * opposite colours:
 *
 *   bullish: current close > open, prior close < open,
 *            open_cur <= open_prior AND close_cur >= close_prior
 *   bearish: mirrored (current red body engulfs prior green body)
 *
 * Strength grading: STRONG when the engulfing candle's body carves out most
 * of its total range (body / (high - low) >= `body_ratio`, default 0.7) and
 * the body is strictly larger than the prior body — a decisive, low-wick
 * reversal candle. Causal: reads only the last two closed bars.
 */
class EngulfingIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "engulfing"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val bodyRatioMin = config.getDouble("body_ratio", 0.7)

        if (candles.size < 2) {
            return result(Signal.NEUTRAL, "Engulfing data insufficient (<2 candles)")
        }

        val prev = candles[candles.size - 2]
        val cur = candles[candles.size - 1]

        val bodyCur = kotlin.math.abs(cur.close - cur.open)
        val bodyPrev = kotlin.math.abs(prev.close - prev.open)
        val rangeCur = cur.high - cur.low
        // 0-range current bar cannot produce a meaningful engulf.
        val ratio = if (rangeCur > 0.0) bodyCur / rangeCur else 0.0

        val raw = mapOf<String, Double?>(
            "body_cur" to round6(bodyCur),
            "body_prev" to round6(bodyPrev),
            "body_ratio" to round4(ratio),
            "strong_ratio" to bodyRatioMin,
        )

        val bullish = cur.close > cur.open && prev.close < prev.open &&
            cur.open <= prev.open && cur.close >= prev.close
        val bearish = cur.close < cur.open && prev.close > prev.open &&
            cur.open >= prev.open && cur.close <= prev.close
        val strong = bodyCur > bodyPrev && ratio >= bodyRatioMin

        if (bullish) {
            return if (strong) {
                result(
                    Signal.STRONG_BUY,
                    "Bullish engulfing, body/range=${ratio.format(2)} decisive",
                    raw + ("direction" to 1.0),
                )
            } else {
                result(
                    Signal.BUY,
                    "Bullish engulfing, body/range=${ratio.format(2)} marginal",
                    raw + ("direction" to 1.0),
                )
            }
        }
        if (bearish) {
            return if (strong) {
                result(
                    Signal.STRONG_SELL,
                    "Bearish engulfing, body/range=${ratio.format(2)} decisive",
                    raw + ("direction" to -1.0),
                )
            } else {
                result(
                    Signal.SELL,
                    "Bearish engulfing, body/range=${ratio.format(2)} marginal",
                    raw + ("direction" to -1.0),
                )
            }
        }

        return result(Signal.NEUTRAL, "No engulfing pattern", raw + ("direction" to 0.0))
    }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
    private fun round6(x: Double): Double = Math.round(x * 1000000.0) / 1000000.0
}
