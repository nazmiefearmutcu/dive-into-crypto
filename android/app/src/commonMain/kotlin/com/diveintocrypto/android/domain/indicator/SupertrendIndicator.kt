package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import kotlin.math.abs

class SupertrendIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "supertrend"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 10)
        val multiplier = config.getDouble("multiplier", 3.0)

        val n = candles.size
        if (n < period) {
            return result(Signal.NEUTRAL, "Supertrend data insufficient")
        }

        val high = DoubleArray(n) { candles[it].high }
        val low = DoubleArray(n) { candles[it].low }
        val close = DoubleArray(n) { candles[it].close }

        // True Range (tr[0] stays 0.0, exactly as in the Python source)
        val tr = DoubleArray(n)
        for (i in 1 until n) {
            val tr1 = high[i] - low[i]
            val tr2 = abs(high[i] - close[i - 1])
            val tr3 = abs(low[i] - close[i - 1])
            tr[i] = maxOf(tr1, tr2, tr3)
        }

        // ATR: seed = mean(tr[1:period+1]) (numpy slice clamps at n), then RMA (SMMA)
        val atr = DoubleArray(n)
        val seedEnd = minOf(period + 1, n)
        atr[period - 1] = tr.slice(1 until seedEnd).average()
        for (i in period until n) {
            atr[i] = (atr[i - 1] * (period - 1) + tr[i]) / period
        }

        val finalUpper = DoubleArray(n) { Double.NaN }
        val finalLower = DoubleArray(n) { Double.NaN }
        val supertrend = DoubleArray(n) { Double.NaN }
        val direction = DoubleArray(n) { 1.0 } // 1 for bull, -1 for bear

        for (i in period until n) {
            val hl2 = (high[i] + low[i]) / 2
            val basicUpper = hl2 + multiplier * atr[i]
            val basicLower = hl2 - multiplier * atr[i]

            // Final Upper Band
            finalUpper[i] =
                if (finalUpper[i - 1].isNaN() || basicUpper < finalUpper[i - 1] || close[i - 1] > finalUpper[i - 1]) {
                    basicUpper
                } else {
                    finalUpper[i - 1]
                }

            // Final Lower Band
            finalLower[i] =
                if (finalLower[i - 1].isNaN() || basicLower > finalLower[i - 1] || close[i - 1] < finalLower[i - 1]) {
                    basicLower
                } else {
                    finalLower[i - 1]
                }

            // Supertrend direction
            if (supertrend[i - 1].isNaN()) {
                if (close[i] <= finalUpper[i]) {
                    direction[i] = -1.0
                    supertrend[i] = finalUpper[i]
                } else {
                    direction[i] = 1.0
                    supertrend[i] = finalLower[i]
                }
            } else {
                if (direction[i - 1] == 1.0 && close[i] < finalLower[i]) {
                    direction[i] = -1.0
                    supertrend[i] = finalUpper[i]
                } else if (direction[i - 1] == -1.0 && close[i] > finalUpper[i]) {
                    direction[i] = 1.0
                    supertrend[i] = finalLower[i]
                } else {
                    direction[i] = direction[i - 1]
                    supertrend[i] = if (direction[i] == 1.0) finalLower[i] else finalUpper[i]
                }
            }
        }

        val currentDirection = direction[n - 1]
        val prevDirection = if (n >= 2) direction[n - 2] else currentDirection
        val currentSt = supertrend[n - 1]

        if (currentSt.isNaN()) {
            return result(Signal.NEUTRAL, "Supertrend data insufficient")
        }

        val raw = mapOf<String, Double?>(
            "supertrend" to round4(currentSt),
            // Python emits the string "BULL"/"BEAR"; encoded numerically: 1.0 = BULL, -1.0 = BEAR
            "direction" to currentDirection,
        )

        return when {
            currentDirection == 1.0 && prevDirection == -1.0 ->
                result(Signal.STRONG_BUY, "Supertrend turned bullish", raw)
            currentDirection == -1.0 && prevDirection == 1.0 ->
                result(Signal.STRONG_SELL, "Supertrend turned bearish", raw)
            currentDirection == 1.0 -> result(Signal.BUY, "Supertrend is bullish", raw)
            currentDirection == -1.0 -> result(Signal.SELL, "Supertrend is bearish", raw)
            else -> result(Signal.NEUTRAL, "Supertrend neutral", raw)
        }
    }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
}
