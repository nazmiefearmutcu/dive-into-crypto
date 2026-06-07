package com.diveintocrypto.android.domain.divergence

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/**
 * **Whale L/S Divergence — DIRECTIONAL TREND detector (EMPIRICAL CONTRARIAN).**
 *
 * Idea (redesigned 2026-06-01; sign correction 2026-06-01, empirical):
 * over a window, when the **PRICE** trend and the **WHALE L/S** trend (top-trader
 * position ratio — `topLongShortPositionRatio`, the app's "Whale L/S") run in
 * OPPOSITE directions, that is a divergence. There are two distinct signs and they
 * must NEVER be conflated:
 *
 *   1. **patternDirection (DESCRIPTIVE)** — the whale's RAW movement:
 *      whale SELL (L/S ↓) ⇒ −1 (distribution), whale BUY (L/S ↑) ⇒ +1 (accumulation).
 *      For label/color/arrow only; it is NOT a prediction.
 *   2. **direction (SELECTION/RANKING, PREDICTIVE)** — `patternDirection × whalePredictiveSign`.
 *      Default `whalePredictiveSign = -1` ⇒ **CONTRARIAN**.
 *
 * **WHY CONTRARIAN?** The top-trader L/S ratio behaves contrarian empirically
 * (cross-sectional IC < 0 over 3197 Binance observations; and when the sign was flipped
 * to +1, live regression accuracy DROPPED). So:
 *   • price ↑ + whale L/S ↓ (whales SELLING into a rally) ⇒ patternDirection=−1,
 *     but price has historically CONTINUED upward ⇒ direction = −1×−1 = **+1**
 *     (ranks UP; NOT eliminated when the indicator is BUY).
 *   • price ↓ + whale L/S ↑ (accumulation) ⇒ patternDirection=+1 ⇒ direction = **−1**.
 *   • same direction (whale follows price) → NO divergence (0).
 *
 * Before 2026-06-01 the "smart money" interpretation (whale↓ ⇒ bearish/−1) was BACKWARDS
 * for selection and lowered real-world accuracy; the sign was flipped AT THE SOURCE with a
 * single signed constant (whalePredictiveSign). The descriptive label still shows the
 * whale's LITERAL movement.
 *
 * Scoring:
 *   - The signal is taken from the **highest TF resolution** where it was caught
 *     (forSymbol → 1d beats 12h; "if it appears on 1d AND 12h, take 1d").
 *   - The score is proportional to the **MAGNITUDE** of the divergence (the size of the
 *     whale movement): a small sell and a big sell do NOT get the same score. The magnitude
 *     formula is UNCHANGED; it now merely carries the predictive sign.
 *
 * Pure function: in commonMain so it can plug into the scanner engine, platform-independent.
 * Parameters are calibrated via backtest (tools/backtest).
 */
object WhaleDivergence {

    /**
     * Calibration parameters — **REDESIGNED 2026-06-01.** The previous version computed
     * magnitude as `priceStrength^1.5 × whaleStrength^2.5` (PRODUCT, high exponents, high
     * references); even a realistic divergence (price +5%, whale −8%) produced a score
     * ≈ 3.4 that fell BELOW the `DIVERGENCE_MIN_SHOWN = 5.0` noise floor → detection was
     * EFFECTIVELY DEAD (the bot put the coin in the top 5 without detecting distribution).
     * The new model uses a **weighted SUM**: `magnitude = whaleWeight·whaleStrength +
     * (1−whaleWeight)·priceStrength`. It is monotonic, well-spread (modest divergence ~20,
     * strong ~80-100), whale-primary (whaleWeight 0.70), and never collapses to small×small=0.
     * References are calibrated against the real Binance L/S distribution (tools/backtest/divcal.mjs).
     */
    data class Params(
        val minBars: Int = 20,            // fewer candles than this → not meaningful → 0
        val smooth: Int = 1,              // smaCentered damping off (preserves trend magnitude)
        val trendWindow: Int = 36,        // window (candles) over which price/whale trend is measured — sweep best
        val minPriceMove: Double = 0.008, // lower bound on meaningful |price Δ| (SENSITIVE — don't miss real divergence)
        val minWhaleMove: Double = 0.015, // lower bound on meaningful |whale L/S Δ|
        val priceRef: Double = 0.06,      // move that saturates price strength (6% → full strength)
        val whaleRef: Double = 0.16,      // move that saturates whale magnitude (16% → full strength; small↔large split is here)
        val whaleWeight: Double = 0.80,   // WHALE-PRIMARY: whale share of magnitude (price share = 1−this)
        val gamma: Double = 1.4,          // TF dominance exponent (>1 → 1d beats 5m)
        val wMax: Double = 95.0,          // highest time weight (1d)
        val aggBonus: Double = 0.10,      // same-direction lower-TF confirmation bonus (the highest TF stays dominant)
        /**
         * **PREDICTIVE SIGN MULTIPLIER** — `direction = patternDirection × whalePredictiveSign`.
         *   • `-1` (default) = **EMPIRICAL CONTRARIAN**: top-trader L/S came out contrarian
         *     (cross-sectional IC < 0 over 3197 Binance observations; live regression accuracy
         *     dropped when flipped to +1). If the whale sells into a rally, price has historically
         *     continued upward → that coin ranks UP, not eliminated.
         *   • `+1` = "follow the whales" (smart-money) interpretation — NOT supported by the data;
         *     kept only for research/comparison. Do NOT use in production.
         * Magnitude (weighted sum) is NOT affected by this multiplier — only the sign changes.
         */
        val whalePredictiveSign: Int = -1,
    )

