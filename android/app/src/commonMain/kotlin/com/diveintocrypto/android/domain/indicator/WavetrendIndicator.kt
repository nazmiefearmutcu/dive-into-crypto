package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.abs

/**
 * WaveTrend Oscillator (LazyBear). Port of the desktop engine's
 * `wavetrend.py` — same math, thresholds and signal mapping.
 *
 * Per bar t:
 *   ap  = hlc3 = (high + low + close) / 3
 *   esa = ema(ap, n1)
 *   d   = ema(abs(ap - esa), n1)
 *   ci  = (ap - esa) / (0.015 * d)     (0 when d == 0)
 *   tci = ema(ci, n2)   -> WT1
 *   wt2 = sma(wt1, signal_len)
 *
 * Crossover-centric, zone-gated signal mapping; strictly causal IIR filters
 * plus a trailing SMA — no repainting.
 */
class WavetrendIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "wavetrend"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val n1 = config.getInt("channel_length", 10)
        val n2 = config.getInt("average_length", 21)
        val signalLen = config.getInt("signal_length", 4)
        val ob1 = config.getDouble("ob_level_1", 60.0)
        val ob2 = config.getDouble("ob_level_2", 53.0)
        val os1 = config.getDouble("os_level_1", -60.0)
        val os2 = config.getDouble("os_level_2", -53.0)

        val n = candles.size
        // Need both EMAs (n1) + the tci EMA (n2) + the SMA signal (signal_len)
        // to warm up, plus one prior bar for cross detection.
        val minLen = n1 + n2 + signalLen + 1
        if (n < minLen) {
            return result(Signal.NEUTRAL, "WaveTrend data insufficient", mapOf("wt1" to null, "wt2" to null))
        }

        val ap = candles.map { (it.high + it.low + it.close) / 3.0 }  // hlc3

        val esa = Series.ewmAdjustFalse(ap, n1)
        val dev = ap.indices.map { abs(ap[it] - esa[it]) }
        val d = Series.ewmAdjustFalse(dev, n1)

        // ci = (ap - esa) / (0.015 * d); guard flat markets where d == 0.
        val ci = ap.indices.map { i ->
            val denom = 0.015 * d[i]
            if (denom == 0.0) 0.0 else (ap[i] - esa[i]) / denom
        }

        val wt1 = Series.ewmAdjustFalse(ci, n2)  // tci -> WT1
        val wt2 = Series.rollingMean(wt1, signalLen)  // signal line

        val wt1Now = wt1[n - 1]
        val wt2Now = wt2[n - 1]
        val wt1Prev = wt1[n - 2]
        val wt2Prev = wt2[n - 2]

        if (wt2Now == null || wt2Prev == null) {
            return result(Signal.NEUTRAL, "WaveTrend data insufficient", mapOf("wt1" to null, "wt2" to null))
        }

        val raw = mapOf<String, Double?>(
            "wt1" to round3(wt1Now),
            "wt2" to round3(wt2Now),
            "diff" to round3(wt1Now - wt2Now),
        )

        val bullishCross = wt1Prev <= wt2Prev && wt1Now > wt2Now
        val bearishCross = wt1Prev >= wt2Prev && wt1Now < wt2Now

        // --- Bullish cross ------------------------------------------------
        if (bullishCross) {
            if (wt1Now <= os1) {
                return result(Signal.STRONG_BUY, "WaveTrend bullish cross from deep oversold (WT1=${wt1Now.format(1)})", raw)
            }
            if (wt1Now < ob2) {
                return result(Signal.BUY, "WaveTrend bullish cross (WT1=${wt1Now.format(1)})", raw)
            }
            return result(Signal.NEUTRAL, "WaveTrend late bullish cross inside overbought (WT1=${wt1Now.format(1)})", raw)
        }

        // --- Bearish cross ------------------------------------------------
        if (bearishCross) {
            if (wt1Now >= ob1) {
                return result(Signal.STRONG_SELL, "WaveTrend bearish cross from deep overbought (WT1=${wt1Now.format(1)})", raw)
            }
            if (wt1Now > os2) {
                return result(Signal.SELL, "WaveTrend bearish cross (WT1=${wt1Now.format(1)})", raw)
            }
            return result(Signal.NEUTRAL, "WaveTrend late bearish cross inside oversold (WT1=${wt1Now.format(1)})", raw)
        }

        // --- No cross: pre-cross zone context -----------------------------
        val rising = wt1Now > wt1Prev
        val falling = wt1Now < wt1Prev
        if (wt1Now <= os1 && rising) {
            return result(Signal.BUY, "WaveTrend deep oversold turning up (WT1=${wt1Now.format(1)})", raw)
        }
        if (wt1Now >= ob1 && falling) {
            return result(Signal.SELL, "WaveTrend deep overbought turning down (WT1=${wt1Now.format(1)})", raw)
        }

        return result(Signal.NEUTRAL, "WaveTrend neutral (WT1=${wt1Now.format(1)}, WT2=${wt2Now.format(1)})", raw)
    }

    private fun round3(x: Double): Double = Math.round(x * 1000.0) / 1000.0
}
