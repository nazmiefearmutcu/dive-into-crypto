# M1 — Crypcodile-KMP Engine Foundation + Binance Connector — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the on-device Crypcodile-KMP engine foundation — canonical record schema, a `Connector` interface, a Binance connector, and a `MarketDataEngine` façade that becomes the single data source — with the existing 8 screens working unchanged.

**Architecture:** New `engine/` package in `commonMain` mirroring Crypcodile's Python layout. The engine wraps the proven Binance REST/WS clients as its first connector and exposes both (a) the *exact* method surface the screens already use and (b) new canonical `Record` flows that M2–M4 build on. `AppContainer.repository` is retyped to `MarketDataEngine` (same method signatures) so ViewModel call sites are untouched. Physical consolidation of the Binance client files into `engine/exchanges/binance/` is deferred to M5 cleanup to keep M1 zero-regression.

**Tech Stack:** Kotlin Multiplatform 2.0.21, kotlinx-serialization 1.7.3, kotlinx-coroutines 1.9.0, Ktor 3.0.3 (client + websockets + mock for tests), JUnit (androidUnitTest), Ktor MockEngine (commonTest).

**Build/test commands (JDK 17 required — default `java` is 8):**
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/Users/nazmi/Library/Android/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
cd /Users/nazmi/dive-into-crypto/android
./gradlew :app:testDebugUnitTest        # run unit tests
./gradlew :app:assembleDebug            # verify app compiles
```
All `git` commands run from `/Users/nazmi/dive-into-crypto` (repo root); branch `feat/android-crypcodile-deep-data`.

**Package root:** `app/src/commonMain/kotlin/com/diveintocrypto/android/`
**Test root:** `app/src/commonTest/kotlin/com/diveintocrypto/android/`

---

## File Structure (created/modified in M1)

| File | Responsibility |
|---|---|
| `engine/schema/Enums.kt` (create) | `Venue`, `Side`, `OptType`, `Channel` enums |
| `engine/schema/Records.kt` (create) | sealed `Record` + 10 canonical record data classes |
| `engine/exchanges/Connector.kt` (create) | connector interface: `stream()`, `backfill()` |
| `engine/exchanges/binance/BinanceCanonical.kt` (create) | pure mappers: Binance DTO/Candle → canonical records |
| `engine/exchanges/binance/BinanceConnector.kt` (create) | wraps existing Binance clients; canonical OHLCV stream |
| `engine/MarketDataEngine.kt` (create) | façade: existing surface + canonical record flows |
| `engine/schema/RecordsTest.kt` (create, test) | serialization round-trip + field invariants |
| `engine/exchanges/binance/BinanceCanonicalTest.kt` (create, test) | mapper correctness |
| `engine/MarketDataEngineTest.kt` (create, test) | façade via Ktor MockEngine |
| `AppContainer.kt` (modify) | construct `MarketDataEngine`; retype `repository` |
| `data/MarketDataRepository.kt` (delete) | replaced by `MarketDataEngine` |

ViewModels are **not modified** — `container.repository` keeps its name, only its type changes.

---

### Task 1: Canonical enums

**Files:**
- Create: `app/src/commonMain/kotlin/com/diveintocrypto/android/engine/schema/Enums.kt`
- Test: `app/src/commonTest/kotlin/com/diveintocrypto/android/engine/schema/EnumsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.diveintocrypto.android.engine.schema

import kotlin.test.Test
import kotlin.test.assertEquals

class EnumsTest {
    @Test fun venueWireKeysMatchCrypcodile() {
        assertEquals("deribit", Venue.DERIBIT.wire)
        assertEquals("binance-spot", Venue.BINANCE_SPOT.wire)
        assertEquals("binance-usdm", Venue.BINANCE_USDM.wire)
    }

    @Test fun sideFromAggressorFlag() {
        assertEquals(Side.BUY, Side.fromBuyerMaker(false))   // taker bought
        assertEquals(Side.SELL, Side.fromBuyerMaker(true))   // taker sold
    }

