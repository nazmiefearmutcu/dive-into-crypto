package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.Signal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Synthetic-scenario unit tests for the price-action pattern library
 * (engulfing / liquidity_sweep / pivot_structure). Scenarios mirror
 * desktop/backend/tests/test_patterns.py so both editions assert the same
 * behaviour; fixture parity on the shared BTCUSDT 1h x300 data is pinned in
 * [ExtendedIndicatorsFixtureTest].
 */
class PatternsIndicatorsTest {

    private val cfg = IndicatorConfig() // in-code defaults == desktop default.yaml

    private fun bar(o: Double, h: Double, l: Double, c: Double, t: Long = 0L) = Candle(
        openTime = t, open = o, high = h, low = l, close = c, volume = 1.0, closeTime = t,
    )

    private fun flat(count: Int, price: Double = 100.0, wick: Double = 0.5) =
        List(count) { bar(price, price + wick, price - wick, price) }

    // ------------------------------------------------------------------
    // engulfing
    // ------------------------------------------------------------------

    @Test
    fun engulfingBullishStrong() {
        val bars = flat(3) + bar(101.0, 101.2, 99.8, 100.0) + bar(99.7, 101.4, 99.6, 101.3)
        val r = EngulfingIndicator(cfg).calculate(bars)
        assertEquals(Signal.STRONG_BUY, r.signal)
        assertEquals(2, r.score)
    }

    @Test
    fun engulfingBullishMarginalLongWick() {
        val bars = flat(3) + bar(101.0, 101.2, 99.8, 100.0) + bar(99.7, 101.4, 98.0, 101.0)
        val r = EngulfingIndicator(cfg).calculate(bars)
        assertEquals(Signal.BUY, r.signal)
        assertEquals(1, r.score)
    }

    @Test
    fun engulfingBearishStrong() {
        val bars = flat(3) + bar(100.0, 101.2, 99.8, 101.0) + bar(101.3, 101.4, 99.6, 99.7)
        val r = EngulfingIndicator(cfg).calculate(bars)
        assertEquals(Signal.STRONG_SELL, r.signal)
        assertEquals(-2, r.score)
    }

    @Test
    fun engulfingBearishMarginalLongWick() {
        val bars = flat(3) + bar(100.0, 101.2, 99.8, 101.0) + bar(101.0, 101.4, 99.0, 99.7)
        val r = EngulfingIndicator(cfg).calculate(bars)
        assertEquals(Signal.SELL, r.signal)
        assertEquals(-1, r.score)
    }

    @Test
    fun engulfingNoPatternSameColour() {
        val bars = flat(3) + bar(100.0, 100.6, 99.6, 100.4) + bar(100.4, 101.0, 100.0, 100.8)
        val r = EngulfingIndicator(cfg).calculate(bars)
        assertEquals(Signal.NEUTRAL, r.signal)
    }

    @Test
    fun engulfingInsufficientData() {
        val r = EngulfingIndicator(cfg).calculate(listOf(bar(100.0, 101.0, 99.0, 100.5)))
        assertEquals(Signal.NEUTRAL, r.signal)
        assertTrue("insufficient" in r.reason.lowercase())
    }

    // ------------------------------------------------------------------
    // liquidity_sweep
    // ------------------------------------------------------------------

    /** 24 bars: flat 100 (tight ±0.1 range), a bar setting the swing low, then the test bar. */
    private fun sweepBase(): MutableList<Candle> {
        val bars = flat(24, 100.0, 0.1).toMutableList()
        bars[22] = bar(100.0, 100.1, 99.0, 99.5) // prior bar: swing low 99.0
        return bars
    }

    @Test
    fun sweepLowStrongBuy() {
        val bars = sweepBase()
        bars[23] = bar(99.2, 99.6, 98.5, 99.5) // pierces 99.0 by 0.5, closes above
        val r = LiquiditySweepIndicator(cfg).calculate(bars)
        assertEquals(Signal.STRONG_BUY, r.signal)
        assertEquals(2, r.score)
    }

    @Test
    fun sweepLowWeakBuy() {
        val bars = sweepBase()
        bars[23] = bar(99.2, 99.6, 98.95, 99.5) // penetration 0.05 < 0.5xATR
        val r = LiquiditySweepIndicator(cfg).calculate(bars)
        assertEquals(Signal.BUY, r.signal)
        assertEquals(1, r.score)
    }

    @Test
    fun sweepHighStrongSell() {
        val bars = sweepBase()
        bars[22] = bar(100.0, 101.0, 99.9, 100.5) // prior bar: swing high 101.0
        bars[23] = bar(100.8, 101.5, 100.4, 100.5) // pierces 101.0, closes below
        val r = LiquiditySweepIndicator(cfg).calculate(bars)
        assertEquals(Signal.STRONG_SELL, r.signal)
        assertEquals(-2, r.score)
    }

    @Test
    fun sweepHighWeakSell() {
        val bars = sweepBase()
        bars[22] = bar(100.0, 101.0, 99.9, 100.5)
        bars[23] = bar(100.8, 101.05, 100.4, 100.5) // penetration 0.05, small
        val r = LiquiditySweepIndicator(cfg).calculate(bars)
        assertEquals(Signal.SELL, r.signal)
        assertEquals(-1, r.score)
    }

    @Test
    fun breakoutCloseOutsideIsNotASweep() {
        val bars = sweepBase()
        bars[23] = bar(99.2, 99.6, 98.5, 98.8) // pierces low AND closes below it
        val r = LiquiditySweepIndicator(cfg).calculate(bars)
        assertEquals(Signal.NEUTRAL, r.signal)
    }

