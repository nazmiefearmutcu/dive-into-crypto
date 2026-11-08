package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format

/**
 * RSI, mirroring rsi.py: average gain and average loss are simple means of the last
 * `period` deltas (Cutler-style), NOT Wilder's RMA smoothing. Deliberate: stoch_rsi
 * uses the same series. Values differ from charting-platform RSI for the same closes.
 */
class RsiIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "rsi"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 14)
        val strongBuy = config.getDouble("strong_buy", 25.0)
        val buy = config.getDouble("buy", 35.0)
        val sell = config.getDouble("sell", 65.0)
        val strongSell = config.getDouble("strong_sell", 80.0)

        if (candles.size <= period) {
            return result(Signal.NEUTRAL, "RSI data insufficient", mapOf("rsi" to null))
        }

        val closes = candles.map { it.close }
        // Python: delta = close.diff() - length N, first NaN. We drop the NaN.
        val deltas = closes.zipWithNext { a, b -> b - a } // length N-1

        val gains = deltas.map { if (it > 0.0) it else 0.0 }
        val losses = deltas.map { if (it < 0.0) -it else 0.0 }

        val avgGain = gains.takeLast(period).average()
        val avgLoss = losses.takeLast(period).average()

        // Mirrors rsi.py's np.select: a flat window is RSI 50 and runs through the
        // threshold ladder (no early return, so thresholds at or below 50 still apply).
        val rsi = when {
            avgGain == 0.0 && avgLoss == 0.0 -> 50.0
            avgLoss == 0.0 -> 100.0
            avgGain == 0.0 -> 0.0
            else -> {
                val rs = avgGain / avgLoss
                100.0 - 100.0 / (1.0 + rs)
            }
        }

        val rounded = Math.round(rsi * 100.0) / 100.0
        val raw = mapOf<String, Double?>("rsi" to rounded)

        return when {
            rsi <= strongBuy -> result(Signal.STRONG_BUY, "RSI=${rsi.format(1)} extremely oversold", raw)
            rsi <= buy -> result(Signal.BUY, "RSI=${rsi.format(1)} oversold zone", raw)
            rsi >= strongSell -> result(Signal.STRONG_SELL, "RSI=${rsi.format(1)} extremely overbought", raw)
            rsi >= sell -> result(Signal.SELL, "RSI=${rsi.format(1)} overbought zone", raw)
            else -> result(Signal.NEUTRAL, "RSI=${rsi.format(1)} neutral zone", raw)
        }
    }
}
