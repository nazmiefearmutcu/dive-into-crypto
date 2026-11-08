package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.Signal
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression pins for verified indicator defects. Mirrors
 * desktop/backend/tests/test_indicator_regressions.py: the same deterministic
 * synthetic series (31-bit LCG, no RNG) and the same expected outcomes, so both
 * editions assert identical behaviour.
 */
class IndicatorRegressionTest {

    private val nan = Double.NaN

    private fun bar(o: Double, h: Double, l: Double, c: Double, v: Double = 1.0): Candle = Candle(
        openTime = 0L, open = o, high = h, low = l, close = c, volume = v, closeTime = 0L,
    )

    private fun lcgSteps(n: Int, seed: Long = 12345L): List<Double> {
        var x = seed
        val out = ArrayList<Double>(n)
        repeat(n) {
            x = (1103515245L * x + 12345L) % (1L shl 31)
            out.add(x.toDouble() / 2147483648.0 - 0.5)
        }
        return out
    }

    private fun lcgWalk(n: Int): List<Candle> {
        val close = ArrayList<Double>()
        close.add(100.0)
        for (step in lcgSteps(n - 1)) close.add(close.last() + step)
        return close.map { bar(it, it + 0.2, it - 0.2, it) }
    }

    // ------------------------------------------------------------------
    // engulfing: the current body must contain the prior body
    // ------------------------------------------------------------------

    @Test
    fun engulfingBullishNonContainingBodyIsNeutral() {
        val bars = List(3) { bar(100.0, 100.5, 99.5, 100.0) } + listOf(
            bar(101.0, 101.2, 99.8, 100.0),
            bar(100.5, 101.6, 100.3, 101.5),
        )
        val r = EngulfingIndicator(IndicatorConfig()).calculate(bars)
        assertEquals(Signal.NEUTRAL, r.signal)
    }

    @Test
    fun engulfingBearishNonContainingBodyIsNeutral() {
        val bars = List(3) { bar(100.0, 100.5, 99.5, 100.0) } + listOf(
            bar(100.0, 101.2, 99.8, 101.0),
            bar(100.5, 100.6, 99.4, 99.5),
        )
        val r = EngulfingIndicator(IndicatorConfig()).calculate(bars)
        assertEquals(Signal.NEUTRAL, r.signal)
    }

    // ------------------------------------------------------------------
    // half_life_reversion: statistical gate, sub-bar floor, finite window
    // ------------------------------------------------------------------

    @Test
    fun halfLifeRandomWalkIsNeutralBySignificanceGate() {
        val r = HalfLifeReversionIndicator(IndicatorConfig()).calculate(lcgWalk(60))
        assertEquals(Signal.NEUTRAL, r.signal)
        assertTrue("not statistically mean-reverting" in r.reason, r.reason)
    }

    @Test
    fun halfLifeAlternatingSeriesBetaBelowMinusOneIsNeutral() {
        // y alternates 100/110, so beta = -2 (an unstable AR(1), no finite half-life).
        val bars = (0 until 60).map { i ->
            val c = if (i % 2 == 0) 100.0 else 110.0
            bar(c, c, c, c)
        }
        val r = HalfLifeReversionIndicator(IndicatorConfig()).calculate(bars)
        assertEquals(Signal.NEUTRAL, r.signal)
        assertTrue("unstable" in r.reason, r.reason)
    }

    @Test
    fun halfLifeNonFiniteWindowIsNeutralNotAnError() {
        val closes = MutableList(60) { i -> 100.0 + 0.01 * i }
        closes[50] = nan
        val r = HalfLifeReversionIndicator(IndicatorConfig()).calculate(closes.map { bar(it, it, it, it) })
        assertEquals(Signal.NEUTRAL, r.signal)
        assertTrue("insufficient" in r.reason.lowercase(), r.reason)
    }

    // ------------------------------------------------------------------
    // A NaN current bar must never reach a directional branch
    // ------------------------------------------------------------------

