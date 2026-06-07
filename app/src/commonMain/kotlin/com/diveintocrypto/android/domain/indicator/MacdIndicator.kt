package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import kotlin.math.abs

class MacdIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "macd"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val fast = config.getInt("fast_period", 12)
        val slow = config.getInt("slow_period", 26)
        val signalP = config.getInt("signal_period", 9)
        val strongHist = config.getDouble("strong_histogram_threshold", 0.5)

        if (candles.size < slow + signalP) {
            return result(Signal.NEUTRAL, "MACD data insufficient")
        }

        val closes = candles.map { it.close }
        val emaFast = Series.ewmAdjustFalse(closes, fast)
        val emaSlow = Series.ewmAdjustFalse(closes, slow)
        val macdLine = emaFast.zip(emaSlow) { a, b -> a - b }
        val signalLine = Series.ewmAdjustFalse(macdLine, signalP)
        val histogram = macdLine.zip(signalLine) { a, b -> a - b }

        val currentMacd = macdLine.last()
        val currentSignal = signalLine.last()
        val currentHist = histogram.last()
        val prevHist = histogram.dropLast(1).lastOrNull() ?: 0.0
        val prevMacd = macdLine.dropLast(1).lastOrNull() ?: currentMacd
        val prevSignal = signalLine.dropLast(1).lastOrNull() ?: currentSignal

        val raw = mapOf<String, Double?>(
            "macd" to round4(currentMacd),
            "signal" to round4(currentSignal),
            "histogram" to round4(currentHist),
        )

        val price = closes.last()
        val normThreshold = price * strongHist / 100.0

        val bullishCross = prevMacd <= prevSignal && currentMacd > currentSignal
        val bearishCross = prevMacd >= prevSignal && currentMacd < currentSignal

        return when {
            bullishCross && abs(currentHist) > normThreshold ->
                result(Signal.STRONG_BUY, "MACD bullish crossover with strong momentum", raw)
            bullishCross ->
                result(Signal.BUY, "MACD bullish crossover", raw)
            bearishCross && abs(currentHist) > normThreshold ->
                result(Signal.STRONG_SELL, "MACD bearish crossover with strong momentum", raw)
            bearishCross ->
                result(Signal.SELL, "MACD bearish crossover", raw)
            currentMacd > currentSignal && currentHist > prevHist -> {
                if (currentHist > normThreshold) result(Signal.STRONG_BUY, "MACD above signal, histogram expanding strongly", raw)
                else result(Signal.BUY, "MACD above signal, histogram expanding", raw)
            }
            currentMacd < currentSignal && currentHist < prevHist -> {
                if (abs(currentHist) > normThreshold) result(Signal.STRONG_SELL, "MACD below signal, histogram expanding strongly", raw)
                else result(Signal.SELL, "MACD below signal, histogram expanding", raw)
            }
            else -> result(Signal.NEUTRAL, "MACD indecisive", raw)
        }
    }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
}
