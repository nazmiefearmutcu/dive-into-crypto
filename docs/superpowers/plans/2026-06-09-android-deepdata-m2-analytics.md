# M2 — Crypcodile-KMP Analytics Port — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Port Crypcodile's analytics math kernels to Kotlin (`engine/analytics/`) — Black-76 pricing/greeks/IV solver, IV surface/skew/term-structure/25Δ RR-BF, funding APR, basis, and trade resampling (OHLCV + VWAP) — operating on in-memory canonical record lists, with parity tests asserting against ground-truth values produced by the real Python Crypcodile.

**Architecture:** Pure, stateless functions over `List<Record>` (the on-device engine has no Parquet lake, so Crypcodile's DuckDB-SQL data-shaping is re-expressed as in-memory Kotlin). The math kernels (greeks, IV solver, RR/BF, APR, basis %) are 1:1 ports. Each file is independently testable.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines (none needed here — pure functions), `kotlin.math`. NOTE: `kotlin.math` has NO `erf`; Black-76 ships its own cumulative-normal.

**Build/test env (JDK 17 required):**
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/Users/nazmi/Library/Android/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
cd /Users/nazmi/dive-into-crypto/android
./gradlew :app:testDebugUnitTest --tests '*<TestName>*'
```
Repo root `/Users/nazmi/dive-into-crypto`, branch `feat/android-crypcodile-deep-data`. Commits carry NO Claude/AI attribution trailer.

**Dependencies on M1:** `engine/schema/Enums.kt` (`OptType` with `.CALL/.PUT`), `engine/schema/Records.kt` (`OptionsChain`, `Funding`, `Trade`, `DerivativeTicker`, `OHLCV`, `Venue`).

**Ground-truth parity values** (produced by running the real Python `crypcodile.analytics` — assert Kotlin against these):
| Input | Reference output |
|---|---|
| `norm_cdf(0)` | `0.5` |
| `norm_cdf(1)` | `0.8413447460685429` |
| `norm_pdf(0)` | `0.3989422804014327` |
| `bs_price(F=100,K=100,t=0.5,v=0.6,CALL,r=0)` | `16.799597142736353` |
| `bs_price(... PUT)` | `16.799597142736353` |
| `bs_greeks(F=100,K=100,t=0.5,v=0.6,CALL)` | delta `0.5839979857136818`, gamma `0.009193951055423533`, vega `27.5818531662706`, theta `-16.54911189976236`, rho `-8.399798571368176` |
| `bs_greeks(... PUT).delta` | `-0.4160020142863183` |
| `implied_vol(16.799597142736353, F=100,K=100,t=0.5,CALL)` | `≈0.6` (`0.5999999958536629`) |
| `bs_price(F=110,K=100,t=0.25,v=0.8,CALL)` | `22.108139117843983`; `implied_vol` recovers `≈0.8` |
| `periods_per_year(8)` | `1095.0` |
| `apr_from_rate(0.0001, 8)` | `0.1095` |
| `bs_price(F=100,K=90,t=-1,...,CALL)` (expired) | `10.0` |

---

## File Structure (M2)

| File | Responsibility |
|---|---|
| `engine/analytics/BlackScholes.kt` (create) | normCdf/normPdf, price, greeks (Greeks data class), impliedVol |
| `engine/analytics/Funding.kt` (create) | periodsPerYear, aprFromRate, fundingApr(list)→rows, fundingSummary |
| `engine/analytics/Basis.kt` (create) | spotFutureBasis (ASOF), perpBasis |
| `engine/analytics/VolSurface.kt` (create) | ivSurface, volSkew, termStructure, riskReversalButterfly |
| `engine/analytics/Resample.kt` (create) | resampleOhlcv, resampleMetrics (VWAP/$-vol/count) |
| + matching `*Test.kt` under commonTest | parity + behavior tests |

---

### Task 1: Black-76 pricing, greeks, IV solver

**Files:**
- Create: `app/src/commonMain/kotlin/com/diveintocrypto/android/engine/analytics/BlackScholes.kt`
- Test: `app/src/commonTest/kotlin/com/diveintocrypto/android/engine/analytics/BlackScholesTest.kt`

- [ ] **Step 1: Write the failing test** (exact Python parity values):

```kotlin
package com.diveintocrypto.android.engine.analytics

import com.diveintocrypto.android.engine.schema.OptType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlackScholesTest {
    @Test fun normCdfParity() {
        assertEquals(0.5, BlackScholes.normCdf(0.0), 1e-12)
        assertEquals(0.8413447460685429, BlackScholes.normCdf(1.0), 1e-6)
        assertEquals(0.3989422804014327, BlackScholes.normPdf(0.0), 1e-12)
    }

    @Test fun priceParityAtmCallPut() {
        val c = BlackScholes.price(100.0, 100.0, 0.5, 0.6, OptType.CALL)
        val p = BlackScholes.price(100.0, 100.0, 0.5, 0.6, OptType.PUT)
        assertEquals(16.799597142736353, c, 1e-3)
        assertEquals(16.799597142736353, p, 1e-3)
    }

    @Test fun greeksParity() {
        val g = BlackScholes.greeks(100.0, 100.0, 0.5, 0.6, OptType.CALL)
        assertEquals(0.5839979857136818, g.delta, 1e-5)
        assertEquals(0.009193951055423533, g.gamma, 1e-7)
        assertEquals(27.5818531662706, g.vega, 1e-3)
        assertEquals(-16.54911189976236, g.theta, 1e-3)
        assertEquals(-8.399798571368176, g.rho, 1e-3)
        val gp = BlackScholes.greeks(100.0, 100.0, 0.5, 0.6, OptType.PUT)
        assertEquals(-0.4160020142863183, gp.delta, 1e-5)
    }

    @Test fun impliedVolRecovers() {
        val price = 16.799597142736353
        val iv = BlackScholes.impliedVol(price, 100.0, 100.0, 0.5, OptType.CALL)
        assertNotNull(iv)
        assertEquals(0.6, iv!!, 1e-4)
        // OTM
        val p2 = BlackScholes.price(110.0, 100.0, 0.25, 0.8, OptType.CALL)
        assertEquals(22.108139117843983, p2, 1e-3)
        val iv2 = BlackScholes.impliedVol(p2, 110.0, 100.0, 0.25, OptType.CALL)
        assertNotNull(iv2)
        assertEquals(0.8, iv2!!, 1e-4)
    }

    @Test fun expiredAndBounds() {
        assertEquals(10.0, BlackScholes.price(100.0, 90.0, -1.0, 0.5, OptType.CALL), 1e-12)
        // expired greeks all zero
        val g = BlackScholes.greeks(100.0, 90.0, -1.0, 0.5, OptType.CALL)
        assertEquals(0.0, g.delta); assertEquals(0.0, g.gamma); assertEquals(0.0, g.vega)
        // impliedVol returns null when expired or below intrinsic
        assertNull(BlackScholes.impliedVol(5.0, 100.0, 90.0, -1.0, OptType.CALL))
        assertNull(BlackScholes.impliedVol(0.0001, 100.0, 90.0, 0.5, OptType.CALL)) // below intrinsic (10)
    }
}
```

- [ ] **Step 2: Run, confirm FAIL.**

- [ ] **Step 3: Implement** `BlackScholes.kt`. Port of `crypcodile/analytics/blackscholes.py`. CRITICAL: implement `normCdf` with a high-accuracy approximation (Abramowitz-Stegun 26.2.17, abs error < 7.5e-8) — `kotlin.math` has no `erf`. `normPdf` is exact.

```kotlin
package com.diveintocrypto.android.engine.analytics

import com.diveintocrypto.android.engine.schema.OptType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Black-76 option pricing, greeks, and implied-vol solver — Kotlin port of
 * `crypcodile/analytics/blackscholes.py`. European options on a forward/index F
 * with discount factor D = exp(-rate*t). Pure `kotlin.math`.
 *
 * Greeks natural units: vega per 1.0 vol unit, theta per 1.0 year, rho per 1.0 rate.
 */
object BlackScholes {

    /** Standard normal PDF: exp(-x²/2)/sqrt(2π). Exact. */
    fun normPdf(x: Double): Double = exp(-0.5 * x * x) / sqrt(2.0 * PI)

    /**
     * Standard normal CDF via Abramowitz-Stegun 26.2.17 (abs error < 7.5e-8).
     * `kotlin.math` has no erf, so this rational approximation stands in.
     */
    fun normCdf(x: Double): Double {
        val l = abs(x)
        val k = 1.0 / (1.0 + 0.2316419 * l)
        val a1 = 0.319381530; val a2 = -0.356563782; val a3 = 1.781477937
        val a4 = -1.821255978; val a5 = 1.330274429
        val w = 1.0 - normPdf(l) * (a1 * k + a2 * k * k + a3 * k * k * k +
                a4 * k * k * k * k + a5 * k * k * k * k * k)
        return if (x >= 0.0) w else 1.0 - w
    }

    data class Greeks(
        val delta: Double, val gamma: Double, val vega: Double,
        val theta: Double, val rho: Double,
    )

    /** Black-76 price. Expired (t<=0) or vol==0 → discounted intrinsic. */
    fun price(forward: Double, strike: Double, tYears: Double, vol: Double,
              optType: OptType, rate: Double = 0.0): Double {
        require(vol >= 0) { "vol must be >= 0, got $vol" }
        val d = if (tYears > 0) exp(-rate * tYears) else 1.0
        if (tYears <= 0 || vol == 0.0) {
            return if (optType == OptType.CALL) d * maxOf(forward - strike, 0.0)
                   else d * maxOf(strike - forward, 0.0)
        }
        val sqrtT = sqrt(tYears)
        val d1 = (ln(forward / strike) + 0.5 * vol * vol * tYears) / (vol * sqrtT)
        val d2 = d1 - vol * sqrtT
        return if (optType == OptType.CALL)
            d * (forward * normCdf(d1) - strike * normCdf(d2))
        else
            d * (strike * normCdf(-d2) - forward * normCdf(-d1))
    }

    /** Black-76 greeks. Expired (t<=0) or vol==0 → all zeros. */
    fun greeks(forward: Double, strike: Double, tYears: Double, vol: Double,
               optType: OptType, rate: Double = 0.0): Greeks {
        require(vol >= 0) { "vol must be >= 0, got $vol" }
        if (tYears <= 0 || vol == 0.0) return Greeks(0.0, 0.0, 0.0, 0.0, 0.0)
        val d = exp(-rate * tYears)
        val sqrtT = sqrt(tYears)
        val d1 = (ln(forward / strike) + 0.5 * vol * vol * tYears) / (vol * sqrtT)
        val nd1 = normCdf(d1)
        val npd1 = normPdf(d1)
        val delta = if (optType == OptType.CALL) d * nd1 else -d * normCdf(-d1)
        val gamma = d * npd1 / (forward * vol * sqrtT)
        val vega = d * forward * npd1 * sqrtT
        val priceVal = price(forward, strike, tYears, vol, optType, rate)
        val theta = -d * forward * npd1 * vol / (2.0 * sqrtT) + rate * priceVal
        val rho = -tYears * priceVal
        return Greeks(delta, gamma, vega, theta, rho)
    }

    private const val IV_MIN = 1e-6
    private const val IV_MAX = 10.0
    private const val TOL = 1e-6
    private const val MAX_ITER = 100

    /**
     * Solve implied vol reproducing [price]. Newton (seed 0.5) → bisection fallback
     * on [1e-6, 10]. Returns null when expired, outside no-arb bounds, or non-convergent.
     */
    fun impliedVol(price: Double, forward: Double, strike: Double, tYears: Double,
                   optType: OptType, rate: Double = 0.0): Double? {
        if (tYears <= 0) return null
        val d = exp(-rate * tYears)
        val intrinsic: Double; val upper: Double
        if (optType == OptType.CALL) { intrinsic = d * maxOf(forward - strike, 0.0); upper = d * forward }
        else { intrinsic = d * maxOf(strike - forward, 0.0); upper = d * strike }
        if (price <= intrinsic || price >= upper) return null

        var vol = 0.5
        var newtonFailed = false
        repeat(MAX_ITER) {
            val p = price(forward, strike, tYears, vol, optType, rate)
            val diff = p - price
            if (abs(diff) < TOL) return vol
            val v = greeks(forward, strike, tYears, vol, optType, rate).vega
            if (abs(v) < 1e-10) { newtonFailed = true; return@repeat }
            val volNew = vol - diff / v
            if (volNew < IV_MIN || volNew > IV_MAX) { newtonFailed = true; return@repeat }
            vol = volNew
        }
        // Note: the repeat above does not early-break like Python; replicate Python's
        // break-then-bisection by always running bisection if Newton didn't return.
        var lo = IV_MIN; var hi = IV_MAX
        val pLo = price(forward, strike, tYears, lo, optType, rate)
        val pHi = price(forward, strike, tYears, hi, optType, rate)
        if (!(pLo <= price && price <= pHi)) return null
        repeat(MAX_ITER) {
            val mid = 0.5 * (lo + hi)
            val pMid = price(forward, strike, tYears, mid, optType, rate)
            if (abs(pMid - price) < TOL) return mid
            if (pMid < price) lo = mid else hi = mid
        }
        return null
    }
}
```

> IMPLEMENTER NOTE: The Python solver `break`s out of the Newton loop the moment a step goes out of bounds / vega≈0, then runs bisection. Kotlin's `repeat` can't `break`; restructure the Newton phase as a `for (i in 0 until MAX_ITER)` loop with a real `break` so you do NOT keep iterating Newton after a failure, then fall through to bisection. The provided code uses `return@repeat` which is WRONG (it continues the loop) — FIX THIS: use a `for` loop with `break`. Verify the `impliedVolRecovers` test passes (it exercises both ATM Newton-convergence and OTM).

- [ ] **Step 4: Run, confirm PASS (all 5 tests).** If `impliedVol` doesn't converge, fix the Newton/bisection control flow per the note.

- [ ] **Step 5: Commit:**
```bash
cd /Users/nazmi/dive-into-crypto
git add android/app/src/commonMain/kotlin/com/diveintocrypto/android/engine/analytics/BlackScholes.kt \
        android/app/src/commonTest/kotlin/com/diveintocrypto/android/engine/analytics/BlackScholesTest.kt
git commit -m "feat(engine): Black-76 pricing, greeks, IV solver"
```

---

### Task 2: Funding analytics

**Files:**
- Create: `app/src/commonMain/kotlin/com/diveintocrypto/android/engine/analytics/Funding.kt`
- Test: `app/src/commonTest/kotlin/com/diveintocrypto/android/engine/analytics/FundingAnalyticsTest.kt`

Port of `crypcodile/analytics/funding.py` pure-math + the per-event/cumulative loop, operating on `List<Funding>`.

- [ ] **Step 1: Write the failing test:**
```kotlin
package com.diveintocrypto.android.engine.analytics

import com.diveintocrypto.android.engine.schema.Funding
import com.diveintocrypto.android.engine.schema.Venue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FundingAnalyticsTest {
    private fun f(ts: Long, rate: Double, ih: Int = 8) = Funding(
        venue = Venue.DERIBIT, symbol = "deribit:BTC-PERPETUAL", symbolRaw = "BTC-PERPETUAL",
        exchangeTs = ts, localTs = ts, fundingRate = rate, fundingTs = ts, intervalHours = ih)

    @Test fun ppyAndApr() {
        assertEquals(1095.0, FundingAnalytics.periodsPerYear(8), 1e-12)
        assertEquals(0.1095, FundingAnalytics.aprFromRate(0.0001, 8), 1e-12)
    }

    @Test fun fundingAprRowsSortedWithCumulative() {
        val rows = FundingAnalytics.fundingApr(listOf(f(30, 0.0002), f(10, 0.0001), f(20, -0.0001)))
        // sorted by fundingTs ascending: 10(0.0001), 20(-0.0001), 30(0.0002)
        assertEquals(listOf(10L, 20L, 30L), rows.map { it.fundingTs })
        assertEquals(0.0001 * 1095.0, rows[0].apr, 1e-12)
        // cumulative running sum of rate
        assertEquals(0.0001, rows[0].cumulativeFunding, 1e-12)
        assertEquals(0.0001 + -0.0001, rows[1].cumulativeFunding, 1e-12)
        assertEquals(0.0001 + -0.0001 + 0.0002, rows[2].cumulativeFunding, 1e-12)
    }

    @Test fun summary() {
        val s = FundingAnalytics.fundingSummary(listOf(f(10, 0.0001), f(20, 0.0003)))
        assertEquals(2, s!!.nEvents)
        assertEquals(0.0002, s.meanRate, 1e-12)
        assertEquals(0.0004, s.totalFunding, 1e-12)
    }

    @Test fun emptyReturnsNullSummary() {
        assertTrue(FundingAnalytics.fundingApr(emptyList()).isEmpty())
        assertEquals(null, FundingAnalytics.fundingSummary(emptyList()))
    }
}
```

- [ ] **Step 2: Run, confirm FAIL.**

- [ ] **Step 3: Implement** `Funding.kt`:
```kotlin
package com.diveintocrypto.android.engine.analytics

import com.diveintocrypto.android.engine.schema.Funding

/** Funding APR analytics — Kotlin port of `crypcodile/analytics/funding.py` (in-memory). */
object FundingAnalytics {
    private const val DEFAULT_INTERVAL_HOURS = 8

    /** 8760 / interval_hours (hours per non-leap year). */
    fun periodsPerYear(intervalHours: Int): Double {
        require(intervalHours > 0) { "interval_hours must be positive, got $intervalHours" }
        return 8760.0 / intervalHours
    }

    /** Simple (non-compounded) annualisation: rate * periodsPerYear. */
    fun aprFromRate(rate: Double, intervalHours: Int): Double =
        rate * periodsPerYear(intervalHours)

    data class FundingRow(
        val fundingTs: Long, val fundingRate: Double, val intervalHours: Int,
        val apr: Double, val cumulativeFunding: Double,
    )

    /** Per-event APR + running cumulative funding, sorted by fundingTs ascending. */
    fun fundingApr(funding: List<Funding>): List<FundingRow> {
        if (funding.isEmpty()) return emptyList()
        val sorted = funding.sortedBy { it.fundingTs }
        var running = 0.0
        return sorted.map { fr ->
            val ih = if (fr.intervalHours > 0) fr.intervalHours else DEFAULT_INTERVAL_HOURS
            running += fr.fundingRate
            FundingRow(
                fundingTs = fr.fundingTs, fundingRate = fr.fundingRate, intervalHours = ih,
                apr = fr.fundingRate * (8760.0 / ih), cumulativeFunding = running,
            )
        }
    }

    data class FundingSummaryRow(
        val nEvents: Int, val meanRate: Double, val meanApr: Double, val totalFunding: Double,
    )

    /** Single-row summary, or null when empty. */
    fun fundingSummary(funding: List<Funding>): FundingSummaryRow? {
        val rows = fundingApr(funding)
        if (rows.isEmpty()) return null
        val n = rows.size
        val meanRate = rows.sumOf { it.fundingRate } / n
        val meanApr = rows.sumOf { it.apr } / n
        val total = rows.sumOf { it.fundingRate }
        return FundingSummaryRow(n, meanRate, meanApr, total)
    }
}
```

- [ ] **Step 4: Run, confirm PASS.**
- [ ] **Step 5: Commit:**
```bash
cd /Users/nazmi/dive-into-crypto
git add android/app/src/commonMain/kotlin/com/diveintocrypto/android/engine/analytics/Funding.kt \
        android/app/src/commonTest/kotlin/com/diveintocrypto/android/engine/analytics/FundingAnalyticsTest.kt
git commit -m "feat(engine): funding APR analytics"
```

---

### Task 3: Basis analytics

**Files:**
- Create: `app/src/commonMain/kotlin/com/diveintocrypto/android/engine/analytics/Basis.kt`
- Test: `app/src/commonTest/kotlin/com/diveintocrypto/android/engine/analytics/BasisAnalyticsTest.kt`

Port of `crypcodile/analytics/basis.py`. `spotFutureBasis` pairs each future trade with the nearest PRIOR spot trade (ASOF: largest `spot.localTs <= future.localTs`), drops futures with no prior spot or spot price <= 0. `perpBasis` over `DerivativeTicker` (mark vs index), drops null/<=0.

- [ ] **Step 1: Write the failing test:**
```kotlin
package com.diveintocrypto.android.engine.analytics

import com.diveintocrypto.android.engine.schema.DerivativeTicker
import com.diveintocrypto.android.engine.schema.Side
import com.diveintocrypto.android.engine.schema.Trade
import com.diveintocrypto.android.engine.schema.Venue
import kotlin.test.Test
import kotlin.test.assertEquals

class BasisAnalyticsTest {
    private fun trade(ts: Long, px: Double, venue: Venue, raw: String) = Trade(
        venue = venue, symbol = "${venue.wire}:$raw", symbolRaw = raw,
        exchangeTs = ts, localTs = ts, price = px, amount = 1.0, side = Side.BUY)

    @Test fun spotFutureBasisAsofPriorMatch() {
        val futures = listOf(trade(100, 101.0, Venue.DERIBIT, "BTC-FUT"),
                             trade(200, 103.0, Venue.DERIBIT, "BTC-FUT"))
        val spot = listOf(trade(50, 100.0, Venue.BINANCE_SPOT, "BTCUSDT"),
                          trade(150, 102.0, Venue.BINANCE_SPOT, "BTCUSDT"))
        val rows = BasisAnalytics.spotFutureBasis(futures, spot)
        // future@100 → prior spot@50 (100.0): basis=1.0, pct=0.01
        // future@200 → prior spot@150 (102.0): basis=1.0, pct≈0.009803...
        assertEquals(2, rows.size)
        assertEquals(1.0, rows[0].basis, 1e-12)
        assertEquals(0.01, rows[0].basisPct, 1e-12)
        assertEquals(1.0, rows[1].basis, 1e-12)
        assertEquals(1.0 / 102.0, rows[1].basisPct, 1e-12)
    }

    @Test fun spotFutureBasisAnnualized() {
        val futures = listOf(trade(0, 101.0, Venue.DERIBIT, "BTC-FUT"))
        val spot = listOf(trade(0, 100.0, Venue.BINANCE_SPOT, "BTCUSDT"))
        val expiry = (86_400L * 1_000_000_000L) * 365  // 365 days in ns from ts=0
        val rows = BasisAnalytics.spotFutureBasis(futures, spot, expiryNs = expiry)
        // days_to_expiry=365 → annualized = 0.01 * 365 / 365 = 0.01
        assertEquals(0.01, rows[0].annualizedPct!!, 1e-9)
    }

    @Test fun perpBasisMarkVsIndex() {
        val t = DerivativeTicker(venue = Venue.DERIBIT, symbol = "deribit:BTC-PERPETUAL",
            symbolRaw = "BTC-PERPETUAL", exchangeTs = 10, localTs = 10,
            markPrice = 100.5, indexPrice = 100.0)
        val rows = BasisAnalytics.perpBasis(listOf(t))
        assertEquals(1, rows.size)
        assertEquals(0.5, rows[0].basis, 1e-12)
        assertEquals(0.005, rows[0].basisPct, 1e-12)
    }

    @Test fun perpBasisDropsNullOrZero() {
        val bad = DerivativeTicker(venue = Venue.DERIBIT, symbol = "deribit:BTC-PERPETUAL",
            symbolRaw = "BTC-PERPETUAL", exchangeTs = 10, localTs = 10,
            markPrice = null, indexPrice = 100.0)
        assertEquals(0, BasisAnalytics.perpBasis(listOf(bad)).size)
    }
}
```

- [ ] **Step 2: Run, confirm FAIL.**

- [ ] **Step 3: Implement** `Basis.kt`:
```kotlin
package com.diveintocrypto.android.engine.analytics

import com.diveintocrypto.android.engine.schema.DerivativeTicker
import com.diveintocrypto.android.engine.schema.Trade

/** Spot-future & perpetual basis — Kotlin port of `crypcodile/analytics/basis.py` (in-memory). */
object BasisAnalytics {

    data class SpotFutureBasisRow(
        val localTs: Long, val futurePrice: Double, val spotPrice: Double,
        val basis: Double, val basisPct: Double, val annualizedPct: Double? = null,
    )

    private const val NS_PER_DAY = 86_400.0 * 1e9

    /**
     * ASOF basis: each future trade paired with the nearest prior spot trade
     * (largest spot.localTs <= future.localTs, spotPrice > 0). Ordered by future localTs.
     * When [expiryNs] given, adds annualizedPct = basisPct * 365 / daysToExpiry
     * (null when daysToExpiry <= 0).
     */
    fun spotFutureBasis(
        futures: List<Trade>, spot: List<Trade>, expiryNs: Long? = null,
    ): List<SpotFutureBasisRow> {
        if (futures.isEmpty() || spot.isEmpty()) return emptyList()
        val spotSorted = spot.sortedBy { it.localTs }
        val futSorted = futures.sortedBy { it.localTs }
        val out = ArrayList<SpotFutureBasisRow>()
        for (fut in futSorted) {
            // nearest prior spot: largest localTs <= fut.localTs
            val prior = spotSorted.lastOrNull { it.localTs <= fut.localTs } ?: continue
            if (prior.price <= 0.0) continue
            val basis = fut.price - prior.price
            val basisPct = basis / prior.price
            val ann: Double? = if (expiryNs != null) {
                val days = (expiryNs - fut.localTs) / NS_PER_DAY
                if (days > 0.0) basisPct * 365.0 / days else null
            } else null
            out.add(SpotFutureBasisRow(fut.localTs, fut.price, prior.price, basis, basisPct, ann))
        }
        return out
    }

    data class PerpBasisRow(
        val localTs: Long, val markPrice: Double, val indexPrice: Double,
        val basis: Double, val basisPct: Double,
    )

    /** Perp basis (mark vs index). Drops rows with null/<=0 mark or index. Ordered by localTs. */
    fun perpBasis(tickers: List<DerivativeTicker>): List<PerpBasisRow> =
        tickers.asSequence()
            .filter { it.markPrice != null && it.indexPrice != null && it.markPrice > 0.0 && it.indexPrice > 0.0 }
            .sortedBy { it.localTs }
            .map { t ->
                val m = t.markPrice!!; val i = t.indexPrice!!
                PerpBasisRow(t.localTs, m, i, m - i, (m - i) / i)
            }.toList()
}
```

- [ ] **Step 4: Run, confirm PASS.**
- [ ] **Step 5: Commit:**
```bash
cd /Users/nazmi/dive-into-crypto
git add android/app/src/commonMain/kotlin/com/diveintocrypto/android/engine/analytics/Basis.kt \
        android/app/src/commonTest/kotlin/com/diveintocrypto/android/engine/analytics/BasisAnalyticsTest.kt
git commit -m "feat(engine): spot-future and perp basis analytics"
```

---

### Task 4: Vol surface / skew / term-structure / RR-BF

**Files:**
- Create: `app/src/commonMain/kotlin/com/diveintocrypto/android/engine/analytics/VolSurface.kt`
- Test: `app/src/commonTest/kotlin/com/diveintocrypto/android/engine/analytics/VolSurfaceTest.kt`

Port of `crypcodile/analytics/volsurface.py` over `List<OptionsChain>`. Snapshot = latest row per `(strike, expiry, optType)` with `localTs <= atNs`. IV source: `markIv` if present & >0 (`source="mark_iv"`); else solve from `markPrice`+`underlyingPrice` via `BlackScholes.impliedVol` (`source="computed"`); else `null` (`source="unavailable"`). `moneyness = strike / underlyingPrice`. Depends on Task 1.

- [ ] **Step 1: Write the failing test:**
```kotlin
package com.diveintocrypto.android.engine.analytics

import com.diveintocrypto.android.engine.schema.OptType
import com.diveintocrypto.android.engine.schema.OptionsChain
import com.diveintocrypto.android.engine.schema.Venue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VolSurfaceTest {
    private fun opt(strike: Double, expiry: Long, type: OptType, ts: Long,
                    markIv: Double? = null, up: Double? = 100.0) = OptionsChain(
        venue = Venue.DERIBIT, symbol = "deribit:BTC-x", symbolRaw = "BTC-x",
        exchangeTs = ts, localTs = ts, underlying = "BTC", underlyingPrice = up,
        strike = strike, expiry = expiry, optType = type, markIv = markIv)

    private val E1 = 1_000_000_000_000_000L

    @Test fun surfaceUsesMarkIvAndLatestSnapshot() {
        val chain = listOf(
            opt(100.0, E1, OptType.CALL, ts = 10, markIv = 0.60),
            opt(100.0, E1, OptType.CALL, ts = 20, markIv = 0.65), // latest wins
            opt(120.0, E1, OptType.PUT,  ts = 15, markIv = 0.70),
        )
        val surf = VolSurface.ivSurface(chain, atNs = 100)
        assertEquals(2, surf.size)
        val call = surf.first { it.strike == 100.0 }
        assertEquals(0.65, call.iv!!, 1e-12)         // latest snapshot
        assertEquals("mark_iv", call.source)
        assertEquals(1.0, call.moneyness, 1e-12)     // 100/100
    }

    @Test fun surfaceSnapshotExcludesFutureRows() {
        val chain = listOf(opt(100.0, E1, OptType.CALL, ts = 200, markIv = 0.6))
        assertTrue(VolSurface.ivSurface(chain, atNs = 100).isEmpty())
    }

    @Test fun skewComputesDeltaAndRrBf() {
        // Build a 3-strike skew at one expiry with mark_iv; deltas from BlackScholes.
        val chain = listOf(
            opt(90.0,  E1, OptType.PUT,  ts = 10, markIv = 0.70),
            opt(100.0, E1, OptType.CALL, ts = 10, markIv = 0.60),
            opt(110.0, E1, OptType.CALL, ts = 10, markIv = 0.65),
        )
        val atNs = 0L
        val skew = VolSurface.volSkew(chain, expiryNs = E1, atNs = atNs)
        assertEquals(3, skew.size)
        // every row with iv & t>0 has a delta
        assertTrue(skew.all { it.delta != null })
        val (rr, bf) = VolSurface.riskReversalButterfly(skew, targetDelta = 0.25)
        assertNotNull(rr); assertNotNull(bf)
    }

    @Test fun termStructureAtmPerExpiry() {
        val E2 = E1 * 2
        val chain = listOf(
            opt(100.0, E1, OptType.CALL, ts = 10, markIv = 0.60),
            opt(105.0, E1, OptType.CALL, ts = 10, markIv = 0.62),
            opt(100.0, E2, OptType.CALL, ts = 10, markIv = 0.55),
        )
        val ts = VolSurface.termStructure(chain, atNs = 0)
        assertEquals(listOf(E1, E2), ts.map { it.expiry })
        // ATM strike nearest underlyingPrice (100): for E1 → strike 100, iv 0.60
        assertEquals(100.0, ts[0].atmStrike, 1e-12)
        assertEquals(0.60, ts[0].atmIv!!, 1e-12)
    }
}
```

- [ ] **Step 2: Run, confirm FAIL.**

- [ ] **Step 3: Implement** `VolSurface.kt` per the port spec above. Key structures:
```kotlin
package com.diveintocrypto.android.engine.analytics

import com.diveintocrypto.android.engine.schema.OptType
import com.diveintocrypto.android.engine.schema.OptionsChain
import kotlin.math.abs

/** IV surface, skew, term-structure, 25Δ RR/BF — port of `crypcodile/analytics/volsurface.py`. */
object VolSurface {
    private const val NS_PER_YEAR = 365.0 * 24.0 * 3600.0 * 1e9
    private const val NS_PER_DAY = 86_400.0 * 1e9

    data class SurfacePoint(
        val expiry: Long, val strike: Double, val moneyness: Double,
        val optType: OptType, val iv: Double?, val source: String,
    )

    /** Latest row per (strike, expiry, optType) with localTs <= atNs, IV resolved. */
    fun ivSurface(chain: List<OptionsChain>, atNs: Long, rate: Double = 0.0): List<SurfacePoint> {
        val visible = chain.filter { it.localTs <= atNs }
        if (visible.isEmpty()) return emptyList()
        // latest per (strike, expiry, optType)
        val latest = visible
            .groupBy { Triple(it.strike, it.expiry, it.optType) }
            .map { (_, rows) -> rows.maxBy { it.localTs } }
        return latest.map { r ->
            val (iv, source) = resolveIv(r, atNs, rate)
            val mny = if (r.underlyingPrice != null && r.underlyingPrice > 0.0)
                r.strike / r.underlyingPrice else Double.NaN
            SurfacePoint(r.expiry, r.strike, mny, r.optType, iv, source)
        }
    }

    private fun resolveIv(r: OptionsChain, atNs: Long, rate: Double): Pair<Double?, String> {
        val mi = r.markIv
        if (mi != null && mi.isFinite() && mi > 0.0) return mi to "mark_iv"
        val mp = r.markPrice; val up = r.underlyingPrice
        if (mp != null && mp.isFinite() && mp > 0.0 && up != null && up.isFinite() && up > 0.0) {
            val t = (r.expiry - atNs) / NS_PER_YEAR
            if (t > 0.0) {
                val iv = BlackScholes.impliedVol(mp, up, r.strike, t, r.optType, rate)
                if (iv != null) return iv to "computed"
            }
        }
        return null to "unavailable"
    }

    data class SkewPoint(
        val strike: Double, val moneyness: Double, val optType: OptType,
        val iv: Double?, val delta: Double?,
    )

    fun volSkew(chain: List<OptionsChain>, expiryNs: Long, atNs: Long, rate: Double = 0.0): List<SkewPoint> {
        val surface = ivSurface(chain, atNs, rate).filter { it.expiry == expiryNs }
        if (surface.isEmpty()) return emptyList()
        // underlyingPrice = latest visible row for this expiry
        val up = chain.filter { it.localTs <= atNs && it.expiry == expiryNs }
            .maxByOrNull { it.localTs }?.underlyingPrice
        val t = (expiryNs - atNs) / NS_PER_YEAR
        return surface.sortedBy { it.strike }.map { s ->
            val delta = if (s.iv != null && up != null && t > 0.0)
                BlackScholes.greeks(up, s.strike, t, s.iv, s.optType, rate).delta else null
            SkewPoint(s.strike, s.moneyness, s.optType, s.iv, delta)
        }
    }

    /** 25Δ RR/BF. rr = iv(call@+target) - iv(put@-target); bf = mean(callIv,putIv) - atmIv. */
    fun riskReversalButterfly(skew: List<SkewPoint>, targetDelta: Double = 0.25): Pair<Double?, Double?> {
        if (skew.isEmpty()) return null to null
        val calls = skew.filter { it.optType == OptType.CALL && it.iv != null && it.delta != null }
        val puts = skew.filter { it.optType == OptType.PUT && it.iv != null && it.delta != null }
        val bestCall = calls.minByOrNull { abs(it.delta!! - targetDelta) }
        val bestPut = puts.minByOrNull { abs(it.delta!! - (-targetDelta)) }
        if (bestCall == null || bestPut == null) return null to null
        val all = skew.filter { it.iv != null && it.delta != null }
        val atm = all.minByOrNull { abs(abs(it.delta!!) - 0.5) } ?: return null to null
        val rr = bestCall.iv!! - bestPut.iv!!
        val bf = 0.5 * (bestCall.iv + bestPut.iv) - atm.iv!!
        return rr to bf
    }

    data class TermPoint(val expiry: Long, val daysToExpiry: Double, val atmStrike: Double, val atmIv: Double?)

    /** ATM IV per expiry (ATM strike nearest underlyingPrice; fallback moneyness→1). */
    fun termStructure(chain: List<OptionsChain>, atNs: Long, rate: Double = 0.0): List<TermPoint> {
        val surface = ivSurface(chain, atNs, rate)
        if (surface.isEmpty()) return emptyList()
        val up = chain.filter { it.localTs <= atNs }.maxByOrNull { it.localTs }?.underlyingPrice
        val expiries = surface.map { it.expiry }.distinct().sorted()
        return expiries.mapNotNull { exp ->
            val rows = surface.filter { it.expiry == exp }
            if (rows.isEmpty()) return@mapNotNull null
            val best = if (up != null) rows.minBy { abs(it.strike - up) }
                       else rows.minBy { abs(it.moneyness - 1.0) }
            TermPoint(exp, (exp - atNs) / NS_PER_DAY, best.strike, best.iv)
        }
    }
}
```

- [ ] **Step 4: Run, confirm PASS.**
- [ ] **Step 5: Commit:**
```bash
cd /Users/nazmi/dive-into-crypto
git add android/app/src/commonMain/kotlin/com/diveintocrypto/android/engine/analytics/VolSurface.kt \
        android/app/src/commonTest/kotlin/com/diveintocrypto/android/engine/analytics/VolSurfaceTest.kt
git commit -m "feat(engine): IV surface, skew, term-structure, 25-delta RR/BF"
```

---

### Task 5: Trade resampling (OHLCV + VWAP metrics)

**Files:**
- Create: `app/src/commonMain/kotlin/com/diveintocrypto/android/engine/analytics/Resample.kt`
- Test: `app/src/commonTest/kotlin/com/diveintocrypto/android/engine/analytics/ResampleTest.kt`

In-memory port of `crypcodile/resample/ohlcv.py` + `metrics.py`. Bucket by `floor(localTs / intervalNs) * intervalNs`. OHLCV: open=first(by localTs), high=max, low=min, close=last, volume=sum(amount), buyVolume=sum(amount where side==BUY), sellVolume=sum(amount where side==SELL), numTrades=count. Metrics: vwap=Σ(price·amount)/Σ(amount), dollarVolume=Σ(price·amount), tradeCount=count.

- [ ] **Step 1: Write the failing test:**
```kotlin
package com.diveintocrypto.android.engine.analytics

import com.diveintocrypto.android.engine.schema.Side
import com.diveintocrypto.android.engine.schema.Trade
import com.diveintocrypto.android.engine.schema.Venue
import kotlin.test.Test
import kotlin.test.assertEquals

class ResampleTest {
    private fun tr(ts: Long, px: Double, amt: Double, side: Side = Side.BUY) = Trade(
        venue = Venue.BINANCE_USDM, symbol = "binance-usdm:BTCUSDT", symbolRaw = "BTCUSDT",
        exchangeTs = ts, localTs = ts, price = px, amount = amt, side = side)

    @Test fun ohlcvBucketsByInterval() {
        val interval = 1_000L // ns
        val trades = listOf(
            tr(0, 10.0, 1.0, Side.BUY), tr(500, 12.0, 2.0, Side.SELL), tr(999, 11.0, 1.0, Side.BUY),
            tr(1000, 20.0, 1.0, Side.BUY),  // next bucket
        )
        val bars = Resample.resampleOhlcv(trades, interval)
        assertEquals(2, bars.size)
        val b0 = bars[0]
        assertEquals(0L, b0.exchangeTs)        // bar start carried as exchangeTs
        assertEquals(10.0, b0.open); assertEquals(12.0, b0.high)
        assertEquals(10.0, b0.low); assertEquals(11.0, b0.close)
        assertEquals(4.0, b0.volume, 1e-12)    // 1+2+1
        assertEquals(2.0, b0.buyVolume, 1e-12); assertEquals(2.0, b0.sellVolume, 1e-12)
        assertEquals(3, b0.numTrades)
    }

    @Test fun metricsVwap() {
        val interval = 1_000L
        val rows = Resample.resampleMetrics(listOf(tr(0, 10.0, 2.0), tr(100, 20.0, 2.0)), interval)
        assertEquals(1, rows.size)
        // vwap = (10*2 + 20*2)/(2+2) = 60/4 = 15
        assertEquals(15.0, rows[0].vwap, 1e-12)
        assertEquals(60.0, rows[0].dollarVolume, 1e-12)
        assertEquals(2, rows[0].tradeCount)
    }
}
```

- [ ] **Step 2: Run, confirm FAIL.**

- [ ] **Step 3: Implement** `Resample.kt`:
```kotlin
package com.diveintocrypto.android.engine.analytics

import com.diveintocrypto.android.engine.schema.OHLCV
import com.diveintocrypto.android.engine.schema.Side
import com.diveintocrypto.android.engine.schema.Trade

/** In-memory OHLCV + VWAP resampling — port of `crypcodile/resample/{ohlcv,metrics}.py`. */
object Resample {

    private fun bucketStart(localTs: Long, intervalNs: Long): Long = (localTs / intervalNs) * intervalNs

    /** Resample trades into OHLCV bars (interval in ns). Ordered by bar ascending. */
    fun resampleOhlcv(trades: List<Trade>, intervalNs: Long, interval: String = ""): List<OHLCV> {
        if (trades.isEmpty()) return emptyList()
        val byBucket = trades.groupBy { bucketStart(it.localTs, intervalNs) }
        return byBucket.toSortedMap().map { (bar, rows) ->
            val ordered = rows.sortedBy { it.localTs }
            val first = ordered.first(); val last = ordered.last()
            OHLCV(
                venue = first.venue, symbol = first.symbol, symbolRaw = first.symbolRaw,
                exchangeTs = bar, localTs = bar,
                open = first.price, high = ordered.maxOf { it.price },
                low = ordered.minOf { it.price }, close = last.price,
                volume = ordered.sumOf { it.amount },
                buyVolume = ordered.filter { it.side == Side.BUY }.sumOf { it.amount },
                sellVolume = ordered.filter { it.side == Side.SELL }.sumOf { it.amount },
                numTrades = ordered.size,
                interval = interval,
            )
        }
    }

    data class MetricRow(
        val bar: Long, val symbol: String, val vwap: Double,
        val dollarVolume: Double, val tradeCount: Int,
    )

    /** VWAP, dollar volume, trade count per bucket. Ordered by bar ascending. */
    fun resampleMetrics(trades: List<Trade>, intervalNs: Long): List<MetricRow> {
        if (trades.isEmpty()) return emptyList()
        return trades.groupBy { bucketStart(it.localTs, intervalNs) }.toSortedMap().map { (bar, rows) ->
            val notional = rows.sumOf { it.price * it.amount }
            val vol = rows.sumOf { it.amount }
            MetricRow(
                bar = bar, symbol = rows.first().symbol,
                vwap = if (vol != 0.0) notional / vol else Double.NaN,
                dollarVolume = notional, tradeCount = rows.size,
            )
        }
    }
}
```

- [ ] **Step 4: Run, confirm PASS.**
- [ ] **Step 5: Commit:**
```bash
cd /Users/nazmi/dive-into-crypto
git add android/app/src/commonMain/kotlin/com/diveintocrypto/android/engine/analytics/Resample.kt \
        android/app/src/commonTest/kotlin/com/diveintocrypto/android/engine/analytics/ResampleTest.kt
git commit -m "feat(engine): trade resampling (OHLCV + VWAP metrics)"
```

---

### Task 6: M2 milestone gate

- [ ] Run the full suite + compile:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/Users/nazmi/Library/Android/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
cd /Users/nazmi/dive-into-crypto/android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```
Expected: BUILD SUCCESSFUL, all M1 + M2 tests green. No commit needed (verification only) unless a fix is required.

---

## Self-Review

**Spec coverage (spec §2 analytics):** BlackScholes price/greeks/IV (Task 1 ✓), VolSurface ivSurface/volSkew/termStructure/RR-BF (Task 4 ✓), Funding APR/cumulative/summary (Task 2 ✓), Basis spot-future/perp (Task 3 ✓), Resample OHLCV + VWAP/$-vol (Task 5 ✓). Book reconstruction is deferred to M3 (it pairs with the live order-book connector). Parity vectors: real Python values baked into Task 1 (the transcendental kernel); arithmetic analytics use hand-computed expected values in tests.

**Placeholder scan:** none — every task has full code + concrete test expectations. Task 1 explicitly flags and corrects the `repeat`-vs-`for`/`break` control-flow trap in the IV solver.

**Type consistency:** `OptType` (CALL/PUT), `BlackScholes.{price,greeks,impliedVol,Greeks}`, `FundingAnalytics.*`, `BasisAnalytics.*`, `VolSurface.{SurfacePoint,SkewPoint,TermPoint,ivSurface,volSkew,termStructure,riskReversalButterfly}`, `Resample.{resampleOhlcv,resampleMetrics,MetricRow}` are used consistently. All consume M1 schema types verbatim.

**Known risk:** `normCdf` accuracy (A&S 26.2.17, ~7.5e-8) drives the parity tolerances (delta 1e-5, price 1e-3); if a tighter match is ever needed, swap in a Hart/West cumulative-normal without changing call sites.
