# M3 — Deribit Connector — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Add a native Kotlin Deribit connector to the engine — live options chains with greeks/IV, perp/future derivative tickers + funding, trades, liquidations, and L2 book — sourced from Deribit's public WS, normalized to canonical records, wired into `MarketDataEngine`.

**Architecture:** Pure `normalizeMessage(JsonObject, localTs): List<Record>` (1:1 port of `crypcodile/exchanges/deribit/normalize.py`) is the testable core. A thin `DeribitConnector` implements `Connector`: builds JSON-RPC `public/subscribe` channels, opens the WS with reconnect+backoff, and runs each text frame through normalize with per-frame parse isolation. `MarketDataEngine` gains a Deribit connector + typed convenience flows.

**Tech Stack:** Kotlin Multiplatform, Ktor 3.0.3 WebSockets, kotlinx-serialization-json, kotlinx-datetime 0.6.1 (option-expiry date math), kotlinx-coroutines Flow.

**Build/test env (JDK 17):**
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/Users/nazmi/Library/Android/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
cd /Users/nazmi/dive-into-crypto/android
./gradlew :app:testDebugUnitTest --tests '*<TestName>*'
```
Repo root `/Users/nazmi/dive-into-crypto`, branch `feat/android-crypcodile-deep-data`. Commits carry NO Claude/AI attribution.

**Endpoints:** WS `wss://www.deribit.com/ws/api/v2`, REST `https://www.deribit.com/api/v2`. All public, no auth.

**Dependencies on M1:** `engine/schema/*` (Records, `OptType`, `Side`, `Venue.DERIBIT`), `engine/exchanges/Connector.kt`, `engine/MarketDataEngine.kt`, `data/binance/NetworkLog.kt` (reused for multi-venue WS logging), `platform/Time.kt` (`nowMillis`).

**Reference (Deribit normalize.py behavior to preserve EXACTLY):**
- `ticker.{sym}.100ms` data: option iff `greeks != null` OR `mark_iv != null`.
  - Option → `OptionsChain`: underlying/strike/expiry/optType from symbol parse; `mark_iv,bid_iv,ask_iv` = Deribit% ÷ 100; greeks delta/gamma/vega/theta/rho RAW from `greeks`; markPrice=`mark_price`, bidPx=`best_bid_price`, bidSz=`best_bid_amount`, askPx=`best_ask_price`, askSz=`best_ask_amount`, lastPrice=`last_price`, openInterest=`open_interest`, underlyingPrice=`underlying_price`.
  - Perp/future → `DerivativeTicker`(lastPrice=`last_price`, markPrice=`mark_price`, indexPrice=`index_price`, fundingRate=`current_funding`, predictedFundingRate=`funding_8h`, openInterest=`open_interest`) + (if `current_funding != null`) `Funding`(fundingRate=`current_funding`, predictedFundingRate=`funding_8h`, fundingTs=exchangeTs, intervalHours=8).
- `trades.{sym}.100ms` data (list): each → `Trade`(id=`trade_id`, price, amount, side from `direction` buy/sell, liquidation=`liquidation` truthy); if `liquidation` truthy also a `Liquidation`.
- `book.{sym}.100ms` data (dict): `type=="snapshot"` → `BookSnapshot`(bids/asks, depth=len(bids)+len(asks), seqId=`change_id`) else `BookDelta`(seqId=`change_id`, prevSeqId=`prev_change_id`). Levels: each `[action, price, amount]`; `action=="delete"` ⇒ amount 0.
- Timestamps: `ms_to_ns(x) = x * 1_000_000`. `exchangeTs = ms_to_ns(data.timestamp)`.
- Option symbol `BASE-DdMMMyy-STRIKE-(C|P)` e.g. `BTC-27JUN25-100000-C`: regex `^(\d{1,2})([A-Z]{3})(\d{2})$` on the date token → day, month (JAN..DEC→1..12), year=2000+yy; expiry_ns = UTC-midnight epoch-seconds × 1e9.

---

## File Structure (M3)

| File | Responsibility |
|---|---|
| `engine/exchanges/deribit/DeribitSymbols.kt` (create) | option symbol parse → (underlying, strike, expiryNs, optType) |
| `engine/exchanges/deribit/DeribitNormalize.kt` (create) | `normalizeMessage(JsonObject, localTs): List<Record>` |
| `engine/exchanges/deribit/DeribitInstruments.kt` (create) | parse `public/get_instruments` + REST fetch |
| `engine/exchanges/deribit/DeribitConnector.kt` (create) | `Connector`: channels, subscribe frame, WS stream + reconnect |
| `engine/MarketDataEngine.kt` (modify) | add Deribit connector + typed convenience flows |
| + matching `*Test.kt` | offline JSON-fixture + behavior tests |

