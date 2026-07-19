package com.diveintocrypto.android.domain.overlay

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Futures-microstructure overlay — a scan-layer signal bundle over the assembled
 * series data (OI / funding / taker / L-S). Faithful port of the Python reference
 * `scan/microstructure.py`.
 *
 * Every signal is a pure function over aligned float lists, strictly causal, and
 * returns a value in [-1, +1] (+ bullish / − bearish) or null when there is not
 * enough data. The bundle score is the weighted mean of the available signals,
 * mapped to a signed −100..+100 and a BUY/SELL/NEUTRAL label. ADDITIVE — it never
 * touches the parity-locked consensus.
 */
object Microstructure {

    data class Params(
        val window: Int = 24,
        val minPoints: Int = 8,
        val zCap: Double = 3.0,
        val buyThreshold: Double = 20.0,
        val strongThreshold: Double = 55.0,
        val wOiDiv: Double = 1.0,
        val wOiBreakout: Double = 1.0,
        val wFundingFade: Double = 1.0,
        val wTaker: Double = 1.0,
        val wCrowding: Double = 1.0,
        val wSmartDumb: Double = 0.8,
    )

    data class SignalOut(val name: String, val score: Double, val reason: String, val weight: Double)

    data class Result(
        val score: Double,      // signed −100..+100
        val direction: Int,     // +1 / −1 / 0
        val label: String,      // STRONG_BUY | BUY | NEUTRAL | SELL | STRONG_SELL | OFF
        val active: Int,
        val signals: List<SignalOut>,
    )

    data class Series(
        val oi: List<Double> = emptyList(),
        val price: List<Double> = emptyList(),
        val funding: List<Double> = emptyList(),
        val taker: List<Double> = emptyList(),
        val glob: List<Double> = emptyList(),
        val pos: List<Double> = emptyList(),
    )

    // ── numeric helpers (mirror the Python file) ────────────────────────────
    private fun clean(x: List<Double>): List<Double> =
        x.filter { !it.isNaN() && !it.isInfinite() }

    private fun mean(a: List<Double>): Double = if (a.isEmpty()) 0.0 else a.sum() / a.size

    private fun std(a: List<Double>): Double {
        val n = a.size
        if (n < 2) return 0.0
        val m = mean(a)
        return sqrt(a.sumOf { (it - m) * (it - m) } / (n - 1))
    }

    private fun clip(x: Double, lo: Double = -1.0, hi: Double = 1.0): Double =
        if (x < lo) lo else if (x > hi) hi else x

    private fun sign(x: Double): Int = if (x > 0) 1 else if (x < 0) -1 else 0

    private fun pctChange(a: List<Double>): Double {
        if (a.size < 2 || a.first() == 0.0) return 0.0
        return (a.last() - a.first()) / abs(a.first())
    }

    private fun zscore(a: List<Double>, cap: Double): Double {
        if (a.size < 3) return 0.0
        val hist = a.subList(0, a.size - 1)
        val s = std(hist)
        if (s == 0.0) return 0.0
        return clip((a.last() - mean(hist)) / s, -cap, cap)
    }

    private fun List<Double>.tail(n: Int): List<Double> =
        if (size <= n) this else subList(size - n, size)

    // ── individual signals: (value in [-1,1] | null, reason) ────────────────
    private fun oiPriceDivergence(oi: List<Double>, price: List<Double>, p: Params): Pair<Double?, String> {
        if (minOf(oi.size, price.size) < p.minPoints) return null to "oi/price insufficient"
        val dOi = pctChange(oi.tail(p.window))
        val dPr = pctChange(price.tail(p.window))
        if (abs(dPr) < 0.002) return 0.0 to "price flat"
        val confirm = if (dOi > 0) 1.0 else -1.0
        val mag = clip(abs(dPr) / 0.03)
        return clip(sign(dPr) * confirm * mag) to "dPr=$dPr dOI=$dOi"
    }

