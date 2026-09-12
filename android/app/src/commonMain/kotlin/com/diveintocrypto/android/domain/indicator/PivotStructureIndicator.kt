package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.format

/**
 * Pivot structure (market-structure fractal) indicator — verbatim port of the
 * Python reference `pivot_structure.py`.
 *
 * Labels confirmed fractal swing points with `k` bars on each side (default 2):
 *
 *   swing high @ j: high[j] strictly greater than the k highs before AND the
 *                   k highs after j (confirmation needs j + k <= last bar)
 *   swing low @ j:  mirrored on lows
 *
 * Structure read from the two most recent confirmed swings of each kind:
 *
 *   higher-high + higher-low  (HH+HL) -> BUY bias  (uptrend intact)
 *   lower-high  + lower-low   (LH+LL) -> SELL bias (downtrend intact)
 *   anything else                     -> NEUTRAL (mixed structure)
 *
 * STRONG on a structure break: with a BUY bias, a close BELOW the last
 * confirmed swing low breaks the uptrend (choch) -> STRONG_SELL; with a SELL
 * bias, a close ABOVE the last confirmed swing high -> STRONG_BUY. Causal:
 * only confirmed (fully-fractaled) swings and the current close are read, so
 * swing labels never repaint.
 */
class PivotStructureIndicator(config: IndicatorConfig) : BaseIndicator(config) {

    override val name: String = "pivot_structure"

    override fun calculate(candles: List<Candle>): IndicatorResult {
        val k = config.getInt("k", 2)

        val n = candles.size
        // A fractal needs k bars on each side.
        if (n < 2 * k + 1) {
            return result(Signal.NEUTRAL, "Pivot structure data insufficient")
        }

        val swingHighs = ArrayList<Double>()
        val swingLows = ArrayList<Double>()
        // Last confirmable center index is n-1-k.
        for (j in k until (n - k)) {
            val hi = candles[j].high
            val lo = candles[j].low
            var isHigh = true
            var isLow = true
            for (d in 1..k) {
                if (!(hi > candles[j - d].high && hi > candles[j + d].high)) isHigh = false
                if (!(lo < candles[j - d].low && lo < candles[j + d].low)) isLow = false
                if (!(isHigh || isLow)) break
            }
            if (isHigh) swingHighs.add(hi)
            if (isLow) swingLows.add(lo)
        }

        if (swingHighs.size < 2 || swingLows.size < 2) {
            return result(
                Signal.NEUTRAL,
                "Pivot structure data insufficient (<2 confirmed swings)",
            )
        }

        val lastSh = swingHighs[swingHighs.size - 1]
        val prevSh = swingHighs[swingHighs.size - 2]
        val lastSl = swingLows[swingLows.size - 1]
        val prevSl = swingLows[swingLows.size - 2]

        val hh = lastSh > prevSh
        val lh = lastSh < prevSh
        val hl = lastSl > prevSl
        val ll = lastSl < prevSl

        val c = candles[n - 1].close

        val baseRaw = mapOf<String, Double?>(
            "last_swing_high" to round6(lastSh),
            "prev_swing_high" to round6(prevSh),
            "last_swing_low" to round6(lastSl),
            "prev_swing_low" to round6(prevSl),
            "structure_break" to 0.0,
        )

        return if (hh && hl) {
            if (c < lastSl) {
                result(
                    Signal.STRONG_SELL,
                    "Uptrend structure broken: close ${c.format(4)} below last swing low " +
                        "${lastSl.format(4)}",
                    baseRaw + ("structure" to 0.0) + ("structure_break" to 1.0),
                )
            } else {
                result(
                    Signal.BUY,
                    "Uptrend structure: HH ${lastSh.format(4)} + HL ${lastSl.format(4)}",
                    baseRaw + ("structure" to 0.0),
                )
            }
        } else if (lh && ll) {
            if (c > lastSh) {
                result(
                    Signal.STRONG_BUY,
                    "Downtrend structure broken: close ${c.format(4)} above last swing high " +
                        "${lastSh.format(4)}",
                    baseRaw + ("structure" to 1.0) + ("structure_break" to 1.0),
                )
            } else {
                result(
                    Signal.SELL,
                    "Downtrend structure: LH ${lastSh.format(4)} + LL ${lastSl.format(4)}",
                    baseRaw + ("structure" to 1.0),
                )
            }
        } else {
            result(
                Signal.NEUTRAL,
                "Mixed swing structure (no aligned bias)",
                baseRaw + ("structure" to 2.0),
            )
        }
    }

    private fun round6(x: Double): Double = Math.round(x * 1000000.0) / 1000000.0
}
