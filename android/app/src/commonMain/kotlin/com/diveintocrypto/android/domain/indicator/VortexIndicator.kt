package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import kotlin.math.abs

/** Vortex Indicator (VI). */
class VortexIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "vortex"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 14)

        val n = candles.size
        if (n == 0) {
            return result(Signal.NEUTRAL, "Vortex data insufficient")
        }

        val highs = candles.map { it.high }
        val lows = candles.map { it.low }
        val closes = candles.map { it.close }

        // True Range (row-wise max skips the missing prev close at i == 0, like pandas)
        val tr: List<Double?> = candles.indices.map { i ->
            if (i == 0) highs[0] - lows[0]
            else maxOf(highs[i] - lows[i], abs(highs[i] - closes[i - 1]), abs(lows[i] - closes[i - 1]))
        }

        // Vortex movements (undefined at i == 0, like pandas shift())
        val vmPlus: List<Double?> = candles.indices.map { i ->
            if (i == 0) null else abs(highs[i] - lows[i - 1])
        }
        val vmMinus: List<Double?> = candles.indices.map { i ->
            if (i == 0) null else abs(lows[i] - highs[i - 1])
        }

        val sumTr = rollingSum(tr, period)
        val sumVmPlus = rollingSum(vmPlus, period)
        val sumVmMinus = rollingSum(vmMinus, period)

        val viPlus: List<Double?> = candles.indices.map { i ->
            val s = sumTr[i]
            val p = sumVmPlus[i]
            if (s == null || p == null) null else p / (s + 1e-10)
        }
        val viMinus: List<Double?> = candles.indices.map { i ->
            val s = sumTr[i]
            val m = sumVmMinus[i]
            if (s == null || m == null) null else m / (s + 1e-10)
        }

        val currPlus = viPlus.last()
            ?: return result(Signal.NEUTRAL, "Vortex data insufficient")
        val currMinus = viMinus.last()
            ?: return result(Signal.NEUTRAL, "Vortex data insufficient")
        val prevPlus = if (n >= 2) viPlus[n - 2] else currPlus
        val prevMinus = if (n >= 2) viMinus[n - 2] else currMinus

        val raw = mapOf<String, Double?>(
            "vi_plus" to round4(currPlus),
            "vi_minus" to round4(currMinus),
        )

        // null prev behaves like pandas NaN: comparisons are false
        val bullishCross = prevPlus != null && prevMinus != null && prevPlus <= prevMinus && currPlus > currMinus
        val bearishCross = prevPlus != null && prevMinus != null && prevPlus >= prevMinus && currPlus < currMinus

        return when {
            bullishCross -> result(Signal.STRONG_BUY, "Vortex bullish crossover", raw)
            bearishCross -> result(Signal.STRONG_SELL, "Vortex bearish crossover", raw)
            currPlus > currMinus -> result(Signal.BUY, "Vortex is bullish", raw)
            currPlus < currMinus -> result(Signal.SELL, "Vortex is bearish", raw)
            else -> result(Signal.NEUTRAL, "Vortex neutral", raw)
        }
    }

    /** Rolling sum with pandas semantics: null while the window is not full or contains a null. */
    private fun rollingSum(values: List<Double?>, window: Int): List<Double?> =
        values.indices.map { i ->
            if (i + 1 < window) null
            else {
                val w = values.subList(i + 1 - window, i + 1)
                if (w.any { it == null }) null else w.sumOf { it!! }
            }
        }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
}