---

### Task 1: Deribit option symbol parser

**Files:**
- Create: `app/src/commonMain/kotlin/com/diveintocrypto/android/engine/exchanges/deribit/DeribitSymbols.kt`
- Test: `app/src/commonTest/kotlin/com/diveintocrypto/android/engine/exchanges/deribit/DeribitSymbolsTest.kt`

- [ ] **Step 1: failing test**
```kotlin
package com.diveintocrypto.android.engine.exchanges.deribit

import com.diveintocrypto.android.engine.schema.OptType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeribitSymbolsTest {
    @Test fun parsesCallSymbol() {
        val p = DeribitSymbols.parseOptionSymbol("BTC-27JUN25-100000-C")!!
        assertEquals("BTC", p.underlying)
        assertEquals(100000.0, p.strike)
        assertEquals(OptType.CALL, p.optType)
        // 2025-06-27 00:00:00 UTC in ns
        assertEquals(1750982400L * 1_000_000_000L, p.expiryNs)
    }

    @Test fun parsesPutSingleDigitDay() {
        val p = DeribitSymbols.parseOptionSymbol("ETH-8JUN26-3000-P")!!
        assertEquals("ETH", p.underlying)
        assertEquals(3000.0, p.strike)
        assertEquals(OptType.PUT, p.optType)
        assertEquals(1781222400L * 1_000_000_000L, p.expiryNs) // 2026-06-08 00:00 UTC
    }

    @Test fun returnsNullForNonOption() {
        assertNull(DeribitSymbols.parseOptionSymbol("BTC-PERPETUAL"))
        assertNull(DeribitSymbols.parseOptionSymbol("BTC-27JUN25")) // < 4 parts
    }
}
```
> The two epoch constants are UTC-midnight of 2025-06-27 and 2026-06-08. If your implementation is correct they will match; compute them with kotlinx-datetime, do not hardcode in the impl.

- [ ] **Step 2: run, confirm FAIL.**

- [ ] **Step 3: implement** `DeribitSymbols.kt`:
```kotlin
package com.diveintocrypto.android.engine.exchanges.deribit

import com.diveintocrypto.android.engine.schema.OptType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/** Parsed Deribit option symbol. */
data class ParsedOption(val underlying: String, val strike: Double, val expiryNs: Long, val optType: OptType)

/** Deribit option symbol parsing — port of `_parse_option_symbol` in normalize.py. */
object DeribitSymbols {
    private val MONTHS = mapOf(
        "JAN" to 1, "FEB" to 2, "MAR" to 3, "APR" to 4, "MAY" to 5, "JUN" to 6,
        "JUL" to 7, "AUG" to 8, "SEP" to 9, "OCT" to 10, "NOV" to 11, "DEC" to 12,
    )
    // D{1,2}MMMYY e.g. 8JUN26, 27JUN25
    private val DATE_RE = Regex("^(\\d{1,2})([A-Z]{3})(\\d{2})$")

    /** Parse BASE-DdMMMyy-STRIKE-(C|P). Returns null if not a 4-part option symbol. */
    fun parseOptionSymbol(sym: String): ParsedOption? {
        val parts = sym.split("-")
        if (parts.size < 4) return null
        val underlying = parts[0]
        val strike = parts[2].toDoubleOrNull() ?: return null
        val optType = when (parts[3]) {
            "C" -> OptType.CALL
            "P" -> OptType.PUT
            else -> return null
        }
        val m = DATE_RE.find(parts[1].uppercase()) ?: return null
        val day = m.groupValues[1].toInt()
        val month = MONTHS[m.groupValues[2]] ?: return null
        val year = 2000 + m.groupValues[3].toInt()
        val epochSec = LocalDate(year, month, day).atStartOfDayIn(TimeZone.UTC).epochSeconds
        return ParsedOption(underlying, strike, epochSec * 1_000_000_000L, optType)
    }
}
```

- [ ] **Step 4: run, confirm PASS (3).**
- [ ] **Step 5: commit** `feat(engine): Deribit option symbol parser`:
```bash
cd /Users/nazmi/dive-into-crypto
git add android/app/src/commonMain/kotlin/com/diveintocrypto/android/engine/exchanges/deribit/DeribitSymbols.kt \
        android/app/src/commonTest/kotlin/com/diveintocrypto/android/engine/exchanges/deribit/DeribitSymbolsTest.kt
git commit -m "feat(engine): Deribit option symbol parser"
```

