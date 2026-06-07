package com.diveintocrypto.android.domain.divergence

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [WhaleDivergence] **directional + EMPIRICAL CONTRARIAN** contract (2026-06-01):
 *   - DISTRIBUTION (price ↑ + whale L/S ↓): patternDirection = -1 (descriptive), but since
 *     top-trader L/S is contrarian, predictive direction = +1, score POSITIVE.
 *   - ACCUMULATION (price ↓ + whale L/S ↑): patternDirection = +1, direction = -1, score NEGATIVE.
 * That is `direction = patternDirection × whalePredictiveSign` with the default
 * whalePredictiveSign = -1 (contrarian). 0 on flat/insufficient/equal data; |score| scales with the
 * timeframe resolution (1d >> 5m); aggregation preserves the sign, opposite direction gives no
 * corroboration. The magnitude formula did not change; only the predictive sign was flipped.
 * The is_adverse / verdict elimination rule keeps running on the predictive `direction`
 * (== -dir) — these tests are NOT affected by the SOURCE sign flip and were not changed.
 */
class WhaleDivergenceTest {

    // --- DISTRIBUTION fixtures (price rises, whale distributes=sells) ------
    // Contrarian: this pattern is predictively +1 (expect up) → score POSITIVE.
    private fun risingPrice(): List<Double> = (0 until 60).map { i ->
        when {
            i < 30 -> 100.0
            i < 40 -> 100.0 - (i - 30) * 0.5    // 100 → 95.5 (dip)
            else -> 95.0 + (i - 40) * 1.2        // 95 → 117.8 (peak, current)
        }
    }
    private fun distributingWhale(): List<Double> = (0 until 60).map { i ->
        if (i <= 20) 1.4 + (i / 20.0) * 0.5 else 1.9 - ((i - 20) / 39.0) * 0.75
    }
    private fun chasingWhale(): List<Double> = (0 until 60).map { i -> 1.0 + i * 0.015 }

    // --- ACCUMULATION fixtures (price falls, whale accumulates=buys) ----------
    // Contrarian: this pattern is predictively -1 (expect down) → score NEGATIVE.
    private fun fallingPrice(): List<Double> = (0 until 60).map { i ->
        if (i <= 20) 88.0 + i * 0.85           // 88 → 105 (peak)
        else 105.0 - (i - 20) * 0.55           // 105 → 83.55 (dip, current)
    }
    private fun accumulatingWhale(): List<Double> = (0 until 60).map { i ->
        if (i <= 10) 1.5 - i * 0.04            // 1.5 → 1.1 (dip, before the rise)
        else 1.1 + (i - 10) * 0.013            // 1.1 → 1.74 (accumulation)
    }

    // --- DISTRIBUTION tests (contrarian: score POSITIVE) ---------------------

    @Test
    fun bearishDivergenceOn1dGivesNegativeScore() {
        // Price↑ + whale-sell (distribution): raw pattern -1, but CONTRARIAN prediction +1 → score POSITIVE.
        val r = WhaleDivergence.perTf(risingPrice(), distributingWhale(), tfWeight = 95)
        assertTrue(r.detected)
        assertEquals(+1, r.direction)
        assertEquals(-1, r.patternDirection)
        assertTrue(r.score > 35.0, "distribution pattern should give a large POSITIVE contrarian score (got ${r.score})")
        assertTrue(r.score <= 100.0)
    }

    @Test
    fun noBearishWhenWhalesChasePrice() {
        val r = WhaleDivergence.perTf(risingPrice(), chasingWhale(), tfWeight = 95)
        assertFalse(r.detected)
        assertEquals(0.0, r.score, 1e-9)
    }

    // --- ACCUMULATION tests (contrarian: score NEGATIVE) ---------------------

