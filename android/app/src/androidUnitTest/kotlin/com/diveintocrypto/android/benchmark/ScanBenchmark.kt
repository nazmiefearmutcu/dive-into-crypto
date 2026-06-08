package com.diveintocrypto.android.benchmark

import com.diveintocrypto.android.domain.consensus.ConsensusEngine
import com.diveintocrypto.android.domain.indicator.AdxDiIndicator
import com.diveintocrypto.android.domain.indicator.AtrFilterIndicator
import com.diveintocrypto.android.domain.indicator.BaseIndicator
import com.diveintocrypto.android.domain.indicator.BollingerIndicator
import com.diveintocrypto.android.domain.indicator.CciIndicator
import com.diveintocrypto.android.domain.indicator.EmaCrossIndicator
import com.diveintocrypto.android.domain.indicator.IchimokuIndicator
import com.diveintocrypto.android.domain.indicator.MacdIndicator
import com.diveintocrypto.android.domain.indicator.MfiIndicator
import com.diveintocrypto.android.domain.indicator.ObvIndicator
import com.diveintocrypto.android.domain.indicator.PsarIndicator
import com.diveintocrypto.android.domain.indicator.RocIndicator
import com.diveintocrypto.android.domain.indicator.RsiIndicator
import com.diveintocrypto.android.domain.indicator.SmaCrossIndicator
import com.diveintocrypto.android.domain.indicator.StochasticIndicator
import com.diveintocrypto.android.domain.indicator.WilliamsRIndicator
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.domain.model.IndicatorConfig
import com.diveintocrypto.android.domain.model.IndicatorResult
import kotlin.math.PI
import kotlin.math.sin
import kotlin.system.measureNanoTime
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OFFLINE, compute-only micro-benchmark for the Dive Into Crypto consensus engine.
 *
 * No network, no Binance, no Android framework — pure JVM. Generates a
 * deterministic synthetic OHLCV series (sine + linear drift) using the real
 * [Candle] constructor and runs every indicator's real `calculate(...)` plus a
 * full [ConsensusEngine.evaluate] over the resulting 15 [IndicatorResult]s.
 *
 * It is a perf probe: it asserts only a trivially-true sanity invariant so it
 * never fails on slow CI. Numbers are printed as a GitHub-Markdown table.
 */
class ScanBenchmark {

    private val barCount = 300
    private val warmupIters = 50
    private val timedIters = 500

    /** Deterministic synthetic candles: sine oscillation + slow upward drift. */
    private fun syntheticCandles(n: Int): List<Candle> {
        val basePrice = 80_000.0
        val amplitude = 1_500.0
        val drift = 8.0 // per bar
        val period = 48.0 // bars per sine cycle
        val intervalMs = 3_600_000L // 1h bars
        return (0 until n).map { i ->
            val mid = basePrice + drift * i + amplitude * sin(2.0 * PI * i / period)
            // intrabar wiggle derived from the bar index (deterministic, no RNG).
            val wiggle = 60.0 * sin(2.0 * PI * i / 7.0)
            val open = mid - wiggle
            val close = mid + wiggle
            val high = maxOf(open, close) + 90.0 + 30.0 * sin(2.0 * PI * i / 11.0)
            val low = minOf(open, close) - 90.0 - 30.0 * sin(2.0 * PI * i / 13.0)
            val volume = 300.0 + 150.0 * sin(2.0 * PI * i / 9.0) + i * 0.5
            val openTime = i * intervalMs
            Candle(
                openTime = openTime,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = if (volume < 1.0) 1.0 else volume,
                closeTime = openTime + intervalMs - 1,
            )
        }
    }

