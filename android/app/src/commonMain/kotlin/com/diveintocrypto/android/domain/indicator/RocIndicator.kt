package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format

/**
 * 1:1 port of the original Python reference implementation.
 *
 * ROC = ((close - close.shift(period)) / close.shift(period)) * 100.
 * Signal also looks at whether momentum is increasing or decreasing
 * compared to the previous bar's ROC.
 */
class RocIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "roc"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 12)
        val strongThreshold = config.getDouble("strong_threshold", 5.0)
        val weakThreshold = config.getDouble("weak_threshold", 1.0)

        // Need at least period + 2 to have curRoc AND prevRoc both defined.
        if (candles.size < period + 2) {
            return result(Signal.NEUTRAL, "ROC data insufficient")
        }

        val closes = candles.map { it.close }
        val n = closes.size
        val curIdx = n - 1
        val prevIdx = n - 2

        val curBase = closes[curIdx - period]
        val prevBase = closes[prevIdx - period]
        if (curBase == 0.0) {
            return result(Signal.NEUTRAL, "ROC data insufficient")
        }

        val curRoc = (closes[curIdx] - curBase) / curBase * 100.0
        val prevRoc = if (prevBase == 0.0) curRoc
        else (closes[prevIdx] - prevBase) / prevBase * 100.0

        val raw = mapOf<String, Double?>(
            "roc" to round2(curRoc),
            "roc_prev" to round2(prevRoc),
        )

        val momentumIncreasing = curRoc > prevRoc
        val momentumDecreasing = curRoc < prevRoc

        return when {
            curRoc > strongThreshold && momentumIncreasing ->
                result(
                    Signal.STRONG_BUY,
                    "ROC=${curRoc.format(2)}% strong positive momentum rising",
                    raw,
                )
            curRoc > weakThreshold && momentumIncreasing ->
                result(
                    Signal.BUY,
                    "ROC=${curRoc.format(2)}% positive momentum rising",
                    raw,
                )
            curRoc > weakThreshold ->
                result(
                    Signal.BUY,
                    "ROC=${curRoc.format(2)}% positive momentum",
                    raw,
                )
            curRoc < -strongThreshold && momentumDecreasing ->
                result(
                    Signal.STRONG_SELL,
                    "ROC=${curRoc.format(2)}% strong negative momentum falling",
                    raw,
                )
            curRoc < -weakThreshold && momentumDecreasing ->
                result(
                    Signal.SELL,
                    "ROC=${curRoc.format(2)}% negative momentum falling",
                    raw,
                )
            curRoc < -weakThreshold ->
                result(
                    Signal.SELL,
                    "ROC=${curRoc.format(2)}% negative momentum",
                    raw,
                )
            else ->
                result(
                    Signal.NEUTRAL,
                    "ROC=${curRoc.format(2)}% weak/flat momentum",
                    raw,
                )
        }
    }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
}