    @Test
    fun bullishDivergenceOn1dGivesPositiveScore() {
        // Price↓ + whale-buy (accumulation): raw pattern +1, but CONTRARIAN prediction -1 → score NEGATIVE.
        val r = WhaleDivergence.perTf(fallingPrice(), accumulatingWhale(), tfWeight = 95)
        assertTrue(r.detected, "price drop + whale accumulation should give a divergence")
        assertEquals(-1, r.direction)
        assertEquals(+1, r.patternDirection)
        assertTrue(r.score < -35.0, "accumulation pattern should give a large NEGATIVE contrarian score (got ${r.score})")
    }

    @Test
    fun noBullishWhenWhalesDumpOnDrop() {
        // Price falls and whale also falls (no accumulation) → no divergence.
        val r = WhaleDivergence.perTf(fallingPrice(), distributingWhale(), tfWeight = 95)
        assertFalse(r.detected)
    }

    @Test
    fun bearishAndBullishHaveOppositeSigns() {
        // Contrarian: distribution pattern score POSITIVE, accumulation pattern score NEGATIVE.
        val bear = WhaleDivergence.perTf(risingPrice(), distributingWhale(), tfWeight = 95)
        val bull = WhaleDivergence.perTf(fallingPrice(), accumulatingWhale(), tfWeight = 95)
        assertTrue(bear.score > 0.0 && bull.score < 0.0, "distribution>0, accumulation<0 (${bear.score}, ${bull.score})")
    }

    // --- Degenerate cases -----------------------------------------------

    @Test
    fun flatMarketGivesZero() {
        val flat = (0 until 60).map { 100.0 }
        val r = WhaleDivergence.perTf(flat, distributingWhale(), tfWeight = 95)
        assertFalse(r.detected)
        assertEquals(0.0, r.score, 1e-9)
    }

    @Test
    fun insufficientDataGivesZero() {
        val price = (0 until 10).map { 100.0 + it }
        val whale = (0 until 10).map { 1.5 - it * 0.05 }
        assertFalse(WhaleDivergence.perTf(price, whale, tfWeight = 95).detected)
    }

    @Test
    fun allEqualSeriesGivesZero() {
        val price = (0 until 40).map { 100.0 }
        val whale = (0 until 40).map { 1.5 }
        assertFalse(WhaleDivergence.perTf(price, whale, tfWeight = 95).detected)
    }

    // --- TF weight + magnitude ------------------------------------------

    @Test
    fun higherTimeframeOutscoresLowerByAbs() {
        val s1d = abs(WhaleDivergence.perTf(risingPrice(), distributingWhale(), tfWeight = 95).score)
        val s5m = abs(WhaleDivergence.perTf(risingPrice(), distributingWhale(), tfWeight = 25).score)
        assertTrue(s1d > 0.0 && s5m > 0.0)
        assertTrue(s1d > 4.0 * s5m, "1d should beat 5m (|$s1d| vs |$s5m|)")
    }

    @Test
    fun scoreMagnitudeMonotonicInTimeframeWeight() {
        val weights = listOf(8, 15, 25, 38, 48, 58, 65, 75, 80, 90, 95)
        var prev = -1.0
        for (w in weights) {
            val s = abs(WhaleDivergence.perTf(risingPrice(), distributingWhale(), tfWeight = w).score)
            assertTrue(s >= prev - 1e-9, "|score| should not decrease with TF weight (w=$w → $s)")
            prev = s
        }
    }

    @Test
    fun highTfDropsDiscriminateNotSaturate() {
        val price = risingPrice()
        val moderate = (0 until 60).map { i -> if (i <= 20) 1.86 + (i / 20.0) * 0.14 else 2.00 - ((i - 20) / 39.0) * 0.12 } // ~%6
        val deep = (0 until 60).map { i -> if (i <= 20) 1.56 + (i / 20.0) * 0.44 else 2.00 - ((i - 20) / 39.0) * 0.44 }     // ~%22
        val mMod = WhaleDivergence.perTf(price, moderate, tfWeight = 95).magnitude
        val mDeep = WhaleDivergence.perTf(price, deep, tfWeight = 95).magnitude
        assertTrue(mDeep > mMod + 0.05, "high-TF drops should discriminate (mod=$mMod deep=$mDeep)")
    }

