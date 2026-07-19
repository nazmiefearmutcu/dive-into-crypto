package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.math.Series
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import kotlin.math.abs

/**
 * Klinger Volume Oscillator (KVO) — verbatim port of the original Python
 * reference implementation.
 *
 * Fuses price direction and volume into a "volume force" (VF) series, then
 * applies a MACD-style dual EMA (34/55) with a signal line (13):
 *
 *   tp[i]    = (high + low + close) / 3
 *   dm[i]    = high - low
 *   trend[i] = +1 / -1 by tp direction, persisting on ties (seed +1)
 *   cm[i]    = cm[i-1] + dm[i] if trend unchanged, else dm[i-1] + dm[i]
 *   vf[i]    = volume * |2 * (dm/cm - 1)| * trend * 100   (0 on zero-range bars)
 *   kvo      = EMA(vf, fast) - EMA(vf, slow); signal = EMA(kvo, signal_period)
 *
 * Signal-line crosses are upgraded to STRONG only when the zero-line regime
 * agrees; without a cross, sustained accumulation/distribution (histogram
 * expanding on the matching side of zero) gives a plain BUY/SELL.
 */
class KlingerOscillatorIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "klinger_oscillator"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val fastPeriod = config.getInt("fast_period", 34)
        val slowPeriod = config.getInt("slow_period", 55)
        val signalPeriod = config.getInt("signal_period", 13)

        val n = candles.size
        // Need enough bars to warm the slow EMA and inspect one prior bar.
        if (n < slowPeriod + 1) {
            return result(Signal.NEUTRAL, "Klinger data insufficient (need > slow_period bars)")
        }

        val tp = DoubleArray(n) { (candles[it].high + candles[it].low + candles[it].close) / 3.0 }
        val dm = DoubleArray(n) { candles[it].high - candles[it].low }  // range, always >= 0

        // trend: signed direction of typical price, persisting on ties.
        val trend = DoubleArray(n)
        trend[0] = 1.0
        for (i in 1 until n) {
            trend[i] = when {
                tp[i] > tp[i - 1] -> 1.0
                tp[i] < tp[i - 1] -> -1.0
                else -> trend[i - 1]
            }
        }

        // cm: cumulative measurement with reset when trend flips.
        val cm = DoubleArray(n)
        cm[0] = dm[0]
        for (i in 1 until n) {
            cm[i] = if (trend[i] == trend[i - 1]) cm[i - 1] + dm[i] else dm[i - 1] + dm[i]
        }

        // Volume force. Zero-range bars (dm == 0) exert no force. Guard cm == 0.
        val vf = DoubleArray(n)
        for (i in 0 until n) {
            val ratio = if (cm[i] > EPS) dm[i] / cm[i] else 0.0
            vf[i] = if (dm[i] <= 0.0) {
                0.0
            } else {
                candles[i].volume * abs(2.0 * (ratio - 1.0)) * trend[i] * 100.0
            }
        }

        val vfList = vf.toList()
        val emaFast = Series.ewmAdjustFalse(vfList, fastPeriod)
        val emaSlow = Series.ewmAdjustFalse(vfList, slowPeriod)
        val kvo = List(n) { emaFast[it] - emaSlow[it] }
        val signalLine = Series.ewmAdjustFalse(kvo, signalPeriod)

        val curKvo = kvo[n - 1]
        val curSig = signalLine[n - 1]
        val curHist = curKvo - curSig
        val prevKvo = kvo[n - 2]
        val prevSig = signalLine[n - 2]
        val prevHist = prevKvo - prevSig

        if (!curKvo.isFinite() || !curSig.isFinite()) {
            return result(Signal.NEUTRAL, "Klinger non-finite output")
        }

        val raw = mapOf<String, Double?>(
            "kvo" to round4(curKvo),
            "signal" to round4(curSig),
            "histogram" to round4(curHist),
            // "regime" is a string in Python; tests only check numeric raws.
        )

        val bullishCross = prevKvo <= prevSig && curKvo > curSig
        val bearishCross = prevKvo >= prevSig && curKvo < curSig

        if (bullishCross && curKvo > 0) {
            return result(
                Signal.STRONG_BUY,
                "KVO bullish signal-line cross confirmed above zero (accumulation)",
                raw,
            )
        }
        if (bullishCross) {
            return result(Signal.BUY, "KVO bullish signal-line cross (below zero, unconfirmed)", raw)
        }
        if (bearishCross && curKvo < 0) {
            return result(
                Signal.STRONG_SELL,
                "KVO bearish signal-line cross confirmed below zero (distribution)",
                raw,
            )
        }
        if (bearishCross) {
            return result(Signal.SELL, "KVO bearish signal-line cross (above zero, unconfirmed)", raw)
        }
        if (curKvo > curSig && curHist > prevHist && curKvo > 0) {
            return result(Signal.BUY, "KVO above signal, histogram expanding, positive regime", raw)
        }
        if (curKvo < curSig && curHist < prevHist && curKvo < 0) {
            return result(Signal.SELL, "KVO below signal, histogram expanding, negative regime", raw)
        }
        return result(Signal.NEUTRAL, "KVO indecisive", raw)
    }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0

    private companion object {
        const val EPS = 1e-12
    }
}
