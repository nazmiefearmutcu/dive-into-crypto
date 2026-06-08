package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.abs
import kotlin.math.max

/**
 * 1:1 port of the original Python reference implementation.
 *
 * Note: the reference Python uses *simple rolling means* for the +DM / -DM /
 * TR smoothing — NOT Wilder's smoothing. We mirror that here so the test
 * fixture matches. (`Series.wilderSmooth` is available for callers that need
 * the classic Wilder variant — e.g. ATR — but ADX/DI does not use it.)
 */
class AdxDiIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "adx_di"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 14)
        val strongTrend = config.getDouble("strong_trend", 25.0)
        val weakTrend = config.getDouble("weak_trend", 15.0)

        // Python needs `period` for ATR + `period` for ADX rolling mean over DX
        // plus the initial diff(). Be a touch conservative.
        if (candles.size < 2 * period + 2) {
            return result(Signal.NEUTRAL, "ADX/DI data insufficient")
        }

        val highs = candles.map { it.high }
        val lows = candles.map { it.low }
        val closes = candles.map { it.close }
        val n = candles.size

        // pandas .diff(): first element NaN, then [i] - [i-1].
        // Represent as Double? aligned to the input length.
        val highDiff = DoubleArray(n).also {
            for (i in 1 until n) it[i] = highs[i] - highs[i - 1]
        }
        val lowDiff = DoubleArray(n).also {
            for (i in 1 until n) it[i] = lows[i] - lows[i - 1]
        }

        // plus_dm = high.diff().where((plus_dm > minus_dm) & (plus_dm > 0), 0.0)
        // minus_dm = (-low.diff()).where((minus_dm > plus_dm) & (minus_dm > 0), 0.0)
        val plusDm = DoubleArray(n)
        val minusDm = DoubleArray(n)
        // Index 0: pandas diff yields NaN -> Python's `where` keeps the NaN.
        // For our purposes we treat index 0 as 0.0 — the rolling-mean window
        // (size = period) won't be full until index `period - 1` so this
        // doesn't affect the final reading.
        for (i in 1 until n) {
            val pdm = highDiff[i]
            val mdm = -lowDiff[i]
            plusDm[i] = if (pdm > mdm && pdm > 0.0) pdm else 0.0
            minusDm[i] = if (mdm > pdm && mdm > 0.0) mdm else 0.0
        }

        // True Range
        // tr1 = high - low
        // tr2 = |high - close.shift(1)|
        // tr3 = |low - close.shift(1)|
        // tr = max(tr1, tr2, tr3) row-wise
        // close.shift(1) yields NaN at index 0 — pandas .max(axis=1) skips NaN,
        // so at i=0 tr = tr1.
        val tr = DoubleArray(n)
        tr[0] = highs[0] - lows[0]
        for (i in 1 until n) {
            val prevClose = closes[i - 1]
            val tr1 = highs[i] - lows[i]
            val tr2 = abs(highs[i] - prevClose)
            val tr3 = abs(lows[i] - prevClose)
            tr[i] = max(tr1, max(tr2, tr3))
        }

        // Simple rolling means (period window), pandas semantics: null until
        // the window is full.
        val atrList = Series.rollingMean(tr.toList(), period)
        val plusDmMa = Series.rollingMean(plusDm.toList(), period)
        val minusDmMa = Series.rollingMean(minusDm.toList(), period)

        // plus_di / minus_di per index — null where atr is null/zero or any
        // dependency is null. We compute the full series so we can then run a
        // second rolling mean over DX to get ADX.
        val plusDi = DoubleArray(n) { Double.NaN }
        val minusDi = DoubleArray(n) { Double.NaN }
        val dx = DoubleArray(n) { Double.NaN }
        for (i in 0 until n) {
            val atrVal = atrList[i] ?: continue
            if (atrVal == 0.0) continue // pandas .replace(0, NaN)
            val pDmMa = plusDmMa[i] ?: continue
            val mDmMa = minusDmMa[i] ?: continue
            val pdi = 100.0 * pDmMa / atrVal
            val mdi = 100.0 * mDmMa / atrVal
            plusDi[i] = pdi
            minusDi[i] = mdi
            val diSum = pdi + mdi
            if (diSum == 0.0) continue // pandas .replace(0, NaN)
            dx[i] = 100.0 * abs(pdi - mdi) / diSum
        }

        // ADX = rolling mean of DX (skipping NaN windows -> null)
        val adxOut = DoubleArray(n) { Double.NaN }
        for (i in 0 until n) {
            if (i + 1 < period) continue
            val window = dx.sliceArray(i + 1 - period until i + 1)
            if (window.any { it.isNaN() }) continue
            adxOut[i] = window.average()
        }

        val curAdx = adxOut.last()
        val curPlusDi = plusDi.last()
        val curMinusDi = minusDi.last()

        if (curAdx.isNaN() || curPlusDi.isNaN() || curMinusDi.isNaN()) {
            return result(Signal.NEUTRAL, "ADX/DI data insufficient")
        }

        val raw = mapOf<String, Double?>(
            "adx" to round2(curAdx),
            "plus_di" to round2(curPlusDi),
            "minus_di" to round2(curMinusDi),
        )

        if (curAdx < weakTrend) {
            return result(
                Signal.NEUTRAL,
                "ADX=${curAdx.format(1)} weak trend - no directional conviction",
                raw,
            )
        }

        val isStrong = curAdx >= strongTrend

        return when {
            curPlusDi > curMinusDi -> {
                val msg = if (isStrong) "ADX=${curAdx.format(1)} strong bullish trend (+DI>-DI)"
                else "ADX=${curAdx.format(1)} bullish trend (+DI>-DI)"
                result(if (isStrong) Signal.STRONG_BUY else Signal.BUY, msg, raw)
            }
            curMinusDi > curPlusDi -> {
                val msg = if (isStrong) "ADX=${curAdx.format(1)} strong bearish trend (-DI>+DI)"
                else "ADX=${curAdx.format(1)} bearish trend (-DI>+DI)"
                result(if (isStrong) Signal.STRONG_SELL else Signal.SELL, msg, raw)
            }
            else -> result(
                Signal.NEUTRAL,
                "ADX=${curAdx.format(1)} DI lines converging",
                raw,
            )
        }
    }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
}