    private fun oiBreakoutConfirm(oi: List<Double>, price: List<Double>, p: Params): Pair<Double?, String> {
        if (minOf(oi.size, price.size) < p.minPoints) return null to "oi/price insufficient"
        val dOi = pctChange(oi.tail(p.window))
        val dPr = pctChange(price.tail(p.window))
        if (abs(dPr) < 0.01 || dOi <= 0) return 0.0 to "no OI-backed breakout"
        return clip(sign(dPr) * clip(dOi / 0.05)) to "breakout dPr=$dPr dOI=$dOi"
    }

    private fun fundingFade(funding: List<Double>, p: Params): Pair<Double?, String> {
        val f = funding.tail(p.window)
        if (f.size < 3) return null to "funding insufficient"
        val z = zscore(f, p.zCap)
        if (z == 0.0) return 0.0 to "funding neutral"
        return clip(-z / p.zCap) to "funding z=$z"
    }

    private fun takerAggression(taker: List<Double>, p: Params): Pair<Double?, String> {
        val t = taker.tail(p.window)
        if (t.size < p.minPoints) return null to "taker insufficient"
        val recent = if (t.size >= 4) mean(t.tail(4)) else mean(t)
        val level = clip((recent - 1.0) / 0.30)
        val trend = clip(pctChange(t) / 0.10)
        return clip(0.6 * level + 0.4 * trend) to "taker~$recent"
    }

    private fun lsCrowdingFade(glob: List<Double>, p: Params): Pair<Double?, String> {
        val g = glob.tail(p.window)
        if (g.size < 3) return null to "glob insufficient"
        val z = zscore(g, p.zCap)
        if (z == 0.0) return 0.0 to "crowd neutral"
        return clip(-z / p.zCap) to "crowd z=$z"
    }

    private fun smartDumbSpread(pos: List<Double>, glob: List<Double>, p: Params): Pair<Double?, String> {
        if (minOf(pos.size, glob.size) < p.minPoints) return null to "pos/glob insufficient"
        val spread = mean(pos.tail(p.window)) - mean(glob.tail(p.window))
        if (abs(spread) < 0.02) return 0.0 to "spread flat"
        return clip(spread / 0.5) to "smart-dumb=$spread"
    }

    fun evaluate(series: Series, enabled: Boolean = true, params: Params = Params()): Result {
        if (!enabled) return Result(0.0, 0, "OFF", 0, emptyList())

        val oi = clean(series.oi); val price = clean(series.price)
        val funding = clean(series.funding); val taker = clean(series.taker)
        val glob = clean(series.glob); val pos = clean(series.pos)

        val computed = listOf(
            Triple("oi_price_divergence", oiPriceDivergence(oi, price, params), params.wOiDiv),
            Triple("oi_breakout_confirm", oiBreakoutConfirm(oi, price, params), params.wOiBreakout),
            Triple("funding_fade", fundingFade(funding, params), params.wFundingFade),
            Triple("taker_aggression", takerAggression(taker, params), params.wTaker),
            Triple("ls_crowding_fade", lsCrowdingFade(glob, params), params.wCrowding),
            Triple("smart_dumb_spread", smartDumbSpread(pos, glob, params), params.wSmartDumb),
        )

        val signals = ArrayList<SignalOut>()
        var wsum = 0.0
        var acc = 0.0
        for ((name, valueReason, w) in computed) {
            val (value, reason) = valueReason
            if (value == null) continue
            signals += SignalOut(name, value, reason, w)
            acc += value * w
            wsum += w
        }
        if (wsum == 0.0) return Result(0.0, 0, "NEUTRAL", 0, emptyList())

        val score = kotlin.math.round(clip(acc / wsum) * 1000.0) / 10.0
        val mag = abs(score)
        val direction: Int
        val label: String
        if (mag < params.buyThreshold) {
            direction = 0; label = "NEUTRAL"
        } else {
            direction = sign(score)
            val strong = mag >= params.strongThreshold
            label = if (direction > 0) (if (strong) "STRONG_BUY" else "BUY")
                    else (if (strong) "STRONG_SELL" else "SELL")
        }
        return Result(score, direction, label, signals.size, signals)
    }
}
