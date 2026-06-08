package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.testutil.FixtureLoader
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One test class per indicator, fixture-pinned. Each indicator's calculated
 * result on the BTCUSDT 1h x300 fixture must match the Python reference
 * recorded in btcusdt_1h_300_expected.json.
 */
class IndicatorsFixtureTest {

    private val candles = FixtureLoader.loadCandles()

    @Test
    fun `RSI matches Python reference`() {
        val cfg = IndicatorConfig(mapOf(
            "period" to 14.0, "strong_buy" to 25.0, "buy" to 35.0,
            "sell" to 65.0, "strong_sell" to 80.0,
        ))
        val expected = FixtureLoader.expectedFor("rsi")
        val r = RsiIndicator(cfg).calculate(candles)
        assertEquals(Signal.valueOf(expected.signal), r.signal)
        assertEquals(expected.score, r.score)
        assertEquals(expected.rawValues["rsi"]!!, r.rawValues["rsi"]!!, 0.05)
    }

    @Test
    fun `MACD matches Python reference`() {
        val cfg = IndicatorConfig(mapOf(
            "fast_period" to 12.0, "slow_period" to 26.0,
            "signal_period" to 9.0, "strong_histogram_threshold" to 0.5,
        ))
        val expected = FixtureLoader.expectedFor("macd")
        val r = MacdIndicator(cfg).calculate(candles)
        assertEquals(Signal.valueOf(expected.signal), r.signal)
        assertEquals(expected.score, r.score)
        assertEquals(expected.rawValues["macd"]!!, r.rawValues["macd"]!!, 0.05)
        assertEquals(expected.rawValues["signal"]!!, r.rawValues["signal"]!!, 0.05)
        assertEquals(expected.rawValues["histogram"]!!, r.rawValues["histogram"]!!, 0.05)
    }

    @Test
    fun `Bollinger matches Python reference`() {
        val cfg = IndicatorConfig(mapOf(
            "period" to 20.0, "std_dev" to 2.0, "squeeze_threshold" to 0.02,
        ))
        val expected = FixtureLoader.expectedFor("bollinger")
        val r = BollingerIndicator(cfg).calculate(candles)
        assertEquals(Signal.valueOf(expected.signal), r.signal)
        assertEquals(expected.rawValues["upper"]!!, r.rawValues["upper"]!!, 0.1)
        assertEquals(expected.rawValues["lower"]!!, r.rawValues["lower"]!!, 0.1)
        assertEquals(expected.rawValues["sma"]!!, r.rawValues["sma"]!!, 0.1)
    }

    @Test
    fun `EMA cross matches Python reference`() {
        val cfg = IndicatorConfig(mapOf(
            "short_period" to 9.0, "long_period" to 21.0, "strong_divergence_pct" to 0.02,
        ))
        val expected = FixtureLoader.expectedFor("ema_cross")
        val r = EmaCrossIndicator(cfg).calculate(candles)
        assertEquals(Signal.valueOf(expected.signal), r.signal)
        assertEquals(expected.rawValues["ema_short"]!!, r.rawValues["ema_short"]!!, 0.1)
        assertEquals(expected.rawValues["ema_long"]!!, r.rawValues["ema_long"]!!, 0.1)
    }

    @Test
    fun `Stochastic matches Python reference`() {
        val cfg = IndicatorConfig(mapOf(
            "k_period" to 14.0, "d_period" to 3.0,
            "oversold" to 20.0, "overbought" to 80.0,
        ))
        val expected = FixtureLoader.expectedFor("stochastic")
        val r = StochasticIndicator(cfg).calculate(candles)
        assertEquals(Signal.valueOf(expected.signal), r.signal)
        assertEquals(expected.rawValues["k"]!!, r.rawValues["k"]!!, 0.1)
        assertEquals(expected.rawValues["d"]!!, r.rawValues["d"]!!, 0.1)
    }
}
