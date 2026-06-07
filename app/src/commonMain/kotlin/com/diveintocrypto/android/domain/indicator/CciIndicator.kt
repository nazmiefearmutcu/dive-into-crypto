package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.abs

/**
 * 1:1 port of the original Python reference implementation.
 *
 * CCI = (typical_price - SMA(tp)) / (0.015 * mean_deviation)
 * where mean_deviation = rolling mean of |tp - mean(tp_window)| over `period`.
 */
class CciIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "cci"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 20)
        val buyLevel = config.getDouble("buy", -100.0)
        val strongBuyLevel = config.getDouble("strong_buy", -200.0)
        val sellLevel = config.getDouble("sell", 100.0)
        val strongSellLevel = config.getDouble("strong_sell", 200.0)

        if (candles.size < period) {
            return result(Signal.NEUTRAL, "CCI data insufficient")
        }

        val tp = candles.map { (it.high + it.low + it.close) / 3.0 }
        val smaTp = Series.rollingMean(tp, period)

        // pandas: rolling.apply(lambda x: mean(|x - mean(x)|), raw=True)
        // For each index i (>= period-1), compute MAD of the window.
        val meanDev: List<Double?> = tp.indices.map { i ->
            if (i + 1 < period) null
            else {
                val w = tp.subList(i + 1 - period, i + 1)
                val mean = w.average()
                w.sumOf { abs(it - mean) } / period
            }
        }

        // CCI at the last index
        val lastIdx = tp.size - 1
        val sma = smaTp[lastIdx]
        val md = meanDev[lastIdx]

        if (sma == null || md == null || md == 0.0) {
            return result(Signal.NEUTRAL, "CCI data insufficient")
        }

        val cci = (tp[lastIdx] - sma) / (0.015 * md)
        val raw = mapOf<String, Double?>("cci" to round2(cci))

        return when {
            cci <= strongBuyLevel ->
                result(Signal.STRONG_BUY, "CCI=${cci.format(1)} deeply oversold", raw)
            cci <= buyLevel ->
                result(Signal.BUY, "CCI=${cci.format(1)} oversold", raw)
            cci >= strongSellLevel ->
                result(Signal.STRONG_SELL, "CCI=${cci.format(1)} deeply overbought", raw)
            cci >= sellLevel ->
                result(Signal.SELL, "CCI=${cci.format(1)} overbought", raw)
            else ->
                result(Signal.NEUTRAL, "CCI=${cci.format(1)} neutral range", raw)
        }
    }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
}