    /** **Directional** divergence result for one (symbol, TF). */
    data class TfResult(
        val score: Double,       // **SIGNED** -100..+100 (negative=bearish/down, positive=bullish/up)
        val magnitude: Double,   // 0..1 (unsigned magnitude)
        val risePct: Double,     // |price Δ| (unsigned price movement)
        val whaleDrop: Double,   // |whale Δ| (unsigned whale movement: bearish=down, bullish=up)
        val detected: Boolean,   // is there a real divergence
        val direction: Int = 0,  // PREDICTIVE/RANKING sign: -1 expect down, +1 expect up, 0 none
        val patternDirection: Int = 0, // DESCRIPTIVE raw whale movement: -1 distribution (sell), +1 accumulation (buy), 0 none
        val tfWeight: Int = 0,   // this TF's time weight (forSymbol picks the highest TF)
    ) {
        companion object {
            val NONE = TfResult(0.0, 0.0, 0.0, 0.0, false, 0, 0, 0)
        }
    }

    /** **Directional** divergence result per symbol (all TFs blended). */
    data class SymbolResult(
        val score: Double,       // **SIGNED** -100..+100 — the score that ranks the table
        val bestTf: String?,     // the highest TF that produced the divergence
        val bestScore: Double,   // signed
        val bestRisePct: Double,
        val bestWhaleDrop: Double,
        val direction: Int = 0,  // PREDICTIVE/RANKING sign: -1 expect down, +1 expect up, 0 none
        val patternDirection: Int = 0, // DESCRIPTIVE raw whale movement: -1 distribution (sell), +1 accumulation (buy), 0 none
    ) {
        companion object {
            val NONE = SymbolResult(0.0, null, 0.0, 0.0, 0.0, 0, 0)
        }
    }

    /**
     * Computes the directional trend-divergence score for one (symbol, TF).
     *
     * @param price close prices (old→new)
     * @param whaleLS per-candle whale L/S ratio (old→new, same length as price)
     * @param tfWeight this TF's time weight (1m=8 .. 1d=95)
     */
    fun perTf(
        price: List<Double>,
        whaleLS: List<Double>,
        tfWeight: Int,
        params: Params = Params(),
    ): TfResult {
        val n = minOf(price.size, whaleLS.size)
        if (n < params.minBars) return TfResult.NONE

        // Sanitize: forward-fill NaN/Inf; if more than 20% is corrupt → 0.
        val p = sanitize(price, n) ?: return TfResult.NONE
        val ls = sanitize(whaleLS, n) ?: return TfResult.NONE
        val sPrice = smaCentered(p, params.smooth)
        val sWhale = smaCentered(ls, params.smooth)

        // Net trend over the window (start → end).
        val w = minOf(n, params.trendWindow)
        val s = (n - w).coerceIn(0, n - 1)
        val pStart = sPrice[s]; val pEnd = sPrice[n - 1]
        val wStart = sWhale[s]; val wEnd = sWhale[n - 1]
        if (pStart <= 0.0 || wStart <= 0.0) return TfResult.NONE

        val priceD = (pEnd - pStart) / pStart           // price net Δ (signed)
        val whaleD = (wEnd - wStart) / wStart            // whale L/S net Δ (signed)
        if (abs(priceD) < params.minPriceMove) return TfResult.NONE
        if (abs(whaleD) < params.minWhaleMove) return TfResult.NONE

        // Divergence = OPPOSITE direction. Same direction (whale follows price) → no signal.
        if (sign(priceD) == sign(whaleD)) return TfResult.NONE

        // DESCRIPTIVE raw whale movement: whale ↓ = distribution (−1), whale ↑ = accumulation (+1).
        val patternDirection = if (whaleD < 0.0) -1 else 1
        // SINGLE SIGN FLIP (AT THE SOURCE): predictive/ranking sign = raw pattern × contrarian
        // multiplier. whalePredictiveSign = -1 (default) ⇒ EMPIRICAL CONTRARIAN: if the whale sells
        // into a rally (patternDirection=-1), price has historically continued upward ⇒ direction=+1.
        val direction = patternDirection * params.whalePredictiveSign

        // Magnitude: both axes are saturated to [0,1]. COMBINATION = WEIGHTED SUM (NOT a product).
        // The product gave small×small ≈ 0, killing detection; the sum stays monotonic, spreads
        // well, and preserves the requirement that the whale weight (whaleWeight) "strongly drives
        // the excess score." The existence of a divergence is already guaranteed by the min-threshold gates (above).
        val priceStrength = (abs(priceD) / params.priceRef).coerceIn(0.0, 1.0)
        val whaleStrength = (abs(whaleD) / params.whaleRef).coerceIn(0.0, 1.0)
        val pw = params.whaleWeight.coerceIn(0.0, 1.0)
        val magnitude = (pw * whaleStrength + (1.0 - pw) * priceStrength).coerceIn(0.0, 1.0)
        if (magnitude <= 0.0) return TfResult.NONE

        val tfFactor = (tfWeight.toDouble() / params.wMax).coerceIn(0.0, 1.0).pow(params.gamma)
        val score = (direction * 100.0 * magnitude * tfFactor).coerceIn(-100.0, 100.0)

        return TfResult(
            score = score,
            magnitude = magnitude,
            risePct = abs(priceD),
            whaleDrop = abs(whaleD),
            detected = true,
            direction = direction,
            patternDirection = patternDirection,
            tfWeight = tfWeight,
        )
    }