---

### Task 2: Deribit message normalizer

**Files:**
- Create: `app/src/commonMain/kotlin/com/diveintocrypto/android/engine/exchanges/deribit/DeribitNormalize.kt`
- Test: `app/src/commonTest/kotlin/com/diveintocrypto/android/engine/exchanges/deribit/DeribitNormalizeTest.kt`

- [ ] **Step 1: failing test** (representative Deribit JSON payloads):
```kotlin
package com.diveintocrypto.android.engine.exchanges.deribit

import com.diveintocrypto.android.engine.schema.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeribitNormalizeTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(s: String) = json.parseToJsonElement(s).jsonObject

    @Test fun optionTickerToOptionsChainWithIvPercentAndRawGreeks() {
        val msg = obj("""
        {"params":{"channel":"ticker.BTC-27JUN25-100000-C.100ms","data":{
          "instrument_name":"BTC-27JUN25-100000-C","timestamp":1700000000000,
          "underlying_price":95000.0,"mark_price":0.05,"mark_iv":65.0,
          "best_bid_price":0.049,"best_bid_amount":5.0,"bid_iv":64.0,
          "best_ask_price":0.051,"best_ask_amount":5.0,"ask_iv":66.0,
          "last_price":0.05,"open_interest":1234.0,
          "greeks":{"delta":0.42,"gamma":0.00001,"vega":120.0,"theta":-30.0,"rho":10.0}
        }}}""")
        val recs = DeribitNormalize.normalizeMessage(msg, localTs = 9L)
        assertEquals(1, recs.size)
        val oc = recs[0] as OptionsChain
        assertEquals("deribit:BTC-27JUN25-100000-C", oc.symbol)
        assertEquals(0.65, oc.markIv!!, 1e-12)     // 65.0% → 0.65
        assertEquals(0.64, oc.bidIv!!, 1e-12)
        assertEquals(0.42, oc.delta!!, 1e-12)      // greeks RAW
        assertEquals(120.0, oc.vega!!, 1e-12)
        assertEquals(100000.0, oc.strike)
        assertEquals(OptType.CALL, oc.optType)
        assertEquals(1700000000000L * 1_000_000L, oc.exchangeTs)
        assertEquals(9L, oc.localTs)
    }

    @Test fun perpTickerToDerivativeTickerAndFunding() {
        val msg = obj("""
        {"params":{"channel":"ticker.BTC-PERPETUAL.100ms","data":{
          "instrument_name":"BTC-PERPETUAL","timestamp":1700000000000,
          "last_price":95000.0,"mark_price":95010.0,"index_price":95005.0,
          "current_funding":0.0001,"funding_8h":0.0002,"open_interest":50000.0
        }}}""")
        val recs = DeribitNormalize.normalizeMessage(msg, localTs = 9L)
        assertEquals(2, recs.size)
        val dt = recs[0] as DerivativeTicker
        assertEquals(95010.0, dt.markPrice!!); assertEquals(95005.0, dt.indexPrice!!)
        assertEquals(0.0001, dt.fundingRate!!, 1e-12)
        val f = recs[1] as Funding
        assertEquals(0.0001, f.fundingRate, 1e-12); assertEquals(8, f.intervalHours)
    }

    @Test fun tradesWithLiquidationEmitsTradeAndLiquidation() {
        val msg = obj("""
        {"params":{"channel":"trades.BTC-PERPETUAL.100ms","data":[
          {"instrument_name":"BTC-PERPETUAL","timestamp":1700000000000,"trade_id":"t1",
           "price":95000.0,"amount":10.0,"direction":"sell","liquidation":"M"}
        ]}}""")
        val recs = DeribitNormalize.normalizeMessage(msg, localTs = 9L)
        assertEquals(2, recs.size)
        val t = recs[0] as Trade
        assertEquals(Side.SELL, t.side); assertTrue(t.liquidation)
        assertTrue(recs[1] is Liquidation)
    }

    @Test fun bookSnapshotAndDeltaLevels() {
        val snap = obj("""
        {"params":{"channel":"book.BTC-PERPETUAL.100ms","data":{
          "instrument_name":"BTC-PERPETUAL","timestamp":1700000000000,"type":"snapshot",
          "change_id":100,"bids":[["new",95000.0,5.0]],"asks":[["new",95010.0,3.0]]
        }}}""")
        val s = DeribitNormalize.normalizeMessage(snap, localTs = 9L)[0] as BookSnapshot
        assertEquals(1, s.bids.size); assertEquals(95000.0, s.bids[0].price); assertEquals(5.0, s.bids[0].amount)
        assertEquals(2, s.depth)

        val delta = obj("""
        {"params":{"channel":"book.BTC-PERPETUAL.100ms","data":{
          "instrument_name":"BTC-PERPETUAL","timestamp":1700000000001,"type":"change",
          "change_id":101,"prev_change_id":100,"bids":[["delete",95000.0,0.0]],"asks":[]
        }}}""")
        val d = DeribitNormalize.normalizeMessage(delta, localTs = 9L)[0] as BookDelta
        assertEquals(0.0, d.bids[0].amount)    // delete ⇒ amount 0
        assertEquals(101L, d.seqId); assertEquals(100L, d.prevSeqId)
    }

    @Test fun unknownChannelYieldsNothing() {
        assertTrue(DeribitNormalize.normalizeMessage(obj("""{"params":{"channel":"x.y","data":{}}}"""), 1L).isEmpty())
        assertTrue(DeribitNormalize.normalizeMessage(obj("""{"foo":1}"""), 1L).isEmpty())
    }
}
```

