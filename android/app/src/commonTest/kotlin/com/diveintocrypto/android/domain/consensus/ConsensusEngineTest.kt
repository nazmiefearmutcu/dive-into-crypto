package com.diveintocrypto.android.domain.consensus

import com.diveintocrypto.android.domain.model.IndicatorResult
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.data.binance.OpenInterestPoint
import com.diveintocrypto.android.data.binance.LongShortRatioPoint
import com.diveintocrypto.android.data.binance.TakerLongShortRatioPoint
import com.diveintocrypto.android.data.binance.FundingRatePoint
import com.diveintocrypto.android.domain.model.Candle
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class ConsensusEngineTest {

    private val engine = ConsensusEngine()

    private fun r(name: String, signal: Signal) =
        IndicatorResult(name = name, signal = signal, reason = "")

    @Test
    fun `all-buy with all weight produces STRONG_BUY`() {
        val out = engine.evaluate(listOf(
            r("rsi", Signal.STRONG_BUY),
            r("macd", Signal.STRONG_BUY),
            r("bollinger", Signal.STRONG_BUY),
            r("ema_cross", Signal.STRONG_BUY),
            r("stochastic", Signal.STRONG_BUY),
        ))
        assertEquals(Signal.STRONG_BUY, out.finalSignal)
        assertTrue(out.confidence >= 80, "confidence should be high")
        assertTrue(out.shouldTrade)
    }

    @Test
    fun `all-sell produces STRONG_SELL`() {
        val out = engine.evaluate(listOf(
            r("rsi", Signal.STRONG_SELL),
            r("macd", Signal.STRONG_SELL),
            r("bollinger", Signal.STRONG_SELL),
            r("ema_cross", Signal.STRONG_SELL),
            r("stochastic", Signal.STRONG_SELL),
        ))
        assertEquals(Signal.STRONG_SELL, out.finalSignal)
        assertTrue(out.shouldTrade)
    }

    @Test
    fun `even split forces NEUTRAL via conflict ratio`() {
        val out = engine.evaluate(listOf(
            r("rsi", Signal.BUY),
            r("macd", Signal.BUY),
            r("bollinger", Signal.SELL),
            r("ema_cross", Signal.SELL),
            r("stochastic", Signal.NEUTRAL),
        ))
        assertEquals(Signal.NEUTRAL, out.finalSignal)
        assertFalse(out.shouldTrade)
    }

    @Test
    fun `all neutral never trades`() {
        val out = engine.evaluate(listOf(
            r("rsi", Signal.NEUTRAL),
            r("macd", Signal.NEUTRAL),
            r("bollinger", Signal.NEUTRAL),
            r("ema_cross", Signal.NEUTRAL),
            r("stochastic", Signal.NEUTRAL),
        ))
        assertEquals(Signal.NEUTRAL, out.finalSignal)
        assertFalse(out.shouldTrade)
    }

    private fun makeCandles(size: Int, startPrice: Double, step: Double): List<Candle> {
        return (0 until size).map { i ->
            Candle(
                openTime = i * 60000L,
                open = startPrice + i * step,
                high = startPrice + i * step + 1.0,
                low = startPrice + i * step - 1.0,
                close = startPrice + (i + 1) * step,
                volume = 100.0,
                closeTime = (i + 1) * 60000L - 1
            )
        }
    }

    private fun makeOi(size: Int, startVal: Double, step: Double): List<OpenInterestPoint> {
        return (0 until size).map { i ->
            OpenInterestPoint(
                timestamp = i * 60000L,
                sumOpenInterest = startVal + i * step,
                sumOpenInterestValue = startVal + i * step
            )
        }
    }

    private fun makeLongShort(size: Int, startRatio: Double, step: Double): List<LongShortRatioPoint> {
        return (0 until size).map { i ->
            LongShortRatioPoint(
                timestamp = i * 60000L,
                longShortRatio = startRatio + i * step,
                longAccount = 0.5,
                shortAccount = 0.5
            )
        }
    }

    private fun makeTaker(size: Int, startRatio: Double, step: Double): List<TakerLongShortRatioPoint> {
        return (0 until size).map { i ->
            TakerLongShortRatioPoint(
                timestamp = i * 60000L,
                buySellRatio = startRatio + i * step,
                buyVol = 100.0 * (startRatio + i * step),
                sellVol = 100.0
            )
        }
    }

    private fun makeFunding(size: Int, rate: Double): List<FundingRatePoint> {
        return (0 until size).map { i ->
            FundingRatePoint(
                timestamp = i * 60000L,
                fundingRate = rate
            )
        }
    }

    @Test
    fun `multimodal bearish continuation is correctly identified`() {
        val size = 50
        val candles = makeCandles(size, 1000.0, -10.0) // falling price
        val oi = makeOi(size, 1000.0, 100.0) // rising OI
        val acc = makeLongShort(size, 1.0, 0.1) // rising retail long ratio (crowding UP)
        val global = makeLongShort(size, 1.0, 0.1)
        val pos = makeLongShort(size, 2.0, -0.05) // falling whale ratio (whale is short)
        val taker = makeTaker(size, 0.9, -0.01) // selling taker flow
        val funding = makeFunding(size, 0.0001)

        val outList = engine.evaluateMultimodal(
            candles = candles,
            rawOi = oi,
            rawAcc = acc,
            rawPos = pos,
            rawGlobal = global,
            rawTaker = taker,
            rawFunding = funding
        )

        assertFalse(outList.isEmpty())
        val lastOut = outList.last()
        println("TEST OUT BEARISH: Signal=${lastOut.finalSignal}, Score=${lastOut.weightedScore}, Reason=${lastOut.reason}")

        // Assert we got a bearish classification (SELL or STRONG_SELL)
        assertTrue(lastOut.finalSignal == Signal.SELL || lastOut.finalSignal == Signal.STRONG_SELL)
    }

    @Test
    fun `multimodal bullish continuation is correctly identified and keywords are swapped`() {
        val size = 50
        val candles = makeCandles(size, 1000.0, 10.0) // rising price
        val oi = makeOi(size, 1000.0, 100.0) // rising OI
        val acc = makeLongShort(size, 1.0, 0.1) // rising retail (will swap in UP priceState)
        val global = makeLongShort(size, 1.0, 0.1)
        val pos = makeLongShort(size, 1.0, 0.1) // whale matches retail (no override)
        val taker = makeTaker(size, 1.1, 0.01) // buying taker flow
        val funding = makeFunding(size, 0.0001)

        val outList = engine.evaluateMultimodal(
            candles = candles,
            rawOi = oi,
            rawAcc = acc,
            rawPos = pos,
            rawGlobal = global,
            rawTaker = taker,
            rawFunding = funding
        )

        assertFalse(outList.isEmpty())
        val lastOut = outList.last()
        println("TEST OUT BULLISH: Signal=${lastOut.finalSignal}, Score=${lastOut.weightedScore}, Reason=${lastOut.reason}")

        // Assert we got a bullish classification
        assertTrue(lastOut.finalSignal == Signal.BUY || lastOut.finalSignal == Signal.STRONG_BUY)

        // Verify keyword swapping (e.g. short -> long, falling -> rising, bearish -> bullish)
        assertTrue(lastOut.reason.contains("Classic deleveraging washout, most consistent with short covering and bullish follow-through"))
        assertTrue(lastOut.reason.contains("Bullish now, but exhaustion risk rises late in the move"))
    }

    @Test
    fun `test consensus engine with small histories size 1 2 5`() {
        val sizes = listOf(1, 2, 5)
        for (size in sizes) {
            val candles = makeCandles(size, 1000.0, -10.0)
            val oi = makeOi(size, 1000.0, 100.0)
            val acc = makeLongShort(size, 1.0, 0.1)
            val global = makeLongShort(size, 1.0, 0.1)
            val pos = makeLongShort(size, 2.0, -0.05)
            val taker = makeTaker(size, 0.9, -0.01)
            val funding = makeFunding(size, 0.0001)

            val out = engine.evaluateMultimodal(
                candles = candles,
                rawOi = oi,
                rawAcc = acc,
                rawPos = pos,
                rawGlobal = global,
                rawTaker = taker,
                rawFunding = funding
            )
            assertEquals(size, out.size)
            for (res in out) {
                assertFalse(res.weightedScore.isNaN(), "Score should not be NaN for size $size")
                assertFalse(res.weightedScore.isInfinite(), "Score should not be Infinite for size $size")
                assertTrue(res.confidence in 0..100, "Confidence ${res.confidence} should be in [0, 100]")
            }
        }
    }
}
