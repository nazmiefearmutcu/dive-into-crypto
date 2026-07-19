package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import kotlin.math.abs

/** TTM Squeeze (Bollinger Bands inside Keltner Channels). Ported from Python TTMSqueezeIndicator. */
class SqueezeIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "squeeze"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 20)
        val bbMult = config.getDouble("bb_mult", 2.0)
        val kcMult = config.getDouble("kc_mult", 1.5)

        if (candles.isEmpty()) {
            return result(Signal.NEUTRAL, "Squeeze data insufficient")
        }

        val closes = candles.map { it.close }

        // Bollinger Bands components
        val sma = Series.rollingMean(closes, period)
        val std = Series.rollingStd(closes, period)

        // True Range (row-wise max skips the missing prev close at i == 0, like pandas)
        val tr = candles.indices.map { i ->
            val c = candles[i]
            if (i == 0) c.high - c.low
            else maxOf(c.high - c.low, abs(c.high - candles[i - 1].close), abs(c.low - candles[i - 1].close))
        }

        // Keltner Channels component
        val atr = Series.rollingMean(tr, period)

        // Squeeze On: BB is completely inside KC (false during warm-up, matching pandas NaN comparisons)
        val squeezeOn = candles.indices.map { i ->
            val m = sma[i]
            val sd = std[i]
            val a = atr[i]
            if (m == null || sd == null || a == null) false
            else (m + bbMult * sd < m + kcMult * a) && (m - bbMult * sd > m - kcMult * a)
        }

        val currentSq = squeezeOn.last()
        val prevSq = if (squeezeOn.size >= 2) squeezeOn[squeezeOn.size - 2] else currentSq

        // Momentum proxy
        val currentMom = sma.last()?.let { closes.last() - it }
            ?: return result(Signal.NEUTRAL, "Squeeze data insufficient")

        val raw = mapOf<String, Double?>(
            // Python emits a boolean here; encoded numerically: 1.0 = squeeze on, 0.0 = off
            "squeeze_on" to if (currentSq) 1.0 else 0.0,
            "momentum" to round4(currentMom),
        )

        // Squeeze release (fires)
        if (prevSq && !currentSq) {
            return if (currentMom > 0) {
                result(Signal.STRONG_BUY, "Squeeze fired LONG", raw)
            } else {
                result(Signal.STRONG_SELL, "Squeeze fired SHORT", raw)
            }
        }

        // Squeeze is currently on (building energy)
        if (currentSq) {
            return result(Signal.NEUTRAL, "Consolidating (Squeeze ON)", raw)
        }

        // No squeeze, just trending
        return if (currentMom > 0) {
            result(Signal.BUY, "Positive momentum (No Squeeze)", raw)
        } else {
            result(Signal.SELL, "Negative momentum (No Squeeze)", raw)
        }
    }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
}