- [ ] **Step 2: run, confirm FAIL.**

- [ ] **Step 3: implement** `DeribitNormalize.kt`. Port of `normalize.py`. Use kotlinx-serialization JSON helpers (`jsonObject`, `jsonArray`, `?.jsonPrimitive?.doubleOrNull/longOrNull/contentOrNull`). Guidance:
```kotlin
package com.diveintocrypto.android.engine.exchanges.deribit

import com.diveintocrypto.android.engine.schema.*
import kotlinx.serialization.json.*

/** Deribit WS message → canonical records. Port of `crypcodile/exchanges/deribit/normalize.py`. */
object DeribitNormalize {
    private const val MS_TO_NS = 1_000_000L
    private const val EX = "deribit"

    fun normalizeMessage(msg: JsonObject, localTs: Long): List<Record> {
        val params = msg["params"]?.jsonObjectOrNull() ?: return emptyList()
        val channel = params["channel"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val data = params["data"]
        return when {
            channel.startsWith("trades.") -> normTrades(data, localTs)
            channel.startsWith("book.") -> normBook(data, localTs)
            channel.startsWith("ticker.") -> normTicker(data, localTs)
            else -> emptyList()
        }
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonObject.dbl(k: String): Double? = this[k]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.lng(k: String): Long? = this[k]?.jsonPrimitive?.longOrNull
    private fun JsonObject.str(k: String): String? = this[k]?.jsonPrimitive?.contentOrNull
    private fun side(dir: String?): Side = when (dir) { "buy" -> Side.BUY; "sell" -> Side.SELL; else -> Side.UNKNOWN }
    private fun iv(v: Double?): Double? = v?.let { it / 100.0 }
    private fun canon(sym: String) = "$EX:$sym"

    private fun normTrades(data: JsonElement?, localTs: Long): List<Record> {
        val arr = data as? JsonArray ?: return emptyList()
        val out = ArrayList<Record>()
        for (el in arr) {
            val t = el.jsonObject
            val sym = t.str("instrument_name") ?: continue
            val ts = (t.lng("timestamp") ?: continue) * MS_TO_NS
            val s = side(t.str("direction"))
            val liq = t["liquidation"].let { it != null && it != JsonNull }
            out.add(Trade(Venue.DERIBIT, canon(sym), sym, ts, localTs,
                id = t.str("trade_id"), price = t.dbl("price") ?: 0.0,
                amount = t.dbl("amount") ?: 0.0, side = s, liquidation = liq))
            if (liq) out.add(Liquidation(Venue.DERIBIT, canon(sym), sym, ts, localTs,
                price = t.dbl("price") ?: 0.0, amount = t.dbl("amount") ?: 0.0, side = s, id = t.str("trade_id")))
        }
        return out
    }

    private fun levels(arr: JsonElement?): List<Level> {
        val a = arr as? JsonArray ?: return emptyList()
        return a.map { row ->
            val r = row.jsonArray
            val action = r[0].jsonPrimitive.contentOrNull
            val price = r[1].jsonPrimitive.doubleOrNull ?: 0.0
            val amount = if (action == "delete") 0.0 else (r[2].jsonPrimitive.doubleOrNull ?: 0.0)
            Level(price, amount)
        }
    }

    private fun normBook(data: JsonElement?, localTs: Long): List<Record> {
        val d = data as? JsonObject ?: return emptyList()
        val sym = d.str("instrument_name") ?: return emptyList()
        val ts = (d.lng("timestamp") ?: return emptyList()) * MS_TO_NS
        val bids = levels(d["bids"]); val asks = levels(d["asks"])
        return if (d.str("type") == "snapshot")
            listOf(BookSnapshot(Venue.DERIBIT, canon(sym), sym, ts, localTs, bids, asks,
                depth = (d["bids"] as? JsonArray)?.size?.plus((d["asks"] as? JsonArray)?.size ?: 0)
                    ?: (bids.size + asks.size), seqId = d.lng("change_id")))
        else
            listOf(BookDelta(Venue.DERIBIT, canon(sym), sym, ts, localTs, bids, asks,
                seqId = d.lng("change_id"), prevSeqId = d.lng("prev_change_id")))
    }

    private fun normTicker(data: JsonElement?, localTs: Long): List<Record> {
        val td = data as? JsonObject ?: return emptyList()
        val sym = td.str("instrument_name") ?: return emptyList()
        val ts = (td.lng("timestamp") ?: return emptyList()) * MS_TO_NS
        val greeks = td["greeks"] as? JsonObject
        val isOption = greeks != null || td["mark_iv"] != null
        if (isOption) {
            val parsed = DeribitSymbols.parseOptionSymbol(sym) ?: return emptyList()
            return listOf(OptionsChain(Venue.DERIBIT, canon(sym), sym, ts, localTs,
                underlying = parsed.underlying, underlyingPrice = td.dbl("underlying_price"),
                strike = parsed.strike, expiry = parsed.expiryNs, optType = parsed.optType,
                markPrice = td.dbl("mark_price"), markIv = iv(td.dbl("mark_iv")),
                bidPx = td.dbl("best_bid_price"), bidSz = td.dbl("best_bid_amount"), bidIv = iv(td.dbl("bid_iv")),
                askPx = td.dbl("best_ask_price"), askSz = td.dbl("best_ask_amount"), askIv = iv(td.dbl("ask_iv")),
                lastPrice = td.dbl("last_price"), openInterest = td.dbl("open_interest"),
                delta = greeks?.dbl("delta"), gamma = greeks?.dbl("gamma"), vega = greeks?.dbl("vega"),
                theta = greeks?.dbl("theta"), rho = greeks?.dbl("rho")))
        }
        val out = ArrayList<Record>()
        out.add(DerivativeTicker(Venue.DERIBIT, canon(sym), sym, ts, localTs,
            lastPrice = td.dbl("last_price"), markPrice = td.dbl("mark_price"), indexPrice = td.dbl("index_price"),
            fundingRate = td.dbl("current_funding"), predictedFundingRate = td.dbl("funding_8h"),
            openInterest = td.dbl("open_interest")))
        val cf = td.dbl("current_funding")
        if (cf != null) out.add(Funding(Venue.DERIBIT, canon(sym), sym, ts, localTs,
            fundingRate = cf, predictedFundingRate = td.dbl("funding_8h"), fundingTs = ts, intervalHours = 8))
        return out
    }
}
```