    // --- Sanitization ----------------------------------------------------

    @Test
    fun nanValuesAreSanitizedNotCrashed() {
        val whale = distributingWhale().toMutableList()
        whale[5] = Double.NaN; whale[6] = Double.NaN; whale[30] = Double.NaN
        val r = WhaleDivergence.perTf(risingPrice(), whale, tfWeight = 95)
        assertTrue(r.detected)
        assertFalse(r.score.isNaN())
    }

    @Test
    fun tooManyNaNsDegradeToZero() {
        val whale = distributingWhale().toMutableList()
        for (i in 0 until 20) whale[i] = Double.NaN
        assertFalse(WhaleDivergence.perTf(risingPrice(), whale, tfWeight = 95).detected)
    }

    // --- Symbol aggregation (signed) ------------------------------------

    @Test
    fun symbolScoreIsSignedMaxPlusSameSignBonus() {
        // Two negative (accumulation pattern, contrarian down) TFs → signed max + same-direction bonus.
        val r1d = WhaleDivergence.TfResult(-57.0, 0.57, 0.2, 0.33, true, direction = -1, patternDirection = 1)
        val r12h = WhaleDivergence.TfResult(-20.0, 0.3, 0.1, 0.2, true, direction = -1, patternDirection = 1)
        val sym = WhaleDivergence.forSymbol(mapOf("1d" to r1d, "12h" to r12h, "8h" to WhaleDivergence.TfResult.NONE))
        assertEquals("1d", sym.bestTf)
        assertEquals(-1, sym.direction)
        // -(57 + 0.10*20) = -59
        assertEquals(-59.0, sym.score, 0.5)
    }

    @Test
    fun oppositeSignTfGivesNoCorroborationBonus() {
        // Leading bear (-50) + opposite-direction bull (+40) → NO bonus (opposite doesn't reinforce) → -50.
        val sym = WhaleDivergence.forSymbol(
            mapOf(
                "1d" to WhaleDivergence.TfResult(-50.0, 0.5, 0.2, 0.3, true, direction = -1, patternDirection = 1),
                "4h" to WhaleDivergence.TfResult(40.0, 0.4, 0.2, 0.3, true, direction = 1, patternDirection = -1),
            ),
        )
        assertEquals(-50.0, sym.score, 1e-9)
        assertEquals(-1, sym.direction)
    }

    @Test
    fun strongerAbsSignalWinsDirection() {
        // Bull +60 beats bear -30 in absolute value → positive direction.
        val sym = WhaleDivergence.forSymbol(
            mapOf(
                "1d" to WhaleDivergence.TfResult(60.0, 0.6, 0.2, 0.3, true, direction = 1, patternDirection = -1),
                "4h" to WhaleDivergence.TfResult(-30.0, 0.3, 0.2, 0.3, true, direction = -1, patternDirection = 1),
            ),
        )
        assertTrue(sym.score > 0.0)
        assertEquals(1, sym.direction)
    }

    @Test
    fun emptyOrUndetectedSymbolIsZero() {
        val sym = WhaleDivergence.forSymbol(mapOf("1d" to WhaleDivergence.TfResult.NONE))
        assertEquals(0.0, sym.score, 1e-9)
        assertEquals(0, sym.direction)
    }

    // --- EMPIRICAL CONTRARIAN sign + new behaviors (2026-06-01) --------

    @Test
    fun priceUpWhalesSellingIsBearishNotBullish() {
        // When price rises net while whale L/S falls net (distribution) → raw pattern -1, but
        // since top-trader L/S is CONTRARIAN the predictive direction is +1 and the score POSITIVE:
        // this coin ranks UP, NOT eliminated when the indicator is BUY. (Fixed 2026-06-01.)
        val price = (0 until 40).map { 100.0 + it * 0.6 }      // steady rise
        val whale = (0 until 40).map { 1.9 - it * 0.02 }       // steady drop (sell)
        val r = WhaleDivergence.perTf(price, whale, tfWeight = 95)
        assertTrue(r.detected)
        assertEquals(+1, r.direction, "price↑ + whale↓ should be CONTRARIAN +1 (expect up)")
        assertEquals(-1, r.patternDirection, "raw pattern should be distribution (-1)")
        assertTrue(r.score > 0.0, "score should be POSITIVE (got ${r.score})")
    }

