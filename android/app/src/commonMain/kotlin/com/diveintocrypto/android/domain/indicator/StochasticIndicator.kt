package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format

class StochasticIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "stochastic"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val kPeriod = config.getInt("k_period", 14)
        val dPeriod = config.getInt("d_period", 3)
        val oversold = config.getDouble("oversold", 20.0)
        val overbought = config.getDouble("overbought", 80.0)

        if (candles.size < kPeriod + dPeriod) return result(Signal.NEUTRAL, "Stochastic data insufficient")

        val highs = candles.map { it.high }
        val lows = candles.map { it.low }
        val closes = candles.map { it.close }

        val lowestLow = Series.rollingMin(lows, kPeriod)
        val highestHigh = Series.rollingMax(highs, kPeriod)

        val kLine: List<Double?> = closes.indices.map { i ->
            val ll = lowestLow[i] ?: return@map null
            val hh = highestHigh[i] ?: return@map null
            val denom = hh - ll
            if (denom == 0.0) null
            else 100.0 * (closes[i] - ll) / denom
        }

        val kValues = kLine.map { it ?: Double.NaN }
        val dLine = kValues.indices.map { i ->
            if (i + 1 < dPeriod) null
            else {
                val w = kValues.subList(i + 1 - dPeriod, i + 1)
                if (w.any { it.isNaN() }) null else w.average()
            }
        }

        val curK = kValues.last()
        val curD = dLine.last()
        if (curK.isNaN() || curD == null) return result(Signal.NEUTRAL, "Stochastic data insufficient")

        val prevK = kValues.dropLast(1).lastOrNull { !it.isNaN() } ?: curK
        val prevD = dLine.dropLast(1).lastOrNull { it != null } ?: curD

        val raw = mapOf<String, Double?>(
            "k" to round2(curK),
            "d" to round2(curD),
        )

        // Oversold reversal
        if (prevK < oversold && curK > prevK && curK > curD) {
            if (curK < oversold * 0.7) {
                return result(Signal.STRONG_BUY, "Stochastic deeply oversold reversal K=${curK.format(1)}", raw)
            }
            return result(Signal.BUY, "Stochastic oversold reversal K=${curK.format(1)}", raw)
        }

        // Overbought reversal
        if (prevK > overbought && curK < prevK && curK < curD) {
            if (curK > overbought + (100 - overbought) * 0.3) {
                return result(Signal.STRONG_SELL, "Stochastic deeply overbought reversal K=${curK.format(1)}", raw)
            }
            return result(Signal.SELL, "Stochastic overbought reversal K=${curK.format(1)}", raw)
        }

        if (curK < oversold && curK > prevK)
            return result(Signal.BUY, "Stochastic in oversold zone, turning up K=${curK.format(1)}", raw)

        if (curK > overbought && curK < prevK)
            return result(Signal.SELL, "Stochastic in overbought zone, turning down K=${curK.format(1)}", raw)

        return result(Signal.NEUTRAL, "Stochastic neutral K=${curK.format(1)} D=${curD.format(1)}", raw)
    }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
}