- [ ] **Step 4: run, confirm PASS (6).**
- [ ] **Step 5: commit** `feat(engine): Deribit message normalizer`.

---

### Task 3: Deribit connector (channels, subscribe frame, WS stream)

**Files:**
- Create: `app/src/commonMain/kotlin/com/diveintocrypto/android/engine/exchanges/deribit/DeribitConnector.kt`
- Test: `app/src/commonTest/kotlin/com/diveintocrypto/android/engine/exchanges/deribit/DeribitConnectorTest.kt`

The connector maps canonical `Channel`s → Deribit wire channels, builds the subscribe frame, and streams. Expose `buildChannels`, `subscribeFrame`, and `handleFrameText` as testable units; the live WS loop uses them.

- [ ] **Step 1: failing test** (offline: channel mapping, subscribe frame, frame handling):
```kotlin
package com.diveintocrypto.android.engine.exchanges.deribit

import com.diveintocrypto.android.engine.schema.Channel
import com.diveintocrypto.android.engine.schema.DerivativeTicker
import com.diveintocrypto.android.engine.schema.Venue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeribitConnectorTest {
    private val c = DeribitConnector()

    @Test fun venueIsDeribit() { assertEquals(Venue.DERIBIT, c.venue) }

    @Test fun buildChannelsMapsAndDedups() {
        val chans = c.buildChannels(
            setOf("BTC-PERPETUAL"),
            setOf(Channel.DERIVATIVE_TICKER, Channel.OPTIONS_CHAIN, Channel.TRADE, Channel.BOOK_DELTA, Channel.BOOK_SNAPSHOT),
        )
        // ticker maps from both derivative_ticker & options_chain → one; book_delta & book_snapshot → one
        assertTrue("ticker.BTC-PERPETUAL.100ms" in chans)
        assertTrue("trades.BTC-PERPETUAL.100ms" in chans)
        assertTrue("book.BTC-PERPETUAL.100ms" in chans)
        assertEquals(chans, chans.distinct()) // no dups
    }

    @Test fun subscribeFrameIsJsonRpc() {
        val frame = c.subscribeFrame(listOf("ticker.BTC-PERPETUAL.100ms"))
        assertTrue(frame.contains("\"jsonrpc\":\"2.0\""))
        assertTrue(frame.contains("public/subscribe"))
        assertTrue(frame.contains("ticker.BTC-PERPETUAL.100ms"))
    }

    @Test fun handleFrameTextNormalizes() {
        val text = """{"params":{"channel":"ticker.BTC-PERPETUAL.100ms","data":{
          "instrument_name":"BTC-PERPETUAL","timestamp":1700000000000,
          "mark_price":95010.0,"index_price":95005.0,"last_price":95000.0}}}"""
        val recs = c.handleFrameText(text, localTs = 5L)
        assertTrue(recs.any { it is DerivativeTicker })
    }

    @Test fun handleFrameTextSwallowsBadJson() {
        assertTrue(c.handleFrameText("not json", localTs = 5L).isEmpty())
    }
}
```