    @Test
    fun atrPercentileNanLastBarIsNeutral() {
        val rows = ArrayList<Candle>()
        for (i in 0 until 120) {
            val c = 100.0 + 0.01 * i
            rows.add(bar(c, c + 0.2, c - 0.2, c))
        }
        var price = 101.2
        repeat(30) {
            price += 0.8
            rows.add(bar(price - 0.4, price + 2.0, price - 2.0, price))
        }
        rows.add(bar(nan, nan, nan, nan))
        val r = AtrPercentileIndicator(IndicatorConfig()).calculate(rows)
        assertEquals(Signal.NEUTRAL, r.signal)
        assertEquals("ATR%ile data invalid", r.reason)
    }

    @Test
    fun histVolPercentileNanLastBarIsNeutral() {
        val rows = ArrayList<Candle>()
        var price = 100.0
        repeat(150) {
            price *= 1.0005
            rows.add(bar(price, price + 0.1, price - 0.1, price))
        }
        for (i in 0 until 25) {
            price *= if (i % 2 == 0) 1.03 else 0.98
            rows.add(bar(price, price + 0.5, price - 0.5, price))
        }
        rows.add(bar(nan, nan, nan, nan))
        val r = HistVolPercentileIndicator(IndicatorConfig()).calculate(rows)
        assertEquals(Signal.NEUTRAL, r.signal)
        assertEquals("HV%ile data invalid", r.reason)
    }

    @Test
    fun hurstNanInsideWindowIsNeutral() {
        val closes = MutableList(80) { i -> 100.0 * 1.003.pow(i) }
        closes[75] = nan
        val r = HurstIndicator(IndicatorConfig()).calculate(closes.map { bar(it, it, it, it) })
        assertEquals(Signal.NEUTRAL, r.signal)
        assertEquals("Hurst insufficient data", r.reason)
    }

    // ------------------------------------------------------------------
    // atr_filter: one cutoff set, explicit warm-up when the baseline is missing
    // ------------------------------------------------------------------

    @Test
    fun atrFilterMissingBaselineIsExplicitNotNormal() {
        val rows = (0 until 16).map { i ->
            bar(100.0 + 0.1 * i, 101.0 + 0.1 * i, 99.0 + 0.1 * i, 100.5 + 0.1 * i)
        }
        val r = AtrFilterIndicator(IndicatorConfig()).calculate(rows)
        assertEquals(Signal.NEUTRAL, r.signal)
        assertTrue("baseline warming up" in r.reason, r.reason)
        assertTrue(r.rawValues["volatility"] == null)
    }

    @Test
    fun atrFilterLabelMatchesReasonTextOnEveryPrefix() {
        val steps = lcgSteps(260, seed = 7L)
        val close = ArrayList<Double>()
        close.add(100.0)
        steps.forEachIndexed { k, s ->
            val scale = if ((k / 30) % 2 == 0) 0.02 else 10.0 // calm and volatile phases
            close.add(close.last() + s * scale)
        }
        val rows = close.indices.map { i ->
            val c = close[i]
            val s = if (i == 0) 0.0 else steps[i - 1]
            bar(c, c + abs(s) + 0.1, c - abs(s) - 0.1, c)
        }
        val seen = HashSet<Double>()
        var n = 60
        while (n <= rows.size) {
            val r = AtrFilterIndicator(IndicatorConfig()).calculate(rows.subList(0, n))
            val label = r.rawValues["volatility"]!!
            seen.add(label)
            assertEquals(label == 0.0, "Low ATR volatility" in r.reason, "n=$n ${r.reason}")
            assertEquals(label == 2.0, "High ATR volatility" in r.reason, "n=$n ${r.reason}")
            n += 7
        }
        assertEquals(setOf(0.0, 1.0, 2.0), seen)
    }

    // ------------------------------------------------------------------
    // squeeze: warm-up is insufficient data, not a momentum SELL
    // ------------------------------------------------------------------

    @Test
    fun squeezeWarmUpIsInsufficientNotSell() {
        for (n in listOf(10, 19)) {
            val rows = (0 until n).map { i -> bar(100.0 + 0.1 * i, 101.0, 99.0, 100.0 + 0.1 * i) }
            val r = SqueezeIndicator(IndicatorConfig()).calculate(rows)
            assertEquals(Signal.NEUTRAL, r.signal, "n=$n")
            assertEquals("Squeeze data insufficient", r.reason, "n=$n")
        }
    }

    // ------------------------------------------------------------------
    // linreg_slope: first-to-last move over period-1 steps
    // ------------------------------------------------------------------

