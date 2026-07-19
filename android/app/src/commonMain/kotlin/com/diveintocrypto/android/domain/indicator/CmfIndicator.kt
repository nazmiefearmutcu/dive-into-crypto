package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal

class CmfIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "cmf"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 20)
        val strongBuy = config.getDouble("strong_buy", 0.2)
        val buy = config.getDouble("buy", 0.05)
        val strongSell = config.getDouble("strong_sell", -0.2)
        val sell = config.getDouble("sell", -0.05)

        // Money Flow Volume = Money Flow Multiplier * volume
        val mfv = candles.map {
            val mfm = ((it.close - it.low) - (it.high - it.close)) / (it.high - it.low + 1e-10)
            mfm * it.volume
        }
        val volume = candles.map { it.volume }

        val mfvSum = rollingSum(mfv, period)
        val volSum = rollingSum(volume, period)

        // CMF
        val cmf: List<Double?> = candles.indices.map { i ->
            val a = mfvSum[i]
            val b = volSum[i]
            if (a == null || b == null) null else a / b
        }

        val currentCmf = cmf.lastOrNull()
            ?: return result(Signal.NEUTRAL, "CMF data insufficient")

        val raw = mapOf<String, Double?>("cmf" to round4(currentCmf))

        return when {
            currentCmf >= strongBuy -> result(Signal.STRONG_BUY, "CMF highly positive (> $strongBuy)", raw)
            currentCmf >= buy -> result(Signal.BUY, "CMF positive (> $buy)", raw)
            currentCmf <= strongSell -> result(Signal.STRONG_SELL, "CMF highly negative (< $strongSell)", raw)
            currentCmf <= sell -> result(Signal.SELL, "CMF negative (< $sell)", raw)
            else -> result(Signal.NEUTRAL, "CMF neutral", raw)
        }
    }

    private fun rollingSum(values: List<Double>, window: Int): List<Double?> =
        values.indices.map { i ->
            if (i + 1 < window) null else values.subList(i + 1 - window, i + 1).sum()
        }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
}
