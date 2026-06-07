package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format

class BollingerIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "bollinger"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 20)
        val stdDev = config.getDouble("std_dev", 2.0)
        val squeezeTh = config.getDouble("squeeze_threshold", 0.02)

        if (candles.size < period) return result(Signal.NEUTRAL, "Bollinger data insufficient")

        val closes = candles.map { it.close }
        val sma = Series.rollingMean(closes, period)
        val std = Series.rollingStd(closes, period)

        val currentSma = sma.last() ?: return result(Signal.NEUTRAL, "Bollinger data insufficient")
        val currentStd = std.last() ?: return result(Signal.NEUTRAL, "Bollinger data insufficient")
        val upper = currentSma + currentStd * stdDev
        val lower = currentSma - currentStd * stdDev
        val close = closes.last()

        val bandWidth = if (currentSma != 0.0) (upper - lower) / currentSma else 0.0
        val position = if ((upper - lower) != 0.0) (close - lower) / (upper - lower) else 0.5

        val raw = mapOf<String, Double?>(
            "upper" to round2(upper),
            "lower" to round2(lower),
            "sma" to round2(currentSma),
            "band_width" to round4(bandWidth),
            "position" to round4(position),
        )

        val isSqueeze = bandWidth < squeezeTh

        return when {
            close < lower && isSqueeze ->
                result(Signal.BUY, "Price below lower band during squeeze - potential breakout", raw)
            close < lower ->
                result(Signal.STRONG_BUY, "Price below lower Bollinger Band", raw)
            position < 0.15 ->
                result(Signal.BUY, "Price near lower Bollinger Band (pos=${position.format(2)})", raw)
            close > upper && isSqueeze ->
                result(Signal.SELL, "Price above upper band during squeeze - potential breakout", raw)
            close > upper ->
                result(Signal.STRONG_SELL, "Price above upper Bollinger Band", raw)
            position > 0.85 ->
                result(Signal.SELL, "Price near upper Bollinger Band (pos=${position.format(2)})", raw)
            else -> {
                val zone = if (isSqueeze) "squeeze detected - awaiting breakout" else "mid-band neutral"
                result(Signal.NEUTRAL, "Bollinger $zone (pos=${position.format(2)})", raw)
            }
        }
    }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
}