    /** Real default-config constructors, mirroring the fixture tests. */
    private fun allIndicators(): List<Pair<String, BaseIndicator>> = listOf(
        "rsi" to RsiIndicator(IndicatorConfig(mapOf(
            "period" to 14.0, "strong_buy" to 25.0, "buy" to 35.0, "sell" to 65.0, "strong_sell" to 80.0,
        ))),
        "stochastic" to StochasticIndicator(IndicatorConfig(mapOf(
            "k_period" to 14.0, "d_period" to 3.0, "oversold" to 20.0, "overbought" to 80.0,
        ))),
        "williams_r" to WilliamsRIndicator(IndicatorConfig(mapOf(
            "period" to 14.0, "oversold" to -80.0, "overbought" to -20.0,
        ))),
        "cci" to CciIndicator(IndicatorConfig(mapOf(
            "period" to 20.0, "buy" to -100.0, "strong_buy" to -200.0, "sell" to 100.0, "strong_sell" to 200.0,
        ))),
        "macd" to MacdIndicator(IndicatorConfig(mapOf(
            "fast_period" to 12.0, "slow_period" to 26.0, "signal_period" to 9.0, "strong_histogram_threshold" to 0.5,
        ))),
        "ema_cross" to EmaCrossIndicator(IndicatorConfig(mapOf(
            "short_period" to 9.0, "long_period" to 21.0, "strong_divergence_pct" to 0.02,
        ))),
        "sma_cross" to SmaCrossIndicator(IndicatorConfig(mapOf(
            "short_period" to 10.0, "long_period" to 50.0, "strong_divergence_pct" to 0.02,
        ))),
        "ichimoku" to IchimokuIndicator(IndicatorConfig(mapOf(
            "tenkan_period" to 9.0, "kijun_period" to 26.0, "senkou_b_period" to 52.0,
        ))),
        "psar" to PsarIndicator(IndicatorConfig(mapOf(
            "af_start" to 0.02, "af_increment" to 0.02, "af_max" to 0.20,
        ))),
        "bollinger" to BollingerIndicator(IndicatorConfig(mapOf(
            "period" to 20.0, "std_dev" to 2.0, "squeeze_threshold" to 0.02,
        ))),
        "mfi" to MfiIndicator(IndicatorConfig(mapOf(
            "period" to 14.0, "strong_buy" to 20.0, "buy" to 30.0, "sell" to 70.0, "strong_sell" to 80.0,
        ))),
        "obv" to ObvIndicator(IndicatorConfig(mapOf(
            "sma_period" to 20.0, "divergence_lookback" to 10.0,
        ))),
        "roc" to RocIndicator(IndicatorConfig(mapOf(
            "period" to 12.0, "strong_threshold" to 5.0, "weak_threshold" to 1.0,
        ))),
        "adx_di" to AdxDiIndicator(IndicatorConfig(mapOf(
            "period" to 14.0, "strong_trend" to 25.0, "weak_trend" to 15.0,
        ))),
        "atr_filter" to AtrFilterIndicator(IndicatorConfig(mapOf(
            "period" to 14.0, "high_volatility_multiplier" to 2.0,
        ))),
    )

    /** Median ns/op over [timedIters] timed iterations after [warmupIters] warmup. */
    private fun benchNs(block: () -> Unit): Double {
        repeat(warmupIters) { block() }
        val samples = DoubleArray(timedIters)
        for (i in 0 until timedIters) {
            samples[i] = measureNanoTime { block() }.toDouble()
        }
        samples.sort()
        return samples[timedIters / 2] // median, robust to GC pauses / JIT outliers
    }

    private fun opsPerSec(nsPerOp: Double): Double = if (nsPerOp > 0.0) 1_000_000_000.0 / nsPerOp else 0.0

    private fun fmtOps(ops: Double): String = when {
        ops >= 1_000_000.0 -> "%,.0f".format(ops)
        ops >= 1_000.0 -> "%,.0f".format(ops)
        else -> "%.1f".format(ops)
    }

    @Test
    fun benchmark() {
        val candles = syntheticCandles(barCount)
        val indicators = allIndicators()
        val engine = ConsensusEngine()

        // ---- (a) per-indicator ----
        val perIndicatorOps = LinkedHashMap<String, Double>()
        // sanity: every indicator produces a result without throwing.
        var sanity = true
        for ((name, ind) in indicators) {
            val r: IndicatorResult = ind.calculate(candles)
            sanity = sanity && r.name.isNotEmpty()
            val ns = benchNs { ind.calculate(candles) }
            perIndicatorOps[name] = opsPerSec(ns)
        }

        // ---- (b) one full consensus evaluation (all 15 indicators + engine.evaluate) ----
        fun fullEvaluation() {
            val results = ArrayList<IndicatorResult>(indicators.size)
            for ((_, ind) in indicators) results.add(ind.calculate(candles))
            engine.evaluate(results)
        }
        val consensusNs = benchNs { fullEvaluation() }
        val consensusOps = opsPerSec(consensusNs)

        // ---- (c) full symbol scan = 12 timeframes (consecutive consensus evals) ----
        val timeframesPerSymbol = 12
        fun fullSymbolScan() {
            repeat(timeframesPerSymbol) { fullEvaluation() }
        }
        val symbolNs = benchNs { fullSymbolScan() }
        val symbolsPerSec = opsPerSec(symbolNs)

        // ---- projection: 500-symbol universe, compute-only (no network) ----
        val universe = 500
        val universeSeconds = (symbolNs * universe) / 1_000_000_000.0

        // ---- print GitHub-Markdown ----
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("| Benchmark | ops/sec |")
        sb.appendLine("| --- | ---: |")
        for ((name, ops) in perIndicatorOps) {
            sb.appendLine("| indicator: `$name` | ${fmtOps(ops)} |")
        }
        sb.appendLine("| **consensus eval (15 indicators + engine)** | ${fmtOps(consensusOps)} |")
        sb.appendLine("| **full symbol scan (12 timeframes)** | ${fmtOps(symbolsPerSec)} |")
        sb.appendLine()
        sb.appendLine(
            "Projected wall-time to scan a $universe-symbol universe (compute-only, " +
                "network excluded): %.3f seconds".format(universeSeconds)
        )
        sb.appendLine(
            "  (per-symbol = $timeframesPerSymbol consensus evals; " +
                "%.1f symbols/sec single-threaded)".format(symbolsPerSec)
        )
        println(sb.toString())

        // perf probe — always pass.
        assertTrue("benchmark ran and produced indicator results", sanity && perIndicatorOps.size == 15)
    }
}