    @Test
    fun priceDownWhalesBuyingIsBullishNotBearish() {
        // Price↓ + whale↑ (accumulation) → raw pattern +1, contrarian prediction -1, score NEGATIVE.
        val price = (0 until 40).map { 120.0 - it * 0.6 }      // steady drop
        val whale = (0 until 40).map { 1.2 + it * 0.02 }       // steady rise (accumulation)
        val r = WhaleDivergence.perTf(price, whale, tfWeight = 95)
        assertTrue(r.detected)
        assertEquals(-1, r.direction, "price↓ + whale↑ should be CONTRARIAN -1 (expect down)")
        assertEquals(+1, r.patternDirection, "raw pattern should be accumulation (+1)")
        assertTrue(r.score < 0.0)
    }

    @Test
    fun highestTfWinsEvenIfLowerTfHasBiggerScore() {
        // 1d (weak but the HIGHEST TF) vs 12h (strong). The highest TF = 1d should be picked.
        val sym = WhaleDivergence.forSymbol(
            mapOf(
                "1d" to WhaleDivergence.TfResult(-30.0, 0.3, 0.2, 0.2, true, direction = -1, patternDirection = 1, tfWeight = 95),
                "12h" to WhaleDivergence.TfResult(-80.0, 0.8, 0.3, 0.3, true, direction = -1, patternDirection = 1, tfWeight = 90),
            ),
        )
        assertEquals("1d", sym.bestTf, "the highest TF (1d) should be picked, not the larger-|score| 12h")
        assertEquals(-1, sym.direction)
        // base 30 (1d) + 0.10*80 (same-direction 12h confirmation) = 38
        assertTrue(abs(sym.score) in 37.0..39.0, "1d base + confirmation bonus (got ${sym.score})")
    }

    @Test
    fun moderateDivergenceScoresWellAboveNoiseFloor() {
        // REGRESSION: the previous multiplicative magnitude (pow1.5×pow2.5) crushed a realistic
        // divergence (price ~+5%, whale ~−9%) to a score ≈ 4.6 → it fell BELOW the 5.0 noise
        // floor and became INVISIBLE (the bot put it in the top 5 without detecting distribution).
        // The new additive model should make this ~50+: a modest divergence must be CLEARLY visible.
        val price = (0 until 40).map { 100.0 + it * 0.15 }   // ~+5% steady rise
        val whale = (0 until 40).map { 2.0 - it * 0.005 }    // ~−9% steady sell (distribution)
        val r = WhaleDivergence.perTf(price, whale, tfWeight = 95)
        assertTrue(r.detected, "a modest divergence should be detected")
        assertEquals(+1, r.direction, "distribution pattern should be contrarian +1")
        assertEquals(-1, r.patternDirection, "raw pattern should be distribution (-1)")
        assertTrue(abs(r.score) > 20.0,
            "a modest divergence should be WELL above the noise floor (5.0), NOT the old collapse ≈4.6 (got ${r.score})")
    }

    @Test
    fun largerDivergenceMagnitudeScoresHigher() {
        // Same TF + same price movement, but a LARGER whale sell → a HIGHER |score|.
        val price = (0 until 40).map { 100.0 + it * 0.6 }
        val small = (0 until 40).map { 1.9 - it * 0.004 }   // light sell
        val big = (0 until 40).map { 1.9 - it * 0.020 }     // heavy sell
        val sSmall = abs(WhaleDivergence.perTf(price, small, tfWeight = 95).score)
        val sBig = abs(WhaleDivergence.perTf(price, big, tfWeight = 95).score)
        assertTrue(sBig > sSmall + 5.0, "a heavy sell should score noticeably higher than a light sell ($sSmall vs $sBig)")
    }
}
