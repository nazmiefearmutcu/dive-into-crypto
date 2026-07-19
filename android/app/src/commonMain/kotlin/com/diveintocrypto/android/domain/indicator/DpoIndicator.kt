package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.sqrt

/**
 * DPO (Detrended Price Oscillator). Port of the desktop engine's `dpo.py` —
 * same math, thresholds and signal mapping.
 *
 * Causal / look-ahead-free (non-centered) formulation:
 *
 *   DPO[t] = close[t] - SMA_N(close)[t - displacement]
 *   displacement = N / 2 + 1
 *
 * Raw DPO (price units) is standardized by its own causal rolling mean/std
 * (population, ddof=0) into a z-score; the 5-level signal is a mean-reversion
 * cycle mapping with STRONG levels gated on reversion toward zero.
 */
class DpoIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "dpo"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 20)
        // displacement defaults to the classic N/2 + 1; allow explicit override
        val displacement = config.getInt("displacement", period / 2 + 1)
        val lookback = config.getInt("zscore_lookback", 100)
        val minObs = config.getInt("min_obs", 20)
        val strongZ = config.getDouble("strong_z", 2.0)
        val weakZ = config.getDouble("weak_z", 1.0)

        if (period < 2 || displacement < 1) {
            return result(Signal.NEUTRAL, "DPO invalid params")
        }

        // Need at least one DPO value plus a couple to compare direction.
        val minRows = period + displacement + 2
        if (candles.size < minRows) {
            return result(Signal.NEUTRAL, "DPO data insufficient (${candles.size}<$minRows)")
        }

        val closes = candles.map { it.close }
        val n = closes.size

        // Causal detrended price oscillator: price minus displaced SMA.
        // sma[i] over [i-period+1, i]; displaced by +displacement (past values only).
        val dpo = arrayOfNulls<Double>(n)
        for (i in 0 until n) {
            val smaEnd = i - displacement
            if (smaEnd + 1 < period) continue
            var sum = 0.0
            for (j in smaEnd - period + 1..smaEnd) sum += closes[j]
            dpo[i] = closes[i] - sum / period
        }

        val currentDpo = dpo[n - 1]
        val currentClose = closes[n - 1]
        if (currentDpo == null || currentClose == 0.0) {
            return result(Signal.NEUTRAL, "DPO data insufficient")
        }

        // Causal rolling standardization (window ends at the current bar).
        val curStats = rollingStats(dpo, lookback, minObs, n - 1)

        val dpoPct = 100.0 * currentDpo / currentClose

        // Not enough dispersion / observations to standardize -> no cycle signal.
        if (curStats == null || curStats.second <= 1e-12) {
            val flatRaw = mapOf<String, Double?>(
                "dpo" to round6(currentDpo),
                "dpo_pct" to round4(dpoPct),
                "z" to 0.0,
                "period" to period.toDouble(),
                "displacement" to displacement.toDouble(),
            )
            return result(Signal.NEUTRAL, "DPO flat / no cycle dispersion", flatRaw)
        }

        val z = (currentDpo - curStats.first) / curStats.second

        // Previous bar z (same causal stats) for reversion confirmation.
        val prevDpo = dpo[n - 2]
        val prevStats = rollingStats(dpo, lookback, minObs, n - 2)
        val zPrev = if (prevDpo == null || prevStats == null || prevStats.second <= 1e-12) {
            z
        } else {
            (prevDpo - prevStats.first) / prevStats.second
        }

        val raw = mapOf<String, Double?>(
            "dpo" to round6(currentDpo),
            "dpo_pct" to round4(dpoPct),
            "z" to round3(z),
            "z_prev" to round3(zPrev),
            "period" to period.toDouble(),
            "displacement" to displacement.toDouble(),
        )

        // Reversion toward the zero line = a cycle extreme that has formed and
        // is starting to unwind (mean-reversion trigger, avoids knife-catching).
        val revertingUp = z < 0 && z > zPrev      // deep trough curling back up
        val revertingDown = z > 0 && z < zPrev    // extended peak rolling over

        return when {
            z <= -strongZ && revertingUp -> result(
                Signal.STRONG_BUY,
                "DPO z=${z.format(2)} deep cycle trough turning up (${dpoPct.format(2, plus = true)}% vs detrended baseline)",
                raw,
            )
            z >= strongZ && revertingDown -> result(
                Signal.STRONG_SELL,
                "DPO z=${z.format(2)} cycle peak rolling over (${dpoPct.format(2, plus = true)}% vs detrended baseline)",
                raw,
            )
            z <= -weakZ -> result(
                Signal.BUY,
                "DPO z=${z.format(2)} below detrended baseline (${dpoPct.format(2, plus = true)}%)",
                raw,
            )
            z >= weakZ -> result(
                Signal.SELL,
                "DPO z=${z.format(2)} above detrended baseline (${dpoPct.format(2, plus = true)}%)",
                raw,
            )
            else -> result(
                Signal.NEUTRAL,
                "DPO z=${z.format(2)} within cycle band (${dpoPct.format(2, plus = true)}%)",
                raw,
            )
        }
    }

    /**
     * Mean and population std (ddof=0) of the non-null values inside the
     * trailing [window] ending at [end]; null when fewer than [minPeriods]
     * observations (pandas rolling(window, min_periods=minPeriods)).
     */
    private fun rollingStats(
        values: Array<Double?>,
        window: Int,
        minPeriods: Int,
        end: Int,
    ): Pair<Double, Double>? {
        val start = maxOf(0, end - window + 1)
        var count = 0
        var sum = 0.0
        for (i in start..end) {
            val v = values[i] ?: continue
            count++
            sum += v
        }
        if (count < minPeriods) return null
        val mean = sum / count
        var sumSq = 0.0
        for (i in start..end) {
            val v = values[i] ?: continue
            val diff = v - mean
            sumSq += diff * diff
        }
        return mean to sqrt(sumSq / count)
    }

    private fun round3(x: Double): Double = Math.round(x * 1000.0) / 1000.0
    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
    private fun round6(x: Double): Double = Math.round(x * 1000000.0) / 1000000.0
}