    @Test fun channelWireKeys() {
        assertEquals("options_chain", Channel.OPTIONS_CHAIN.wire)
        assertEquals("derivative_ticker", Channel.DERIVATIVE_TICKER.wire)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*EnumsTest*'`
Expected: FAIL — `Enums.kt` / symbols unresolved (compilation error).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.diveintocrypto.android.engine.schema

/** Trading venue. `wire` matches Crypcodile's exchange key (`schema/enums.py`). */
enum class Venue(val wire: String) {
    DERIBIT("deribit"),
    BINANCE_SPOT("binance-spot"),
    BINANCE_USDM("binance-usdm"),
    BYBIT("bybit"),
    OKX("okx"),
    COINBASE("coinbase");

    companion object {
        fun fromWire(s: String): Venue? = entries.firstOrNull { it.wire == s }
    }
}

/** Trade aggressor side. */
enum class Side {
    BUY, SELL, UNKNOWN;

    companion object {
        /** Binance aggTrade `m` flag: buyer is market maker ⇒ the taker SOLD. */
        fun fromBuyerMaker(buyerIsMaker: Boolean): Side = if (buyerIsMaker) SELL else BUY
    }
}

/** Option right. */
enum class OptType(val wire: String) {
    CALL("C"), PUT("P");

    companion object {
        fun fromWire(s: String): OptType? = entries.firstOrNull { it.wire == s }
    }
}

/** Canonical channel keys — match Crypcodile's tagged record channels. */
enum class Channel(val wire: String) {
    TRADE("trade"),
    BOOK_SNAPSHOT("book_snapshot"),
    BOOK_DELTA("book_delta"),
    BOOK_TICKER("book_ticker"),
    DERIVATIVE_TICKER("derivative_ticker"),
    OPTIONS_CHAIN("options_chain"),
    FUNDING("funding"),
    OPEN_INTEREST("open_interest"),
    LIQUIDATION("liquidation"),
    OHLCV("ohlcv");

    companion object {
        fun fromWire(s: String): Channel? = entries.firstOrNull { it.wire == s }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*EnumsTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/nazmi/dive-into-crypto
git add android/app/src/commonMain/kotlin/com/diveintocrypto/android/engine/schema/Enums.kt \
        android/app/src/commonTest/kotlin/com/diveintocrypto/android/engine/schema/EnumsTest.kt
git commit -m "feat(engine): canonical venue/side/opttype/channel enums"
```

---

### Task 2: Canonical record schema

Port of Crypcodile `schema/records.py`. Every record carries `venue`, `symbol`, `symbolRaw`, `exchangeTs` (ns, nullable), `localTs` (ns). `Record` is a sealed interface so the engine can return heterogeneous streams.

**Files:**
- Create: `app/src/commonMain/kotlin/com/diveintocrypto/android/engine/schema/Records.kt`
- Test: `app/src/commonTest/kotlin/com/diveintocrypto/android/engine/schema/RecordsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.diveintocrypto.android.engine.schema

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun ohlcvCarriesCommonFields() {
        val r = OHLCV(
            venue = Venue.BINANCE_USDM, symbol = "binance-usdm:BTCUSDT", symbolRaw = "BTCUSDT",
            exchangeTs = 1_700_000_000_000_000_000L, localTs = 1_700_000_000_000_000_001L,
            open = 1.0, high = 2.0, low = 0.5, close = 1.5, volume = 10.0,
            buyVolume = 6.0, sellVolume = 4.0, numTrades = 3, interval = "1m",
        )
        assertEquals(Channel.OHLCV, r.channel)
        assertEquals("binance-usdm:BTCUSDT", r.symbol)
    }

    @Test fun optionsChainHoldsGreeks() {
        val r = OptionsChain(
            venue = Venue.DERIBIT, symbol = "deribit:BTC-28MAR25-100000-C", symbolRaw = "BTC-28MAR25-100000-C",
            exchangeTs = null, localTs = 1L,
            underlying = "BTC", underlyingPrice = 95000.0, strike = 100000.0,
            expiry = 1_743_148_800_000_000_000L, optType = OptType.CALL,
            markPrice = 0.05, bidPx = 0.049, bidSz = 5.0, askPx = 0.051, askSz = 5.0, lastPrice = 0.05,
            markIv = 0.62, bidIv = 0.61, askIv = 0.63,
            delta = 0.42, gamma = 0.00001, vega = 120.0, theta = -30.0, rho = 10.0,
            openInterest = 1234.0,
        )
        assertEquals(Channel.OPTIONS_CHAIN, r.channel)
        assertEquals(0.42, r.delta)
    }

    @Test fun recordSerializationRoundTripsPolymorphically() {
        val orig: Record = Trade(
            venue = Venue.DERIBIT, symbol = "deribit:BTC-PERPETUAL", symbolRaw = "BTC-PERPETUAL",
            exchangeTs = 1L, localTs = 2L, id = "t1", price = 95000.0, amount = 1.5,
            side = Side.BUY, liquidation = false,
        )
        val encoded = json.encodeToString(Record.serializer(), orig)
        val decoded = json.decodeFromString(Record.serializer(), encoded)
        assertTrue(decoded is Trade)
        assertEquals(95000.0, (decoded as Trade).price)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*RecordsTest*'`
Expected: FAIL — `Records.kt` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.diveintocrypto.android.engine.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Canonical market-data record — Kotlin port of Crypcodile `schema/records.py`.
 * Polymorphic (tagged) so the engine can stream heterogeneous records. Each
 * concrete type exposes its [channel]. Timestamps are nanoseconds UTC.
 */
@Serializable
sealed interface Record {
    val venue: Venue
    val symbol: String        // canonical "{venue}:{raw}"
    val symbolRaw: String
    val exchangeTs: Long?      // ns, nullable (some venues omit)
    val localTs: Long          // ns, receive time
    val channel: Channel
}

/** A single price level `(price, amount)`. amount==0.0 in a delta ⇒ remove level. */
@Serializable
data class Level(val price: Double, val amount: Double)

@Serializable
@SerialName("trade")
data class Trade(
    override val venue: Venue,
    override val symbol: String,
    override val symbolRaw: String,
    override val exchangeTs: Long?,
    override val localTs: Long,
    val id: String? = null,
    val price: Double,
    val amount: Double,
    val side: Side,
    val liquidation: Boolean = false,
) : Record { override val channel get() = Channel.TRADE }

@Serializable
@SerialName("book_snapshot")
data class BookSnapshot(
    override val venue: Venue,
    override val symbol: String,
    override val symbolRaw: String,
    override val exchangeTs: Long?,
    override val localTs: Long,
    val bids: List<Level>,
    val asks: List<Level>,
    val depth: Int? = null,
    val seqId: Long? = null,
) : Record { override val channel get() = Channel.BOOK_SNAPSHOT }

@Serializable
@SerialName("book_delta")
data class BookDelta(
    override val venue: Venue,
    override val symbol: String,
    override val symbolRaw: String,
    override val exchangeTs: Long?,
    override val localTs: Long,
    val bids: List<Level>,
    val asks: List<Level>,
    val seqId: Long? = null,
    val prevSeqId: Long? = null,
) : Record { override val channel get() = Channel.BOOK_DELTA }

@Serializable
@SerialName("book_ticker")
data class BookTicker(
    override val venue: Venue,
    override val symbol: String,
    override val symbolRaw: String,
    override val exchangeTs: Long?,
    override val localTs: Long,
    val bidPx: Double,
    val bidSz: Double,
    val askPx: Double,
    val askSz: Double,
    val updateId: Long? = null,
) : Record { override val channel get() = Channel.BOOK_TICKER }

@Serializable
@SerialName("derivative_ticker")
data class DerivativeTicker(
    override val venue: Venue,
    override val symbol: String,
    override val symbolRaw: String,
    override val exchangeTs: Long?,
    override val localTs: Long,
    val lastPrice: Double? = null,
    val markPrice: Double? = null,
    val indexPrice: Double? = null,
    val fundingRate: Double? = null,
    val predictedFundingRate: Double? = null,
    val fundingTs: Long? = null,
    val openInterest: Double? = null,
) : Record { override val channel get() = Channel.DERIVATIVE_TICKER }

@Serializable
@SerialName("options_chain")
data class OptionsChain(
    override val venue: Venue,
    override val symbol: String,
    override val symbolRaw: String,
    override val exchangeTs: Long?,
    override val localTs: Long,
    val underlying: String,
    val underlyingPrice: Double? = null,
    val strike: Double,
    val expiry: Long,                 // ns UTC
    val optType: OptType,
    val markPrice: Double? = null,
    val bidPx: Double? = null,
    val bidSz: Double? = null,
    val askPx: Double? = null,
    val askSz: Double? = null,
    val lastPrice: Double? = null,
    val markIv: Double? = null,
    val bidIv: Double? = null,
    val askIv: Double? = null,
    val delta: Double? = null,
    val gamma: Double? = null,
    val vega: Double? = null,
    val theta: Double? = null,
    val rho: Double? = null,
    val openInterest: Double? = null,
) : Record { override val channel get() = Channel.OPTIONS_CHAIN }

@Serializable
@SerialName("funding")
data class Funding(
    override val venue: Venue,
    override val symbol: String,
    override val symbolRaw: String,
    override val exchangeTs: Long?,
    override val localTs: Long,
    val fundingRate: Double,
    val predictedFundingRate: Double? = null,
    val fundingTs: Long,
    val intervalHours: Int = 8,
) : Record { override val channel get() = Channel.FUNDING }

@Serializable
@SerialName("open_interest")
data class OpenInterest(
    override val venue: Venue,
    override val symbol: String,
    override val symbolRaw: String,
    override val exchangeTs: Long?,
    override val localTs: Long,
    val openInterest: Double,
    val openInterestValue: Double? = null,
) : Record { override val channel get() = Channel.OPEN_INTEREST }

@Serializable
@SerialName("liquidation")
data class Liquidation(
    override val venue: Venue,
    override val symbol: String,
    override val symbolRaw: String,
    override val exchangeTs: Long?,
    override val localTs: Long,
    val price: Double,
    val amount: Double,
    val side: Side,
    val id: String? = null,
) : Record { override val channel get() = Channel.LIQUIDATION }

@Serializable
@SerialName("ohlcv")
data class OHLCV(
    override val venue: Venue,
    override val symbol: String,
    override val symbolRaw: String,
    override val exchangeTs: Long?,
    override val localTs: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val buyVolume: Double = 0.0,
    val sellVolume: Double = 0.0,
    val numTrades: Int = 0,
    val interval: String,
) : Record { override val channel get() = Channel.OHLCV }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*RecordsTest*'`
Expected: PASS (polymorphic serialization uses the `@SerialName` tag via the sealed interface).

- [ ] **Step 5: Commit**

```bash
cd /Users/nazmi/dive-into-crypto
git add android/app/src/commonMain/kotlin/com/diveintocrypto/android/engine/schema/Records.kt \
        android/app/src/commonTest/kotlin/com/diveintocrypto/android/engine/schema/RecordsTest.kt
git commit -m "feat(engine): canonical record schema (Crypcodile port)"
```

---

### Task 3: Connector interface

A connector streams canonical records from one venue and can backfill history. No test (pure interface; verified by compile + Task 4 usage).

**Files:**
- Create: `app/src/commonMain/kotlin/com/diveintocrypto/android/engine/exchanges/Connector.kt`

- [ ] **Step 1: Write the interface**

```kotlin
package com.diveintocrypto.android.engine.exchanges

import com.diveintocrypto.android.engine.schema.Channel
import com.diveintocrypto.android.engine.schema.Record
import com.diveintocrypto.android.engine.schema.Venue
import kotlinx.coroutines.flow.Flow

/**
 * A venue connector — Kotlin analogue of Crypcodile's `exchanges/base.py`.
 * Implementations stream canonical [Record]s live and optionally backfill
 * history via REST. Each connector owns exactly one [Venue].
 */
interface Connector {
    val venue: Venue

    /**
     * Cold stream of canonical records for the requested [channels] and
     * [symbols] (raw, venue-native). Emits until the collector cancels.
     * Implementations MUST isolate per-frame parse errors (one bad frame
     * must not terminate the stream) and reconnect with backoff internally.
     */
    fun stream(channels: Set<Channel>, symbols: Set<String>): Flow<Record>

    /**
     * REST backfill of historical records for one channel/symbol over a window.
     * Returns oldest-first. Empty list if the venue has no history for [channel].
     */
    suspend fun backfill(
        channel: Channel,
        symbolRaw: String,
        startNs: Long,
        endNs: Long,
        limit: Int,
    ): List<Record>
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/nazmi/dive-into-crypto
git add android/app/src/commonMain/kotlin/com/diveintocrypto/android/engine/exchanges/Connector.kt
git commit -m "feat(engine): Connector interface"
```

---

### Task 4: Binance canonical mappers

Pure functions mapping the existing Binance DTOs/`Candle` to canonical records. Kept pure for easy unit testing and reuse by the connector. The existing `Candle`, `Ticker24h`, `OpenInterestPoint`, `LongShortRatioPoint`, `TakerLongShortRatioPoint`, `FundingRatePoint` types in `domain.model` / `data.binance` are reused as-is.

**Files:**
- Create: `app/src/commonMain/kotlin/com/diveintocrypto/android/engine/exchanges/binance/BinanceCanonical.kt`
- Test: `app/src/commonTest/kotlin/com/diveintocrypto/android/engine/exchanges/binance/BinanceCanonicalTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.diveintocrypto.android.engine.exchanges.binance

import com.diveintocrypto.android.data.binance.FundingRatePoint
import com.diveintocrypto.android.data.binance.OpenInterestPoint
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.engine.schema.Venue
import kotlin.test.Test
import kotlin.test.assertEquals

class BinanceCanonicalTest {
    @Test fun candleToOhlcvUsesUsdmVenueAndNsTimestamps() {
        val c = Candle(openTime = 1_700_000_000_000L, open = 1.0, high = 2.0, low = 0.5,
            close = 1.5, volume = 10.0, closeTime = 1_700_000_059_999L)
        val o = c.toOhlcv("BTCUSDT", interval = "1m", localTsMs = 1_700_000_060_000L)
        assertEquals(Venue.BINANCE_USDM, o.venue)
        assertEquals("binance-usdm:BTCUSDT", o.symbol)
        assertEquals("1m", o.interval)
        assertEquals(1_700_000_000_000L * 1_000_000, o.exchangeTs)   // ms→ns
        assertEquals(1_700_000_060_000L * 1_000_000, o.localTs)
        assertEquals(1.5, o.close)
    }

    @Test fun openInterestPointToCanonical() {
        val p = OpenInterestPoint(timestamp = 1_700_000_000_000L, sumOpenInterest = 1234.0, sumOpenInterestValue = 9.9e7)
        val oi = p.toOpenInterest("BTCUSDT", localTsMs = 1_700_000_001_000L)
        assertEquals(1234.0, oi.openInterest)
        assertEquals(9.9e7, oi.openInterestValue)
        assertEquals(1_700_000_000_000L * 1_000_000, oi.exchangeTs)
    }

    @Test fun fundingPointToCanonical() {
        val p = FundingRatePoint(timestamp = 1_700_000_000_000L, fundingRate = -0.00012)
        val f = p.toFunding("BTCUSDT", localTsMs = 1_700_000_001_000L)
        assertEquals(-0.00012, f.fundingRate)
        assertEquals(8, f.intervalHours)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*BinanceCanonicalTest*'`
Expected: FAIL — mappers unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.diveintocrypto.android.engine.exchanges.binance

import com.diveintocrypto.android.data.binance.FundingRatePoint
import com.diveintocrypto.android.data.binance.OpenInterestPoint
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.engine.schema.Funding
import com.diveintocrypto.android.engine.schema.OHLCV
import com.diveintocrypto.android.engine.schema.OpenInterest
import com.diveintocrypto.android.engine.schema.Venue

/** Binance ms epoch → ns. */
private const val MS_TO_NS = 1_000_000L

/** Canonical symbol for a USDT-M perp raw symbol. */
internal fun usdmSymbol(raw: String): String = "${Venue.BINANCE_USDM.wire}:$raw"

/** [Candle] → canonical [OHLCV] (USD-M venue; ms→ns timestamps). */
fun Candle.toOhlcv(symbolRaw: String, interval: String, localTsMs: Long): OHLCV = OHLCV(
    venue = Venue.BINANCE_USDM,
    symbol = usdmSymbol(symbolRaw),
    symbolRaw = symbolRaw,
    exchangeTs = openTime * MS_TO_NS,
    localTs = localTsMs * MS_TO_NS,
    open = open, high = high, low = low, close = close, volume = volume,
    interval = interval,
)

/** [OpenInterestPoint] → canonical [OpenInterest]. */
fun OpenInterestPoint.toOpenInterest(symbolRaw: String, localTsMs: Long): OpenInterest = OpenInterest(
    venue = Venue.BINANCE_USDM,
    symbol = usdmSymbol(symbolRaw),
    symbolRaw = symbolRaw,
    exchangeTs = timestamp * MS_TO_NS,
    localTs = localTsMs * MS_TO_NS,
    openInterest = sumOpenInterest,
    openInterestValue = sumOpenInterestValue,
)

/** [FundingRatePoint] → canonical [Funding] (Binance USD-M funds every 8h). */
fun FundingRatePoint.toFunding(symbolRaw: String, localTsMs: Long): Funding = Funding(
    venue = Venue.BINANCE_USDM,
    symbol = usdmSymbol(symbolRaw),
    symbolRaw = symbolRaw,
    exchangeTs = timestamp * MS_TO_NS,
    localTs = localTsMs * MS_TO_NS,
    fundingRate = fundingRate,
    fundingTs = timestamp * MS_TO_NS,
    intervalHours = 8,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*BinanceCanonicalTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/nazmi/dive-into-crypto
git add android/app/src/commonMain/kotlin/com/diveintocrypto/android/engine/exchanges/binance/BinanceCanonical.kt \
        android/app/src/commonTest/kotlin/com/diveintocrypto/android/engine/exchanges/binance/BinanceCanonicalTest.kt
git commit -m "feat(engine): Binance canonical record mappers"
```

---

### Task 5: Binance connector

Wraps the existing `BinanceSpotClient`, `BinanceFuturesClient`, `BinanceWsClient` (kept in place) and exposes a canonical live OHLCV stream plus passthrough access for the façade. Implements `Connector` for `OHLCV` live + backfill; other channels return empty for now (filled in M3 for Deribit / later Binance depth+trades).

**Files:**
- Create: `app/src/commonMain/kotlin/com/diveintocrypto/android/engine/exchanges/binance/BinanceConnector.kt`
- Test: `app/src/commonTest/kotlin/com/diveintocrypto/android/engine/exchanges/binance/BinanceConnectorTest.kt`

- [ ] **Step 1: Write the failing test** (uses the real WS-less paths via injected clients backed by Ktor MockEngine)

```kotlin
package com.diveintocrypto.android.engine.exchanges.binance

import com.diveintocrypto.android.data.binance.BinanceFuturesClient
import com.diveintocrypto.android.engine.schema.Channel
import com.diveintocrypto.android.engine.schema.OHLCV
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BinanceConnectorTest {
    private fun mockKlines(): HttpClient {
        val body = """[[1700000000000,"1.0","2.0","0.5","1.5","10.0",1700000059999,"0",0,"0","0","0"]]"""
        val engine = MockEngine { respond(body, HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json")) }
        return HttpClient(engine)
    }

    @Test fun backfillOhlcvReturnsCanonicalRecords() = runTest {
        val futures = BinanceFuturesClient(client = mockKlines())
        val connector = BinanceConnector(futures = futures)
        val recs = connector.backfill(Channel.OHLCV, "BTCUSDT", 0, Long.MAX_VALUE, limit = 1)
        assertEquals(1, recs.size)
        val o = recs.first()
        assertTrue(o is OHLCV)
        assertEquals(1.5, (o as OHLCV).close)
        assertEquals("binance-usdm:BTCUSDT", o.symbol)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*BinanceConnectorTest*'`
Expected: FAIL — `BinanceConnector` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.diveintocrypto.android.engine.exchanges.binance

import com.diveintocrypto.android.data.binance.BinanceFuturesClient
import com.diveintocrypto.android.data.binance.BinanceSpotClient
import com.diveintocrypto.android.data.binance.BinanceWsClient
import com.diveintocrypto.android.engine.exchanges.Connector
import com.diveintocrypto.android.engine.schema.Channel
import com.diveintocrypto.android.engine.schema.OHLCV
import com.diveintocrypto.android.engine.schema.Record
import com.diveintocrypto.android.engine.schema.Venue
import com.diveintocrypto.android.platform.nowMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Binance venue connector. In M1 it wraps the proven REST/WS clients and
 * exposes a canonical live OHLCV stream + OHLCV backfill. Depth/trade/liquidation
 * canonical streams arrive in a later milestone; for unsupported channels
 * [stream] emits nothing and [backfill] returns empty.
 *
 * Defaults to USD-M futures (the app's primary market).
 */
class BinanceConnector(
    private val spot: BinanceSpotClient = BinanceSpotClient(),
    private val futures: BinanceFuturesClient = BinanceFuturesClient(),
    private val ws: BinanceWsClient = BinanceWsClient(),
) : Connector {
    override val venue: Venue = Venue.BINANCE_USDM

    /** Live OHLCV stream for one symbol/interval from the futures WS, mapped to canonical [OHLCV]. */
    fun ohlcvStream(symbolRaw: String, interval: String, wsBaseUrl: String? = null): Flow<OHLCV> =
        ws.klineStream(symbol = symbolRaw, interval = interval, customBaseUrl = wsBaseUrl)
            .map { update -> update.candle.toOhlcv(symbolRaw, interval, nowMillis()) }

    override fun stream(channels: Set<Channel>, symbols: Set<String>): Flow<Record> =
        kotlinx.coroutines.flow.flow {
            // M1: only OHLCV streaming is wired here, exposed via [ohlcvStream] which the
            // engine uses directly. Generic multi-channel streaming is added with Deribit (M3).
        }

    override suspend fun backfill(
        channel: Channel,
        symbolRaw: String,
        startNs: Long,
        endNs: Long,
        limit: Int,
    ): List<Record> = when (channel) {
        Channel.OHLCV -> {
            val now = nowMillis()
            futures.klines(symbol = symbolRaw, interval = "1m", limit = limit)
                .map { it.toOhlcv(symbolRaw, interval = "1m", localTsMs = now) }
        }
        else -> emptyList()
    }

    // Passthrough accessors used by the façade for the existing screen surface.
    internal fun spotClient() = spot
    internal fun futuresClient() = futures
    internal fun wsClient() = ws
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*BinanceConnectorTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/nazmi/dive-into-crypto
git add android/app/src/commonMain/kotlin/com/diveintocrypto/android/engine/exchanges/binance/BinanceConnector.kt \
        android/app/src/commonTest/kotlin/com/diveintocrypto/android/engine/exchanges/binance/BinanceConnectorTest.kt
git commit -m "feat(engine): Binance connector with canonical OHLCV"
```

---

### Task 6: MarketDataEngine façade + rewire + delete repository

The engine exposes the **exact** method surface `MarketDataRepository` had (so ViewModels are untouched) PLUS canonical streams. Then `AppContainer` constructs it (keeping property name `repository`), and `MarketDataRepository` is deleted.

**Files:**
- Create: `app/src/commonMain/kotlin/com/diveintocrypto/android/engine/MarketDataEngine.kt`
- Test: `app/src/commonTest/kotlin/com/diveintocrypto/android/engine/MarketDataEngineTest.kt`
- Modify: `app/src/commonMain/kotlin/com/diveintocrypto/android/AppContainer.kt`
- Delete: `app/src/commonMain/kotlin/com/diveintocrypto/android/data/MarketDataRepository.kt`

- [ ] **Step 1: Write the failing test** (façade delegates + maps canonical OHLCV)

```kotlin
package com.diveintocrypto.android.engine

import com.diveintocrypto.android.data.SettingsStore
import com.diveintocrypto.android.data.binance.BinanceFuturesClient
import com.diveintocrypto.android.data.binance.BinanceSpotClient
import com.diveintocrypto.android.data.binance.BinanceWsClient
import com.diveintocrypto.android.engine.exchanges.binance.BinanceConnector
import com.diveintocrypto.android.testutil.InMemoryKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MarketDataEngineTest {
    private fun klineClient(): HttpClient {
        val body = """[[1700000000000,"1.0","2.0","0.5","1.5","10.0",1700000059999,"0",0,"0","0","0"]]"""
        return HttpClient(MockEngine { respond(body, HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json")) })
    }

    @Test fun futuresHistoryDelegatesToConnector() = runTest {
        val settings = SettingsStore(InMemoryKeyValueStore())
        val connector = BinanceConnector(
            spot = BinanceSpotClient(client = klineClient()),
            futures = BinanceFuturesClient(client = klineClient()),
            ws = BinanceWsClient(),
        )
        val engine = MarketDataEngine(connector, settings)
        val candles = engine.futuresHistory("BTCUSDT", "1m", limit = 1)
        assertEquals(1, candles.size)
        assertEquals(1.5, candles.first().close)
    }
}
```

> If `InMemoryKeyValueStore` does not already exist under `testutil`, create it:
> `app/src/commonTest/kotlin/com/diveintocrypto/android/testutil/InMemoryKeyValueStore.kt`
> ```kotlin
> package com.diveintocrypto.android.testutil
> import com.diveintocrypto.android.data.KeyValueStore
> class InMemoryKeyValueStore : KeyValueStore {
>     private val m = mutableMapOf<String, String>()
>     override fun getString(key: String): String? = m[key]
>     override fun putString(key: String, value: String) { m[key] = value }
> }
> ```
> (Adjust to match the actual `KeyValueStore` interface in `data/KeyValueStore.kt` — read it first and implement every member.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*MarketDataEngineTest*'`
Expected: FAIL — `MarketDataEngine` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.diveintocrypto.android.engine

import com.diveintocrypto.android.data.SettingsStore
import com.diveintocrypto.android.data.binance.BinanceWsClient
import com.diveintocrypto.android.data.binance.FundingRatePoint
import com.diveintocrypto.android.data.binance.LongShortRatioPoint
import com.diveintocrypto.android.data.binance.OpenInterestPoint
import com.diveintocrypto.android.data.binance.TakerLongShortRatioPoint
import com.diveintocrypto.android.data.binance.Ticker24h
import com.diveintocrypto.android.domain.model.Candle
import com.diveintocrypto.android.engine.exchanges.binance.BinanceConnector
import com.diveintocrypto.android.engine.schema.OHLCV
import com.diveintocrypto.android.engine.exchanges.binance.toOhlcv
import com.diveintocrypto.android.platform.nowMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single on-device market-data source — the Crypcodile-KMP engine façade.
 *
 * Exposes the exact method surface the screens already use (so existing
 * ViewModels are untouched) PLUS canonical [OHLCV] flows that deep-data
 * features build on. Backed by [BinanceConnector] in M1; the Deribit connector
 * and cross-channel canonical streams arrive in later milestones.
 */
class MarketDataEngine(
    private val binance: BinanceConnector,
    private val settingsStore: SettingsStore,
) {
    // ── Existing surface (delegates to the Binance connector's clients) ──
    suspend fun history(symbol: String, interval: String, limit: Int = 300): List<Candle> =
        binance.spotClient().klines(symbol = symbol, interval = interval, limit = limit)

    fun liveKlines(symbol: String, interval: String): Flow<BinanceWsClient.KlineUpdate> {
        val settings = settingsStore.getSettings()
        val wsUrl = if (settings.wsDataSource == "SPOT") "wss://stream.binance.com:9443"
                    else "wss://fstream.binance.com"
        return binance.wsClient().klineStream(symbol = symbol, interval = interval, customBaseUrl = wsUrl)
    }

    suspend fun futuresHistory(symbol: String, interval: String, limit: Int = 300): List<Candle> =
        binance.futuresClient().klines(symbol = symbol, interval = interval, limit = limit)

    suspend fun futuresUniverse(): List<String> = binance.futuresClient().universe24hSortedByVolume()
    suspend fun ticker24hAll(): List<Ticker24h> = binance.futuresClient().ticker24hAll()
    suspend fun openInterestHist(symbol: String, period: String = "1h", limit: Int = 30): List<OpenInterestPoint> =
        binance.futuresClient().openInterestHist(symbol, period, limit)
    suspend fun topLongShortAccountRatio(symbol: String, period: String = "1h", limit: Int = 30): List<LongShortRatioPoint> =
        binance.futuresClient().topLongShortAccountRatio(symbol, period, limit)
    suspend fun topLongShortPositionRatio(symbol: String, period: String = "1h", limit: Int = 30): List<LongShortRatioPoint> =
        binance.futuresClient().topLongShortPositionRatio(symbol, period, limit)
    suspend fun globalLongShortAccountRatio(symbol: String, period: String = "1h", limit: Int = 30): List<LongShortRatioPoint> =
        binance.futuresClient().globalLongShortAccountRatio(symbol, period, limit)
    suspend fun takerLongShortRatio(symbol: String, period: String = "1h", limit: Int = 30): List<TakerLongShortRatioPoint> =
        binance.futuresClient().takerLongShortRatio(symbol, period, limit)
    suspend fun fundingRate(symbol: String, limit: Int = 30): List<FundingRatePoint> =
        binance.futuresClient().fundingRate(symbol, limit)

    // ── New canonical surface ──
    /** Live canonical OHLCV for the active symbol/interval (futures WS). */
    fun liveOhlcv(symbol: String, interval: String): Flow<OHLCV> {
        val settings = settingsStore.getSettings()
        val wsUrl = if (settings.wsDataSource == "SPOT") "wss://stream.binance.com:9443"
                    else "wss://fstream.binance.com"
        return binance.wsClient().klineStream(symbol = symbol, interval = interval, customBaseUrl = wsUrl)
            .map { it.candle.toOhlcv(symbol, interval, nowMillis()) }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*MarketDataEngineTest*'`
Expected: PASS.

- [ ] **Step 5: Rewire AppContainer**

In `AppContainer.kt`: replace the `repository` declaration. Remove the import `com.diveintocrypto.android.data.MarketDataRepository`; add imports for the engine + connector. Change:

```kotlin
import com.diveintocrypto.android.engine.MarketDataEngine
import com.diveintocrypto.android.engine.exchanges.binance.BinanceConnector
```

Replace the `repository` block (lines ~69-76) with:

```kotlin
    val repository: MarketDataEngine by lazy {
        MarketDataEngine(
            binance = BinanceConnector(
                spot = BinanceSpotClient(),
                futures = BinanceFuturesClient(),
                ws = BinanceWsClient(),
            ),
            settingsStore = settingsStore,
        )
    }
```

(Keep the existing `BinanceSpotClient`/`BinanceFuturesClient`/`BinanceWsClient` imports.)

- [ ] **Step 6: Delete the old repository**

```bash
cd /Users/nazmi/dive-into-crypto
git rm android/app/src/commonMain/kotlin/com/diveintocrypto/android/data/MarketDataRepository.kt
```

- [ ] **Step 7: Full suite + compile gate**

Run:
```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```
Expected: BUILD SUCCESSFUL; all existing tests still pass (ViewModels compile unchanged because `repository` keeps its name and method signatures). If any ViewModel fails to resolve a method, the engine surface is missing a method — add the missing delegate to `MarketDataEngine` verbatim from the table in `MarketDataRepository`'s original (do not change call sites).

- [ ] **Step 8: Commit**

```bash
cd /Users/nazmi/dive-into-crypto
git add -A
git commit -m "feat(engine): MarketDataEngine facade replaces MarketDataRepository"
```

---

## Self-Review

**Spec coverage (M1 rows of spec §7):** engine skeleton (Tasks 1–3 ✓), canonical schema (Task 2 ✓), Binance connector (Tasks 4–5 ✓), `MarketDataEngine` façade (Task 6 ✓), existing screens unchanged (Task 6 Step 7 gate ✓). Deviation: physical removal of `data/binance/*` deferred to M5 (clients are reused as connector internals) — noted in Architecture; `MarketDataRepository` IS removed (Task 6).

**Placeholder scan:** none — every step has full code or exact commands. The `InMemoryKeyValueStore` note instructs reading the real `KeyValueStore` interface and implementing all members (its exact shape isn't assumed).

**Type consistency:** `Venue.wire`, `Channel.wire`, `OHLCV` fields, `Record` sealed interface, `toOhlcv/toOpenInterest/toFunding` mapper names, `BinanceConnector(spot,futures,ws)` ctor, `MarketDataEngine(binance, settingsStore)` ctor, and the 11 façade methods are used consistently across tasks. `repository` property name preserved → no ViewModel edits.

**Open assumptions to verify during execution:** (1) the real `KeyValueStore` interface signature (read `data/KeyValueStore.kt`); (2) `BinanceSpotClient`/`FuturesClient` expose a `client = HttpClient` constructor param (confirmed: they do); (3) `SettingsStore.getSettings().wsDataSource` exists (confirmed in original repository).