    @Test
    fun linregSlopePctIsFirstToLastMove() {
        val closes = (0 until 20).map { 100.0 + 0.5 * it }
        val r = LinregSlopeIndicator(IndicatorConfig()).calculate(closes.map { bar(it, it, it, it) })
        val mean = closes.sum() / closes.size
        val expected = 0.5 * 19 / mean
        assertEquals(1.0, r.rawValues["r2"]!!, 1e-4)
        assertEquals(expected, r.rawValues["slope_pct"]!!, 1e-5)
    }

    // ------------------------------------------------------------------
    // mass_index: the setback is an event on the current bar
    // ------------------------------------------------------------------

    @Test
    fun massIndexReversalVoteFiresOncePerSetback() {
        val rows = (0 until 130).map { i ->
            val c = 100.0 + 0.2 * i
            val half = if (i in 60..75) 1.5 else 0.5 // range bulge at bars 60-75
            bar(c, c + half, c - half, c)
        }
        val directional = ArrayList<Int>()
        for (n in 60..rows.size) {
            val s = MassIndexIndicator(IndicatorConfig()).calculate(rows.subList(0, n)).signal
            if (s == Signal.SELL || s == Signal.STRONG_SELL) directional.add(n)
        }
        // Level semantics voted on ~25 consecutive bars; event semantics votes on one bar.
        // The lower bound matters: a silenced indicator must fail here, not pass vacuously.
        assertTrue(directional.size in 1..2, "directional votes at $directional")
    }

    // ------------------------------------------------------------------
    // vwap: zero volume has no VWAP
    // ------------------------------------------------------------------

    @Test
    fun vwapZeroVolumeWindowIsInsufficient() {
        val rows = (0 until 25).map { i ->
            bar(100.0 + i, 101.0 + i, 99.0 + i, 100.5 + i, 0.0)
        }
        val r = VwapIndicator(IndicatorConfig()).calculate(rows)
        assertEquals(Signal.NEUTRAL, r.signal)
        assertEquals("VWAP data insufficient", r.reason)
    }

    // ------------------------------------------------------------------
    // supertrend / donchian / connors: degenerate periods do not throw
    // ------------------------------------------------------------------

    @Test
    fun supertrendPeriodZeroAndExactLengthAreInsufficient() {
        val rows = (0 until 12).map { i -> bar(100.0 + i, 101.0 + i, 99.0 + i, 100.5 + i) }
        val zero = SupertrendIndicator(IndicatorConfig(mapOf("period" to 0.0))).calculate(rows)
        assertEquals(Signal.NEUTRAL, zero.signal)
        val exact = SupertrendIndicator(IndicatorConfig(mapOf("period" to 12.0))).calculate(rows)
        assertEquals(Signal.NEUTRAL, exact.signal)
    }

    @Test
    fun donchianZeroExitPeriodIsInsufficientNotError() {
        val rows = (0 until 40).map { i -> bar(100.0 + i, 101.0 + i, 99.0 + i, 100.5 + i) }
        val r = DonchianBreakoutIndicator(IndicatorConfig(mapOf("exit_period" to 0.0))).calculate(rows)
        assertEquals(Signal.NEUTRAL, r.signal)
    }

    @Test
    fun connorsRsiZeroRsiPeriodIsInsufficientNotError() {
        val rows = (0 until 150).map { i -> bar(100.0 + (i % 3), 101.0, 99.0, 100.0 + (i % 3)) }
        val r = ConnorsRsiIndicator(IndicatorConfig(mapOf("rsi_period" to 0.0))).calculate(rows)
        assertEquals(Signal.NEUTRAL, r.signal)
    }

    // ------------------------------------------------------------------
    // bollinger: squeeze breakouts follow the breakout direction
    // ------------------------------------------------------------------

    @Test
    fun bollingerSqueezeBreakoutDirections() {
        val closes = (0 until 20).map { 100.0 + 0.1 * (it % 2) }
        val cfg = IndicatorConfig(mapOf("period" to 20.0, "squeeze_threshold" to 0.5))
        val down = (closes + 90.0).map { bar(it, it, it, it) }
        val up = (closes + 110.0).map { bar(it, it, it, it) }
        assertEquals(Signal.SELL, BollingerIndicator(cfg).calculate(down).signal)
        assertEquals(Signal.BUY, BollingerIndicator(cfg).calculate(up).signal)
    }

