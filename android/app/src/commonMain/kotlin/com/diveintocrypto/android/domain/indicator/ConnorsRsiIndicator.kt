package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format

/**
 * Connors RSI (CRSI). Port of the desktop engine's `connors_rsi.py` — same
 * math, thresholds and signal mapping.
 *
 *   CRSI = ( RSI(close, rsi_period)
 *          + RSI(streak, streak_period)
 *          + PercentRank(1-bar return, rank_period) ) / 3
 *
 * The RSI legs use the repo's simple rolling-mean RSI convention (warm-up ->
 * NaN, flat window -> 50, all-gains window -> 100). PercentRank counts prior
 * returns strictly below the current return. Strictly causal.
 */
class ConnorsRsiIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "connors_rsi"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val rsiPeriod = config.getInt("rsi_period", 3)
        val streakPeriod = config.getInt("streak_period", 2)
        val rankPeriod = config.getInt("rank_period", 100)
        val minRankPeriod = config.getInt("min_rank_period", 20)

        val strongBuy = config.getDouble("strong_buy", 10.0)
        val buy = config.getDouble("buy", 20.0)
        val sell = config.getDouble("sell", 80.0)
        val strongSell = config.getDouble("strong_sell", 90.0)

        // Need enough bars for the binding percentrank window plus RSI warm-ups.
        val minRows = maxOf(rsiPeriod + 1, streakPeriod + 2, minRankPeriod + 2)
        if (candles.size < minRows) {
            return result(
                Signal.NEUTRAL,
                "Connors RSI insufficient data (need >= $minRows bars)",
                mapOf("crsi" to null),
            )
        }

        val closes = candles.map { it.close }
        val n = closes.size

        // Leg 1: fast RSI of price.
        val rsiPrice = rollingRsi(closes, rsiPeriod)
        val rsiPriceLast = rsiPrice[n - 1]

        // Leg 2: RSI of the signed streak.
        val streak = streakSeries(closes)
        val rsiStreak = rollingRsi(streak, streakPeriod)
        val rsiStreakLast = rsiStreak[n - 1]

        // Leg 3: percentile rank of the latest 1-bar return.
        val returns = DoubleArray(n) { Double.NaN }
        for (i in 1 until n) returns[i] = closes[i] / closes[i - 1] - 1.0
        val (percentRank, rankWindow) = percentRankLast(returns, rankPeriod, minRankPeriod)

        if (rsiPriceLast.isNaN() || rsiStreakLast.isNaN() || percentRank == null) {
            return result(Signal.NEUTRAL, "Connors RSI component unavailable", mapOf("crsi" to null))
        }

        val crsi = (rsiPriceLast + rsiStreakLast + percentRank) / 3.0

        val raw = mapOf<String, Double?>(
            "crsi" to round2(crsi),
            "rsi_price" to round2(rsiPriceLast),
            "rsi_streak" to round2(rsiStreakLast),
            "percent_rank" to round2(percentRank),
            "rank_window" to rankWindow.toDouble(),
        )

        return when {
            crsi <= strongBuy -> result(Signal.STRONG_BUY, "Connors RSI=${crsi.format(1)} extremely oversold", raw)
            crsi <= buy -> result(Signal.BUY, "Connors RSI=${crsi.format(1)} oversold", raw)
            crsi >= strongSell -> result(Signal.STRONG_SELL, "Connors RSI=${crsi.format(1)} extremely overbought", raw)
            crsi >= sell -> result(Signal.SELL, "Connors RSI=${crsi.format(1)} overbought", raw)
            else -> result(Signal.NEUTRAL, "Connors RSI=${crsi.format(1)} neutral zone", raw)
        }
    }

    /**
     * Simple rolling-mean RSI matching the desktop repo's rsi.py conventions:
     * warm-up -> NaN, flat window -> 50.0, all-gains window -> 100.0. The
     * (undefined) first diff contributes 0 to both gain and loss, mirroring
     * pandas `delta.where(delta > 0, 0.0)` on a leading NaN.
     */
    private fun rollingRsi(values: List<Double>, period: Int): DoubleArray {
        val n = values.size
        val gain = DoubleArray(n)
        val loss = DoubleArray(n)
        for (i in 1 until n) {
            val d = values[i] - values[i - 1]
            if (d > 0.0) gain[i] = d else if (d < 0.0) loss[i] = -d
        }
        val out = DoubleArray(n) { Double.NaN }
        for (i in period - 1 until n) {
            var g = 0.0
            var l = 0.0
            for (j in i - period + 1..i) {
                g += gain[j]
                l += loss[j]
            }
            val avgGain = g / period
            val avgLoss = l / period
            out[i] = when {
                avgGain == 0.0 && avgLoss == 0.0 -> 50.0
                avgLoss == 0.0 -> 100.0
                else -> 100.0 - (100.0 / (1.0 + avgGain / avgLoss))
            }
        }
        return out
    }

    /**
     * Signed run-length of consecutive up/down closes: +1,+2,... for an up run;
     * -1,-2,... for a down run; 0 on a flat (or first) bar.
     */
    private fun streakSeries(closes: List<Double>): List<Double> {
        val out = DoubleArray(closes.size)
        var s = 0.0
        for (i in closes.indices) {
            s = if (i == 0) {
                0.0
            } else {
                val d = closes[i] - closes[i - 1]
                when {
                    d > 0.0 -> if (s > 0.0) s + 1.0 else 1.0
                    d < 0.0 -> if (s < 0.0) s - 1.0 else -1.0
                    else -> 0.0
                }
            }
            out[i] = s
        }
        return out.toList()
    }

    /**
     * PercentRank of the most recent return within the trailing prior-return
     * window: 100 * (count of prior returns strictly LESS than the current
     * return) / window size. Degrades to any window >= [minRankPeriod];
     * otherwise (null, effective window).
     */
    private fun percentRankLast(returns: DoubleArray, rankPeriod: Int, minRankPeriod: Int): Pair<Double?, Int> {
        val current = returns[returns.size - 1]
        if (current.isNaN()) return null to 0
        val prior = returns.dropLast(1).filter { !it.isNaN() }
        val eff = minOf(rankPeriod, prior.size)
        if (eff < minRankPeriod) return null to eff
        val window = prior.takeLast(eff)
        val pr = 100.0 * window.count { it < current }.toDouble() / eff.toDouble()
        return pr to eff
    }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
}
