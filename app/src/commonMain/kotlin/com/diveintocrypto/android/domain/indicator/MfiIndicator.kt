package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format

/**
 * 1:1 port of the original Python reference implementation.
 *
 *   typical_price = (high + low + close) / 3
 *   money_flow = typical_price * volume
 *   positive_flow_i = money_flow_i  if  tp_i > tp_{i-1}   else 0
 *   negative_flow_i = money_flow_i  if  tp_i < tp_{i-1}   else 0
 *   ratio = sum(positive_flow over `period`) / sum(negative_flow over `period`)
 *   MFI = 100 - (100 / (1 + ratio))
 *
 * pandas' .diff() yields NaN at index 0; tp_diff > 0 is False there, so both
 * positive_flow[0] and negative_flow[0] are 0 — matched below.
 */
class MfiIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "mfi"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 14)
        val strongBuyLevel = config.getDouble("strong_buy", 20.0)
        val buyLevel = config.getDouble("buy", 30.0)
        val sellLevel = config.getDouble("sell", 70.0)
        val strongSellLevel = config.getDouble("strong_sell", 80.0)

        if (candles.size < period + 1) {
            return result(Signal.NEUTRAL, "MFI data insufficient")
        }

        val tp = candles.map { (it.high + it.low + it.close) / 3.0 }
        val money = candles.indices.map { i -> tp[i] * candles[i].volume }
        val n = candles.size

        val positive = DoubleArray(n)
        val negative = DoubleArray(n)
        // Index 0: tp_diff is NaN -> both branches fail -> 0.0 each.
        for (i in 1 until n) {
            val diff = tp[i] - tp[i - 1]
            if (diff > 0.0) positive[i] = money[i]
            else if (diff < 0.0) negative[i] = money[i]
        }

        // Trailing-period sums at the latest bar.
        var posSum = 0.0
        var negSum = 0.0
        for (i in n - period until n) {
            posSum += positive[i]
            negSum += negative[i]
        }

        if (posSum == 0.0 && negSum == 0.0) {
            return result(Signal.NEUTRAL, "MFI data insufficient (no price movement)", mapOf("mfi" to 50.0))
        }

        val mfi = if (negSum == 0.0) {
            100.0
        } else if (posSum == 0.0) {
            0.0
        } else {
            val ratio = posSum / negSum
            100.0 - (100.0 / (1.0 + ratio))
        }
        val raw = mapOf<String, Double?>("mfi" to round2(mfi))

        return when {
            mfi <= strongBuyLevel ->
                result(Signal.STRONG_BUY, "MFI=${mfi.format(1)} strong money flow oversold", raw)
            mfi <= buyLevel ->
                result(Signal.BUY, "MFI=${mfi.format(1)} money flow oversold", raw)
            mfi >= strongSellLevel ->
                result(Signal.STRONG_SELL, "MFI=${mfi.format(1)} strong money flow overbought", raw)
            mfi >= sellLevel ->
                result(Signal.SELL, "MFI=${mfi.format(1)} money flow overbought", raw)
            else ->
                result(Signal.NEUTRAL, "MFI=${mfi.format(1)} neutral money flow", raw)
        }
    }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
}