- [ ] **Step 2: run, confirm FAIL.**

- [ ] **Step 3: implement** `DeribitConnector.kt`:
```kotlin
package com.diveintocrypto.android.engine.exchanges.deribit

import com.diveintocrypto.android.data.binance.NetworkLog
import com.diveintocrypto.android.data.binance.binanceHttpClient
import com.diveintocrypto.android.engine.exchanges.Connector
import com.diveintocrypto.android.engine.schema.Channel
import com.diveintocrypto.android.engine.schema.Record
import com.diveintocrypto.android.engine.schema.Venue
import com.diveintocrypto.android.platform.logError
import com.diveintocrypto.android.platform.nowMillis
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.Url
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Deribit public WS connector — port of `crypcodile/exchanges/deribit/connector.py`.
 * Streams canonical records from `wss://www.deribit.com/ws/api/v2` via JSON-RPC
 * `public/subscribe`. Reconnects with exponential backoff; isolates per-frame parse errors.
 */
class DeribitConnector(
    private val httpClient: HttpClient = binanceHttpClient(),
    private val wsUrl: String = WS_URL,
) : Connector {
    override val venue: Venue = Venue.DERIBIT

    private val json = Json { ignoreUnknownKeys = true }

    /** Map canonical channels → Deribit wire channels (deduped, sorted). */
    fun buildChannels(symbols: Set<String>, channels: Set<Channel>): List<String> {
        val out = sortedSetOf<String>()
        for (sym in symbols) for (ch in channels) {
            val wire = when (ch) {
                Channel.TRADE, Channel.LIQUIDATION -> "trades.$sym.$WS_INTERVAL"
                Channel.BOOK_DELTA, Channel.BOOK_SNAPSHOT -> "book.$sym.$WS_INTERVAL"
                Channel.DERIVATIVE_TICKER, Channel.OPTIONS_CHAIN, Channel.FUNDING, Channel.OPEN_INTEREST ->
                    "ticker.$sym.$WS_INTERVAL"
                else -> null
            }
            if (wire != null) out.add(wire)
        }
        return out.toList()
    }

    /** JSON-RPC 2.0 public/subscribe frame for the given wire channels. */
    fun subscribeFrame(wireChannels: List<String>): String {
        val chans = wireChannels.joinToString(",") { "\"$it\"" }
        return """{"jsonrpc":"2.0","method":"public/subscribe","params":{"channels":[$chans]}}"""
    }

    /** Parse one WS text frame → records, swallowing malformed frames. */
    fun handleFrameText(text: String, localTs: Long): List<Record> = runCatching {
        DeribitNormalize.normalizeMessage(json.parseToJsonElement(text).jsonObject, localTs)
    }.getOrElse {
        logError("Deribit", "frame parse error: ${it.message}", it)
        emptyList()
    }

    override fun stream(channels: Set<Channel>, symbols: Set<String>): Flow<Record> = callbackFlow {
        val wire = buildChannels(symbols, channels)
        if (wire.isEmpty()) { close(); return@callbackFlow }
        val host = Url(wsUrl).host
        var backoffMs = 1_000L
        val job = launch {
            while (isActive) {
                val opened = nowMillis()
                try {
                    httpClient.webSocket(wsUrl) {
                        NetworkLog.recordWs(host = host, path = "/ws/api/v2", status = 101, durationMs = 0)
                        send(subscribeFrame(wire))
                        backoffMs = 1_000L // reset on successful connect
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            for (r in handleFrameText(frame.readText(), nowMillis())) trySend(r)
                        }
                    }
                    NetworkLog.recordWs(host, "/ws/api/v2", 1000, nowMillis() - opened, "Closed")
                } catch (t: Throwable) {
                    NetworkLog.recordWs(host, "/ws/api/v2", -1, nowMillis() - opened, t.message ?: "error")
                }
                if (!isActive) break
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L) // exp backoff, cap 30s
            }
        }
        awaitClose { job.cancel() }
    }

    /** Deribit has no plain REST record backfill wired in this milestone. */
    override suspend fun backfill(channel: Channel, symbolRaw: String, startNs: Long, endNs: Long, limit: Int): List<Record> =
        emptyList()

    companion object {
        const val WS_URL = "wss://www.deribit.com/ws/api/v2"
        const val WS_INTERVAL = "100ms"
    }
}
```

- [ ] **Step 4: run, confirm PASS (5).**
- [ ] **Step 5: commit** `feat(engine): Deribit WS connector`.

---

### Task 4: Wire Deribit into MarketDataEngine

**Files:**
- Modify: `app/src/commonMain/kotlin/com/diveintocrypto/android/engine/MarketDataEngine.kt`
- Test: `app/src/commonTest/kotlin/com/diveintocrypto/android/engine/DeribitEngineWiringTest.kt`

Add a `DeribitConnector` to the engine and typed convenience flows. Keep the existing ctor working (Deribit defaults).

- [ ] **Step 1: failing test:**
```kotlin
package com.diveintocrypto.android.engine

