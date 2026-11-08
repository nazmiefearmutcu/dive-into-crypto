package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.abs

/**
 * OBV (On-Balance Volume) — verbatim port of
 * the original Python reference implementation.
 *
 * Direction per candle: +1 if close > prev_close, -1 if close < prev_close,
 * 0 otherwise. Pandas .diff() puts NaN at index 0 → both > and < comparisons
 * are False → direction=0 at index 0. Cumulative sum of (volume * direction).
 */
class ObvIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "obv"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val smaPeriod = config.getInt("sma_period", 20)
        val divergenceLookback = config.getInt("divergence_lookback", 10)

        val n = candles.size
        if (n < 2 || n < smaPeriod) {
            return result(Signal.NEUTRAL, "OBV data insufficient")
        }

        val closes = candles.map { it.close }
        val volumes = candles.map { it.volume }

        // direction: index 0 is NaN-diff, so direction = 0
        val obv = DoubleArray(n)
        obv[0] = 0.0  // volume * 0 = 0
        for (i in 1 until n) {
            val dir = when {
                closes[i] > closes[i - 1] -> 1.0
                closes[i] < closes[i - 1] -> -1.0
                else -> 0.0
            }
            obv[i] = obv[i - 1] + volumes[i] * dir
        }

        val obvList = obv.toList()
        val obvSmaSeries = Series.rollingMean(obvList, smaPeriod)
        val currentObv = obvList.last()
        val currentObvSma = obvSmaSeries.last() ?: return result(Signal.NEUTRAL, "OBV data insufficient")

        val lookback = minOf(divergenceLookback, n - 1)
        val baseClose = closes[n - 1 - lookback]
        val baseObv = obvList[n - 1 - lookback]
        val priceChange = if (baseClose != 0.0) (closes[n - 1] - baseClose) / baseClose else 0.0
        val obvChange = currentObv - baseObv
        // Python: obv_change / volume.iloc[-lookback:].sum(). The window's traded volume
        // is the scale, not |base OBV| (a cumulative sum unrelated to the window).
        // lookback == 0 selects the whole series, as iloc[-0:] does in pandas.
        val volStart = if (lookback > 0) n - lookback else 0
        var windowVolume = 0.0
        for (i in volStart until n) windowVolume += volumes[i]
        val obvChangePct = if (windowVolume != 0.0) obvChange / windowVolume else 0.0

        val obvTrendStr = if (currentObv > currentObvSma) "UP" else "DOWN"
        val raw = mapOf<String, Double?>(
            "obv" to round2(currentObv),
            "obv_sma" to round2(currentObvSma),
            "price_change_pct" to round2(priceChange * 100.0),
            // "obv_trend" is a string in Python; tests only check numeric raws.
        )

        // Bearish divergence: price up but OBV down
        if (priceChange > 0.01 && obvChangePct < -0.05) {
            return result(
                Signal.SELL,
                "OBV bearish divergence: price up ${(priceChange * 100.0).format(1)}% but OBV declining",
                raw,
            )
        }
        // Bullish divergence: price down but OBV up
        if (priceChange < -0.01 && obvChangePct > 0.05) {
            return result(
                Signal.BUY,
                "OBV bullish divergence: price down ${(priceChange * 100.0).format(1)}% but OBV rising",
                raw,
            )
        }

        return when {
            currentObv > currentObvSma -> {
                val strength = if (currentObvSma != 0.0)
                    (currentObv - currentObvSma) / abs(currentObvSma) else 0.0
                if (strength > 0.1)
                    result(Signal.BUY, "OBV strongly above SMA, volume confirms uptrend", raw)
                else
                    result(Signal.BUY, "OBV above SMA, volume supports upward move", raw)
            }
            currentObv < currentObvSma -> {
                val strength = if (currentObvSma != 0.0)
                    (currentObvSma - currentObv) / abs(currentObvSma) else 0.0
                if (strength > 0.1)
                    result(Signal.SELL, "OBV strongly below SMA, volume confirms downtrend", raw)
                else
                    result(Signal.SELL, "OBV below SMA, volume supports downward move", raw)
            }
            else -> result(Signal.NEUTRAL, "OBV flat / no volume confirmation", raw)
        }
    }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
}