    /**
     * Reduces all of a symbol's TF results to a single score. The signal is taken from the
     * **highest TF resolution** where it was caught (largest time weight; "if it appears on
     * 1d and 12h, take 1d"); ties broken by the stronger |score|. Same-direction lower-TF
     * confirmation adds a small bonus (opposite direction gives NO bonus).
     */
    fun forSymbol(perTf: Map<String, TfResult>, params: Params = Params()): SymbolResult {
        val detected = perTf.filterValues { it.detected }
        if (detected.isEmpty()) return SymbolResult.NONE
        // HIGHEST TF (largest time weight); tie broken by absolute score.
        val best = detected.entries.maxWithOrNull(
            compareBy({ it.value.tfWeight }, { abs(it.value.score) }),
        ) ?: return SymbolResult.NONE
        val bestSign = best.value.direction.takeIf { it != 0 } ?: if (best.value.score >= 0) 1 else -1
        // Corroboration = the strongest other TF in the SAME direction (conflicting signals don't reinforce).
        val corr = detected.entries
            .filter { it.key != best.key && it.value.direction == best.value.direction }
            .maxOfOrNull { abs(it.value.score) } ?: 0.0
        val mag = (abs(best.value.score) + params.aggBonus * corr).coerceIn(0.0, 100.0)
        return SymbolResult(
            score = bestSign * mag,
            bestTf = best.key,
            bestScore = best.value.score,
            bestRisePct = best.value.risePct,
            bestWhaleDrop = best.value.whaleDrop,
            direction = best.value.direction,
            patternDirection = best.value.patternDirection,
        )
    }

    // ----------------------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------------------

    /** Forward-fills NaN/Inf; returns null if the corrupt ratio exceeds 20% (caller → 0). */
    private fun sanitize(x: List<Double>, n: Int): DoubleArray? {
        val out = DoubleArray(n)
        var bad = 0
        var lastGood = Double.NaN
        for (i in 0 until n) {
            val v = x[i]
            if (v.isNaN() || v.isInfinite()) {
                bad++
                out[i] = lastGood
            } else {
                out[i] = v
                lastGood = v
            }
        }
        if (bad > n / 5) return null
        if (out.any { it.isNaN() }) {
            val firstGood = out.firstOrNull { !it.isNaN() } ?: return null
            for (i in out.indices) {
                if (out[i].isNaN()) out[i] = firstGood
            }
        }
        return out
    }

    /** Centered SMA (window w, odd); clipped to the available neighbors at the edges. */
    private fun smaCentered(x: DoubleArray, w: Int): DoubleArray {
        if (w <= 1) return x.copyOf()
        val half = w / 2
        val out = DoubleArray(x.size)
        for (i in x.indices) {
            var sum = 0.0
            var cnt = 0
            for (k in (i - half)..(i + half)) {
                if (k in x.indices) { sum += x[k]; cnt++ }
            }
            out[i] = sum / cnt
        }
        return out
    }
}
