package com.diveintocrypto.android.domain.indicator

import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.testutil.FixtureLoader
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-language parity for the EXTENDED indicator set (the 42 indicators beyond
 * the original fixture-pinned 15-core). Expected values generated from the Python
 * reference engine (`SignalService({})`, in-code defaults) on the same
 * BTCUSDT 1h × 300 fixture candles — signal + score, exact.
 *
 * Regenerate: run the desktop engine over
 * `desktop/backend/tests/fixtures/btcusdt_1h_300.json` with an empty config and
 * dump `{name: {signal, score}}` for every non-core indicator.
 */
class ExtendedIndicatorsFixtureTest {

    private val candles = FixtureLoader.loadCandles()

    private val indicators: List<BaseIndicator> = listOf(
        SupertrendIndicator(IndicatorConfig()),
        AwesomeOscillatorIndicator(IndicatorConfig()),
        CmfIndicator(IndicatorConfig()),
        SqueezeIndicator(IndicatorConfig()),
        ChoppinessIndicator(IndicatorConfig()),
        VwapIndicator(IndicatorConfig()),
        VortexIndicator(IndicatorConfig()),
        KeltnerBreakoutIndicator(IndicatorConfig()),
        DonchianBreakoutIndicator(IndicatorConfig()),
        ElderRayIndicator(IndicatorConfig()),
        TrixIndicator(IndicatorConfig()),
        CoppockCurveIndicator(IndicatorConfig()),
        KstIndicator(IndicatorConfig()),
        SchaffTrendCycleIndicator(IndicatorConfig()),
        FisherTransformIndicator(IndicatorConfig()),
        ConnorsRsiIndicator(IndicatorConfig()),
        StochRsiIndicator(IndicatorConfig()),
        UltimateOscillatorIndicator(IndicatorConfig()),
        WavetrendIndicator(IndicatorConfig()),
        DpoIndicator(IndicatorConfig()),
        AroonOscillatorIndicator(IndicatorConfig()),
        ChaikinOscillatorIndicator(IndicatorConfig()),
        KlingerOscillatorIndicator(IndicatorConfig()),
        AccumDistLineIndicator(IndicatorConfig()),
        BalanceOfPowerIndicator(IndicatorConfig()),
        RelativeVigorIndexIndicator(IndicatorConfig()),
        MassIndexIndicator(IndicatorConfig()),
        CmoIndicator(IndicatorConfig()),
        TsiIndicator(IndicatorConfig()),
        VwmaCrossIndicator(IndicatorConfig()),
        QstickIndicator(IndicatorConfig()),
        ForceIndexIndicator(IndicatorConfig()),
        BollingerPercentBIndicator(IndicatorConfig()),
        ZscoreReversionIndicator(IndicatorConfig()),
        LinregSlopeIndicator(IndicatorConfig()),
        AtrPercentileIndicator(IndicatorConfig()),
        HistVolPercentileIndicator(IndicatorConfig()),
        HurstIndicator(IndicatorConfig()),
        RangeExpansionIndicator(IndicatorConfig()),
        KalmanTrendIndicator(IndicatorConfig()),
        HalfLifeReversionIndicator(IndicatorConfig()),
        RollingSharpeIndicator(IndicatorConfig()),
    )

    /** Python-reference expected outputs (BTCUSDT 1h × 300, in-code defaults). */
    private val expected: Map<String, Pair<String, Int>> = mapOf(
        "accum_dist_line" to ("NEUTRAL" to 0),
        "aroon_oscillator" to ("NEUTRAL" to 0),
        "atr_percentile" to ("NEUTRAL" to 0),
        "awesome_oscillator" to ("NEUTRAL" to 0),
        "balance_of_power" to ("NEUTRAL" to 0),
        "bollinger_percent_b" to ("NEUTRAL" to 0),
        "chaikin_oscillator" to ("NEUTRAL" to 0),
        "choppiness" to ("BUY" to 1),
        "cmf" to ("SELL" to -1),
        "cmo" to ("NEUTRAL" to 0),
        "connors_rsi" to ("NEUTRAL" to 0),
        "coppock_curve" to ("NEUTRAL" to 0),
        "donchian_breakout" to ("NEUTRAL" to 0),
        "dpo" to ("NEUTRAL" to 0),
        "elder_ray" to ("NEUTRAL" to 0),
        "fisher_transform" to ("BUY" to 1),
        "force_index" to ("BUY" to 1),
        "half_life_reversion" to ("NEUTRAL" to 0),
        "hist_vol_percentile" to ("NEUTRAL" to 0),
        "hurst" to ("STRONG_SELL" to -2),
        "kalman_trend" to ("BUY" to 1),
        "keltner_breakout" to ("NEUTRAL" to 0),
        "klinger_oscillator" to ("NEUTRAL" to 0),
        "kst" to ("SELL" to -1),
        "linreg_slope" to ("NEUTRAL" to 0),
        "mass_index" to ("NEUTRAL" to 0),
        "qstick" to ("BUY" to 1),
        "range_expansion" to ("NEUTRAL" to 0),
        "relative_vigor_index" to ("NEUTRAL" to 0),
        "rolling_sharpe" to ("NEUTRAL" to 0),
        "schaff_trend_cycle" to ("STRONG_BUY" to 2),
        "squeeze" to ("BUY" to 1),
        "stoch_rsi" to ("NEUTRAL" to 0),
        "supertrend" to ("SELL" to -1),
        "trix" to ("NEUTRAL" to 0),
        "tsi" to ("NEUTRAL" to 0),
        "ultimate_oscillator" to ("NEUTRAL" to 0),
        "vortex" to ("STRONG_BUY" to 2),
        "vwap" to ("BUY" to 1),
        "vwma_cross" to ("SELL" to -1),
        "wavetrend" to ("NEUTRAL" to 0),
        "zscore_reversion" to ("NEUTRAL" to 0),
    )

    @Test
    fun allExtendedIndicatorsPresent() {
        assertEquals(expected.keys, indicators.map { it.name }.toSet(),
            "every extended indicator must be instantiated exactly once")
    }

    @Test
    fun extendedParityAgainstPythonReference() {
        val failures = StringBuilder()
        for (ind in indicators) {
            val r = ind.calculate(candles)
            val (sig, score) = expected.getValue(ind.name)
            if (r.signal.name != sig || r.score != score) {
                failures.append("${ind.name}: kotlin=${r.signal.name}/${r.score} python=$sig/$score (${r.reason})\n")
            }
        }
        if (failures.isNotEmpty()) throw AssertionError("Extended parity mismatches:\n$failures")
    }
}
