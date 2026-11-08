package com.diveintocrypto.android.worker

import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.worker.DiveScanWorker.SymbolPhase1Result
import org.junit.Assert.assertEquals
import org.junit.Test

class DiveScanWorkerAggregationTest {

    private fun row(
        symbol: String,
        tf: String,
        signal: Signal,
        confidence: Int,
        price: Double,
        score: Double = 10.0,
    ) = SymbolPhase1Result(
        symbol = symbol,
        tf = tf,
        signal = signal,
        confidence = confidence,
        price = price,
        finalScore = score,
    )

    @Test
    fun equalConfidenceTieTakesThePriceOfTheEarlierTimeframe() {
        // PHASE1_TFS is 1d, 12h, 8h: 1d must win the tie whatever order the fetches finished in.
        val rows = listOf(
            row("BTCUSDT", "8h", Signal.BUY, 60, price = 200.0),
            row("BTCUSDT", "1d", Signal.BUY, 60, price = 100.0),
        )
        val verdict = DiveScanWorker.aggregateBySymbol(rows).single()
        assertEquals(100.0, verdict.price, 0.0)
    }

    @Test
    fun aggregationDoesNotDependOnCompletionOrder() {
        val rows = listOf(
            row("ETHUSDT", "1d", Signal.SELL, 55, price = 10.0, score = 5.0),
            row("ETHUSDT", "12h", Signal.SELL, 55, price = 11.0, score = 5.0),
            row("SOLUSDT", "1d", Signal.BUY, 70, price = 20.0, score = 9.0),
            row("SOLUSDT", "8h", Signal.BUY, 70, price = 21.0, score = 9.0),
            row("XRPUSDT", "12h", Signal.BUY, 40, price = 1.0, score = 9.0),
        )
        assertEquals(
            DiveScanWorker.aggregateBySymbol(rows),
            DiveScanWorker.aggregateBySymbol(rows.reversed()),
        )
    }

    @Test
    fun directionalVerdictsDropNeutralSymbolsButAggregationKeepsThem() {
        val rows = listOf(
            row("BTCUSDT", "1d", Signal.BUY, 60, price = 100.0),
            // Exact buy/sell tie across two timeframes: NEUTRAL, must not be published as a call.
            row("DOGEUSDT", "1d", Signal.BUY, 50, price = 0.1, score = 4.0),
            row("DOGEUSDT", "12h", Signal.SELL, 50, price = 0.1, score = 4.0),
            row("ADAUSDT", "1d", Signal.NEUTRAL, 30, price = 0.5, score = 0.0),
        )
        assertEquals(listOf("BTCUSDT"), DiveScanWorker.directionalVerdicts(rows).map { it.symbol })
        assertEquals(3, DiveScanWorker.aggregateBySymbol(rows).size)
    }
}
