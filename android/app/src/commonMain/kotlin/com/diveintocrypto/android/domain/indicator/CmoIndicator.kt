package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format

/**
 * 1:1 port of the Python desktop implementation (cmo.py).
 *
 * Chande Momentum Oscillator (CMO) — momentum-trend framing.
 * CMO = 100 * (sumUp - sumDown) / (sumUp + sumDown) over `period`. Unlike RSI
 * (overbought/oversold reversal), this reads CMO as directional momentum:
 * positive = up-momentum. Strictly causal (uses only the closed window ending
 * at the last candle).
 */
class CmoIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "cmo"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val period = config.getInt("period", 14)
        val strong = config.getDouble("strong", 50.0)
        val weak = config.getDouble("weak", 15.0)

        if (candles.size < period + 1) {
            return result(Signal.NEUTRAL, "CMO insufficient data")
        }

        val closes = candles.map { it.close }
        // Python: d = np.diff(close)[-period:] — the last `period` one-bar deltas.
        val window = closes.takeLast(period + 1)
        val deltas = window.zipWithNext { a, b -> b - a }

        val up = deltas.sumOf { if (it > 0.0) it else 0.0 }
        val dn = deltas.sumOf { if (it < 0.0) -it else 0.0 }
        val denom = up + dn
        if (denom == 0.0) {
            return result(Signal.NEUTRAL, "CMO flat window")
        }

        val cmo = 100.0 * (up - dn) / denom
        val raw = mapOf<String, Double?>("cmo" to round4(cmo))

        return when {
            cmo >= strong -> result(Signal.STRONG_BUY, "CMO ${cmo.format(1)} strong up-momentum", raw)
            cmo >= weak -> result(Signal.BUY, "CMO ${cmo.format(1)} up-momentum", raw)
            cmo <= -strong -> result(Signal.STRONG_SELL, "CMO ${cmo.format(1)} strong down-momentum", raw)
            cmo <= -weak -> result(Signal.SELL, "CMO ${cmo.format(1)} down-momentum", raw)
            else -> result(Signal.NEUTRAL, "CMO ${cmo.format(1)} flat", raw)
        }
    }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
}
