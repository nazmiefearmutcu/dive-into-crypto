package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format
import kotlin.math.max

/**
 * Donchian Channel Breakout (turtle N-bar high/low break).
 *
 * Two channels from *prior-bar* extremes — a wide entry channel (N bars) and a
 * narrower confirmation channel (M bars); the channels contain only bars
 * strictly before the current one (pandas rolling().shift(1)), so the barrier
 * the close must clear never contains the close itself. Port of the Python
 * `donchian_breakout.py` — same math, thresholds, and signal mapping.
 */
class DonchianBreakoutIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "donchian_breakout"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        // Entry channel length (classic Turtle System-1 uses 20).
        val period = config.getInt("period", 20)
        // Confirmation / exit channel length (Turtle uses 10). Must be < period.
        var exitPeriod = config.getInt("exit_period", 10)
        // Optional fractional buffer a close must exceed a channel by to count
        // as a break (0.0 = pure turtle break). e.g. 0.001 = 0.1%.
        val bufferPct = config.getDouble("breakout_buffer_pct", 0.0)

        if (exitPeriod >= period) {
            exitPeriod = max(1, period / 2)
        }

        val n = candles.size
        // Need `period` prior bars for the wide channel plus the current bar.
        if (n < period + 1) {
            return result(Signal.NEUTRAL, "Donchian data insufficient")
        }

        val highs = candles.map { it.high }
        val lows = candles.map { it.low }
        val c = candles[n - 1].close

        // Channels built ONLY from bars strictly before the current one:
        // rolling(w).max().shift(1).iloc[-1] == extremum over indices [n-1-w .. n-2].
        val uN = highs.subList(n - 1 - period, n - 1).max()
        val lN = lows.subList(n - 1 - period, n - 1).min()
        val uM = highs.subList(n - 1 - exitPeriod, n - 1).max()
        val lM = lows.subList(n - 1 - exitPeriod, n - 1).min()

        val midN = (uN + lN) / 2.0

        // Buffered breakout barriers.
        val upBarrierN = uN * (1.0 + bufferPct)
        val loBarrierN = lN * (1.0 - bufferPct)
        val upBarrierM = uM * (1.0 + bufferPct)
        val loBarrierM = lM * (1.0 - bufferPct)

        val widthN = uN - lN
        val position = if (widthN > 0.0) (c - lN) / widthN else 0.5

        val raw = mapOf<String, Double?>(
            "period" to period.toDouble(),
            "exit_period" to exitPeriod.toDouble(),
            "upper" to round6(uN),
            "lower" to round6(lN),
            "mid" to round6(midN),
            "exit_upper" to round6(uM),
            "exit_lower" to round6(lM),
            "close" to round6(c),
            "position" to round4(position),
        )

        // Ordered, monotonic 5-level map. Because the N-window contains the
        // M-window, u_n >= u_m and l_n <= l_m, so breaking the N barrier is
        // strictly rarer/stronger than breaking only the M barrier.
        if (c > upBarrierN) {
            return result(
                Signal.STRONG_BUY,
                "Breakout above $period-bar high ${uN.format(4)} (pos=${position.format(2)})",
                raw,
            )
        }
        if (c > upBarrierM) {
            return result(
                Signal.BUY,
                "Breakout above $exitPeriod-bar high ${uM.format(4)}, approaching $period-bar high",
                raw,
            )
        }
        if (c < loBarrierN) {
            return result(
                Signal.STRONG_SELL,
                "Breakdown below $period-bar low ${lN.format(4)} (pos=${position.format(2)})",
                raw,
            )
        }
        if (c < loBarrierM) {
            return result(
                Signal.SELL,
                "Breakdown below $exitPeriod-bar low ${lM.format(4)}, approaching $period-bar low",
                raw,
            )
        }

        val side = if (c >= midN) "upper" else "lower"
        return result(
            Signal.NEUTRAL,
            "Inside Donchian channel ($side half, pos=${position.format(2)})",
            raw,
        )
    }

    private fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
    private fun round6(x: Double): Double = Math.round(x * 1_000_000.0) / 1_000_000.0
}