    // ------------------------------------------------------------------
    // obv: divergence normalised by window volume, not by |base OBV|
    // ------------------------------------------------------------------

    @Test
    fun obvDivergenceUsesWindowVolumeScale() {
        val rows = ArrayList<Candle>()
        var price = 100.0
        repeat(30) { // long rising prefix: cumulative OBV builds to about +30,000
            price += 0.1
            rows.add(bar(price, price, price, price, 1000.0))
        }
        for (i in 0 until 10) { // up +0.5 on volume 100, down -0.2 on volume 300
            price += if (i % 2 == 0) 0.5 else -0.2
            rows.add(bar(price, price, price, price, if (i % 2 == 0) 100.0 else 300.0))
        }
        val r = ObvIndicator(IndicatorConfig()).calculate(rows)
        // OBV change -1000 over window volume 2000 = -0.5, not -1000 / 30,000.
        assertEquals(Signal.SELL, r.signal)
        assertTrue("bearish divergence" in r.reason, r.reason)
    }

    // ------------------------------------------------------------------
    // rsi / mfi: a flat window is 50 and goes through the threshold ladder
    // ------------------------------------------------------------------

    @Test
    fun rsiFlatWindowMapsTo50ThroughThresholds() {
        val rows = List(30) { bar(100.0, 100.0, 100.0, 100.0) }
        val r = RsiIndicator(IndicatorConfig(mapOf("sell" to 40.0))).calculate(rows)
        assertEquals(50.0, r.rawValues["rsi"]!!, 1e-9)
        assertEquals(Signal.SELL, r.signal)
        assertTrue("overbought zone" in r.reason, r.reason)
    }

    @Test
    fun mfiFlatWindowMapsTo50ThroughThresholds() {
        val rows = List(30) { bar(100.0, 100.0, 100.0, 100.0, 1000.0) }
        val r = MfiIndicator(IndicatorConfig(mapOf("sell" to 40.0))).calculate(rows)
        assertEquals(50.0, r.rawValues["mfi"]!!, 1e-9)
        assertEquals(Signal.SELL, r.signal)
    }

    // ------------------------------------------------------------------
    // awesome_oscillator: a single valid AO bar is warm-up, not a trend label
    // ------------------------------------------------------------------

    @Test
    fun awesomeOscillatorSingleValidBarIsWarmUp() {
        val rows = (0 until 34).map { i ->
            bar(100.0 + 0.1 * i, 101.0 + 0.1 * i, 99.0 + 0.1 * i, 100.5 + 0.1 * i)
        }
        val r = AwesomeOscillatorIndicator(IndicatorConfig()).calculate(rows)
        assertEquals(Signal.NEUTRAL, r.signal)
        assertEquals("AO warming up", r.reason)
    }

    // ------------------------------------------------------------------
    // period 1 has no sample std: insufficient data, not an exception
    // ------------------------------------------------------------------

    @Test
    fun squeezeAndBollingerPeriodOneAreInsufficient() {
        val rows = (0 until 30).map { i -> bar(100.0 + 0.1 * i, 101.0, 99.0, 100.0 + 0.1 * i) }
        val squeeze = SqueezeIndicator(IndicatorConfig(mapOf("period" to 1.0))).calculate(rows)
        assertEquals(Signal.NEUTRAL, squeeze.signal)
        val bands = BollingerIndicator(IndicatorConfig(mapOf("period" to 1.0))).calculate(rows)
        assertEquals(Signal.NEUTRAL, bands.signal)
    }

    // ------------------------------------------------------------------
    // cmf: zero volume is insufficient data, not a 0.0 reading
    // ------------------------------------------------------------------

    @Test
    fun cmfZeroVolumeWindowIsInsufficient() {
        val rows = List(25) { bar(100.0, 101.0, 99.0, 100.5, 0.0) }
        val r = CmfIndicator(IndicatorConfig()).calculate(rows)
        assertEquals(Signal.NEUTRAL, r.signal)
        assertEquals("CMF data insufficient", r.reason)
    }
}