import com.diveintocrypto.android.data.SettingsStore
import com.diveintocrypto.android.data.binance.BinanceFuturesClient
import com.diveintocrypto.android.data.binance.BinanceSpotClient
import com.diveintocrypto.android.data.binance.BinanceWsClient
import com.diveintocrypto.android.engine.exchanges.binance.BinanceConnector
import com.diveintocrypto.android.engine.exchanges.deribit.DeribitConnector
import com.diveintocrypto.android.engine.schema.Channel
import com.diveintocrypto.android.engine.schema.DerivativeTicker
import com.diveintocrypto.android.testutil.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeribitEngineWiringTest {
    private fun engine() = MarketDataEngine(
        BinanceConnector(BinanceSpotClient(), BinanceFuturesClient(), BinanceWsClient()),
        SettingsStore(InMemoryKeyValueStore()),
        DeribitConnector(),
    )

    @Test fun engineExposesDeribitConnector() {
        val e = engine()
        // deribit channel wiring reachable via the engine's connector
        val wire = e.deribit.buildChannels(setOf("BTC-PERPETUAL"), setOf(Channel.DERIVATIVE_TICKER))
        assertTrue("ticker.BTC-PERPETUAL.100ms" in wire)
    }

    @Test fun handleFrameThroughEngineConnector() {
        val e = engine()
        val text = """{"params":{"channel":"ticker.BTC-PERPETUAL.100ms","data":{
          "instrument_name":"BTC-PERPETUAL","timestamp":1700000000000,"mark_price":1.0,"index_price":1.0}}}"""
        assertTrue(e.deribit.handleFrameText(text, 5L).any { it is DerivativeTicker })
    }
}
```

- [ ] **Step 2: run, confirm FAIL.**

- [ ] **Step 3: modify** `MarketDataEngine.kt`: add a third constructor param `deribit: DeribitConnector = DeribitConnector()` (exposed as `val deribit`), plus typed flows. Add import `com.diveintocrypto.android.engine.exchanges.deribit.DeribitConnector`, `engine.schema.Channel`, `engine.schema.Record`, `engine.schema.OptionsChain`, `engine.schema.DerivativeTicker`, `kotlinx.coroutines.flow.*`. Append:
```kotlin
class MarketDataEngine(
    private val binance: BinanceConnector,
    private val settingsStore: SettingsStore,
    val deribit: DeribitConnector = DeribitConnector(),
) {
    // ... existing methods unchanged ...

    /** Raw canonical Deribit record stream for the given channels/symbols. */
    fun deribitRecords(channels: Set<Channel>, symbols: Set<String>): Flow<Record> =
        deribit.stream(channels, symbols)

    /** Live option-chain ticks (OptionsChain) for the given option instrument symbols. */
    fun optionChainStream(symbols: Set<String>): Flow<OptionsChain> =
        deribit.stream(setOf(Channel.OPTIONS_CHAIN), symbols).filterIsInstance<OptionsChain>()

    /** Live derivative tickers (perp/future) for the given symbols. */
    fun derivativeTickerStream(symbols: Set<String>): Flow<DerivativeTicker> =
        deribit.stream(setOf(Channel.DERIVATIVE_TICKER), symbols).filterIsInstance<DerivativeTicker>()
}
```
(Keep all existing methods. Update `AppContainer`'s `MarketDataEngine(...)` construction is unaffected — the new param defaults. Verify `filterIsInstance` import: `kotlinx.coroutines.flow.filterIsInstance`.)

- [ ] **Step 4: run, confirm PASS.**
- [ ] **Step 5: commit** `feat(engine): wire Deribit connector into MarketDataEngine`.

---

### Task 5: M3 milestone gate (incl. real-network smoke)

- [ ] **Step 1: full suite + compile:**
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/Users/nazmi/Library/Android/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
cd /Users/nazmi/dive-into-crypto/android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```
Expected: BUILD SUCCESSFUL, all M1+M2+M3 tests green.