    @Test
    fun noSweepInsideRange() {
        val bars = sweepBase()
        bars[23] = bar(100.0, 100.05, 99.5, 100.0)
        val r = LiquiditySweepIndicator(cfg).calculate(bars)
        assertEquals(Signal.NEUTRAL, r.signal)
    }

    @Test
    fun sweepInsufficientData() {
        val r = LiquiditySweepIndicator(cfg).calculate(flat(5))
        assertEquals(Signal.NEUTRAL, r.signal)
        assertTrue("insufficient" in r.reason.lowercase())
    }

    // ------------------------------------------------------------------
    // pivot_structure
    // ------------------------------------------------------------------

    /** HH+HL zigzag: swing high 104.5 -> swing low 99.8 -> HH 105.5 -> HL 100.4. */
    private fun uptrendZigzag() = mutableListOf(
        bar(99.0, 100.5, 98.5, 100.0),
        bar(100.0, 102.5, 99.5, 102.0),
        bar(102.0, 104.5, 101.5, 104.0), // swing high j=2
        bar(104.0, 104.2, 101.8, 102.0),
        bar(102.0, 102.2, 99.8, 100.0), // swing low j=4
        bar(100.0, 101.2, 99.9, 101.0),
        bar(101.0, 103.5, 100.8, 103.0),
        bar(103.0, 105.5, 102.8, 105.0), // swing high j=7
        bar(105.0, 105.3, 102.6, 103.0),
        bar(103.0, 103.2, 100.4, 101.0), // swing low j=9
        bar(101.0, 102.2, 100.6, 102.0),
        bar(102.0, 103.4, 101.5, 103.0),
    )

    /** LH+LL zigzag: swing low 97.5 -> swing high 104.2 -> LL 96.5 -> LH 102.5. */
    private fun downtrendZigzag() = mutableListOf(
        bar(103.0, 104.5, 102.5, 103.0),
        bar(103.0, 103.5, 101.5, 102.0),
        bar(102.0, 102.4, 97.5, 98.0), // swing low j=2
        bar(98.0, 100.5, 97.6, 100.0),
        bar(100.0, 104.2, 99.8, 104.0), // swing high j=4
        bar(104.0, 103.8, 102.6, 103.0),
        bar(103.0, 103.0, 101.4, 102.0),
        bar(102.0, 102.3, 96.5, 97.0), // swing low j=7
        bar(97.0, 99.5, 96.6, 99.0),
        bar(99.0, 102.5, 98.6, 102.0), // swing high j=9
        bar(102.0, 102.4, 100.8, 101.0),
        bar(101.0, 101.6, 100.2, 100.8),
    )

    @Test
    fun uptrendHhHlIsBuy() {
        val r = PivotStructureIndicator(cfg).calculate(uptrendZigzag())
        assertEquals(Signal.BUY, r.signal)
        assertEquals("HH+HL", structureLabel(r, 0.0))
    }

    @Test
    fun downtrendLhLlIsSell() {
        val r = PivotStructureIndicator(cfg).calculate(downtrendZigzag())
        assertEquals(Signal.SELL, r.signal)
        assertEquals("LH+LL", structureLabel(r, 1.0))
    }

    @Test
    fun bearishStructureBreakIsStrongSell() {
        val bars = uptrendZigzag()
        // One extra bar closing below the last confirmed swing low (100.4).
        // The break bar is APPENDED: with k=2 the final bars participate in
        // confirming the j=9 swing, so replacing them would unconfirm it.
        bars.add(bar(101.5, 101.7, 99.0, 99.5))
        val r = PivotStructureIndicator(cfg).calculate(bars)
        assertEquals(Signal.STRONG_SELL, r.signal)
        assertEquals(1.0, r.rawValues["structure_break"])
    }

    @Test
    fun bullishStructureBreakIsStrongBuy() {
        val bars = downtrendZigzag()
        // One extra bar closing above the last confirmed swing high (102.5).
        bars.add(bar(101.0, 103.0, 100.5, 102.8))
        val r = PivotStructureIndicator(cfg).calculate(bars)
        assertEquals(Signal.STRONG_BUY, r.signal)
        assertEquals(1.0, r.rawValues["structure_break"])
    }

    @Test
    fun mixedStructureIsNeutral() {
        val bars = uptrendZigzag()
        // Turn the second low into a LOWER low while highs stay HH -> mixed.
        bars[9] = bar(103.0, 103.2, 98.0, 101.0)
        bars[10] = bar(101.0, 102.2, 98.2, 102.0)
        val r = PivotStructureIndicator(cfg).calculate(bars)
        assertEquals(Signal.NEUTRAL, r.signal)
        assertEquals("MIXED", structureLabel(r, 2.0))
    }

    @Test
    fun flatSeriesHasNoSwings() {
        val r = PivotStructureIndicator(cfg).calculate(flat(30))
        assertEquals(Signal.NEUTRAL, r.signal)
        assertTrue("insufficient" in r.reason.lowercase())
    }

    @Test
    fun pivotInsufficientData() {
        val r = PivotStructureIndicator(cfg).calculate(List(4) { bar(100.0, 101.0, 99.0, 100.5) })
        assertEquals(Signal.NEUTRAL, r.signal)
    }

    /** The Kotlin raw map encodes structure numerically; map back for asserts. */
    private fun structureLabel(r: com.diveintocrypto.android.domain.model.IndicatorResult, code: Double): String? =
        if (r.rawValues["structure"] == code) {
            when (code) {
                0.0 -> "HH+HL"
                1.0 -> "LH+LL"
                else -> "MIXED"
            }
        } else {
            null
        }
}
