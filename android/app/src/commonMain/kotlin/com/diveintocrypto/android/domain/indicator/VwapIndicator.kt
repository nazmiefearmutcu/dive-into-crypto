package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal

/** Rolling Volume Weighted Average Price (VWAP) indicator. */
class VwapIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "vwap"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 20)

        val closes = candles.map { it.close }
        val tpV = candles.map { ((it.high + it.low + it.close) / 3) * it.volume }
        val volume = candles.map { it.volume }

        val rollingTpV = rollingSum(tpV, period)
        val rollingV = rollingSum(volume, period)

        val vwap: List<Double?> = candles.indices.map { i ->
            val t = rollingTpV[i]
            val v = rollingV[i]
            if (t == null || v == null) null else t / (v + 1e-10)
        }

        val currentVwap = vwap.lastOrNull()
            ?: return result(Signal.NEUTRAL, "VWAP data insufficient")
        val currentClose = closes.last()
        val prevClose = if (closes.size >= 2) closes[closes.size - 2] else currentClose
        val prevVwap = if (vwap.size >= 2) vwap[vwap.size - 2] else currentVwap

        val raw = mapOf<String, Double?>("vwap" to round4(currentVwap))

        // null prev behaves like pandas NaN: comparisons are false
        val bullishCross = prevVwap != null && prevClose <= prevVwap && currentClose > currentVwap
        val bearishCross = prevVwap != null && prevClose >= prevVwap && currentClose < currentVwap

        val distancePct = (currentClose - currentVwap) / currentVwap

        return when {
            bullishCross -> result(Signal.STRONG_BUY, "Price crossed above VWAP", raw)
            bearishCross -> result(Signal.STRONG_SELL, "Price crossed below VWAP", raw)
            distancePct > 0.05 -> result(Signal.NEUTRAL, "Price too far above VWAP (overextended)", raw)
            distancePct < -0.05 -> result(Signal.NEUTRAL, "Price too far below VWAP (overextended)", raw)
            currentClose > currentVwap -> result(Signal.BUY, "Price above VWAP", raw)
            currentClose < currentVwap -> result(Signal.SELL, "Price below VWAP", raw)
            else -> result(Signal.NEUTRAL, "Price at VWAP", raw)
        }
    }

    private fun rollingSum(values: List<Double>, window: Int): List<Double?> =
        values.indices.map { i ->
            if (i + 1 < window) null else values.subList(i + 1 - window, i + 1).sum()
        }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
}