- [ ] **Step 2: REAL Deribit WS smoke (prove live deep data flows — controller runs this).** Confirm egress + that the subscribe frame yields real option greeks. Run a raw WSS check (any available tool — `websocat`, or a short Python with `websockets`/`aiohttp`):
  - Connect `wss://www.deribit.com/ws/api/v2`, send `{"jsonrpc":"2.0","method":"public/subscribe","params":{"channels":["ticker.BTC-PERPETUAL.100ms"]}}`, read 2-3 frames, confirm a `mark_price`/`index_price` arrives.
  - Then subscribe an option (fetch one option instrument via REST `public/get_instruments?currency=BTC&kind=option&expired=false`, take the nearest expiry strike, subscribe `ticker.{that}.100ms`), read a frame, confirm `greeks` + `mark_iv` present.
  - Record the observation in the milestone notes. (This validates the connector's real target without a flaky in-suite network test.)

- [ ] **Step 3:** No commit unless a fix was needed.

---

## Self-Review

**Spec coverage (spec §2 Deribit connector + §4 sources):** options chain + greeks + IV (Task 2 ✓), perp/future derivative ticker + funding (Task 2 ✓), trades + liquidations (Task 2 ✓), L2 book snapshot/delta (Task 2 ✓), live WS stream + reconnect/backoff + per-frame isolation (Task 3 ✓), engine wiring + typed flows (Task 4 ✓), real-network proof (Task 5 ✓). **DVOL volatility index deferred to M4** (single index instrument; trivial to add alongside the options screen). Order-book reconstruction (incremental L2 apply) deferred to M5 perf (snapshot/delta records are captured here; the live book accumulator is a render-time concern).

**Placeholder scan:** none — full code in every task; the live-WS loop is concrete; the smoke step names exact frames.

**Type consistency:** `DeribitSymbols.parseOptionSymbol→ParsedOption`, `DeribitNormalize.normalizeMessage(JsonObject, Long): List<Record>`, `DeribitConnector.{venue,buildChannels,subscribeFrame,handleFrameText,stream,backfill}`, `MarketDataEngine.deribit` + `deribitRecords/optionChainStream/derivativeTickerStream` are consistent. All consume M1 schema verbatim; IV ÷100 and raw greeks preserved per the Python reference.

**Risk:** Deribit WS unit tests are offline (frame-handling), so the live socket is only covered by the Task 5 smoke. That's deliberate — a live socket in the unit suite would be flaky. The smoke proves the real path once; the connector reuses the same `callbackFlow`+`webSocket` pattern already proven by `BinanceWsClient`.
