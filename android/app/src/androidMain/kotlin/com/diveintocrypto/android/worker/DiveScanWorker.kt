package com.diveintocrypto.android.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.glance.appwidget.updateAll
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.DiveIntoCryptoApplication
import com.diveintocrypto.android.data.ScanUniverseMode
import com.diveintocrypto.android.data.binance.UpstreamHttpException
import com.diveintocrypto.android.data.binance.isRegionBlocked
import com.diveintocrypto.android.data.createAppContainer
import com.diveintocrypto.android.domain.alerts.AlertEvaluator
import com.diveintocrypto.android.domain.alerts.AlertKind
import com.diveintocrypto.android.domain.alerts.AlertVerdict
import com.diveintocrypto.android.domain.model.Signal
import com.diveintocrypto.android.platform.logError
import com.diveintocrypto.android.platform.synchronized
import com.diveintocrypto.android.ui.scanner.ScannerViewModel
import com.diveintocrypto.android.widget.TopVerdictsWidget
import com.diveintocrypto.android.widget.WidgetScanState
import com.diveintocrypto.android.widget.WidgetVerdictRow
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Periodic BACKGROUND scan (opt-in — see [BackgroundScanScheduler] and the
 * `background_scans_enabled` setting, default FALSE).
 *
 * What it does (BOUNDED, phase-1 coarse only — never the full 12-TF scan):
 *   1. Universe = volume-sorted futures symbols capped by the stored scan-universe
 *      setting ([ScanUniverseMode]).
 *   2. Phase-1 timeframes only ([ScannerViewModel.PHASE1_TFS] = 1d/12h/8h).
 *      Fetches run with capped parallelism under a per-run time budget
 *      ([RUN_BUDGET_MS], below WorkManager's stop threshold). A throttling answer
 *      (429/418) or a regional block stops issuing new requests for the run.
 *   3. Aggregates each symbol across the phase-1 timeframes the way the foreground
 *      scanner does (dominant direction, highest confidence, net-score ranking),
 *      then feeds the survivor head to:
 *        - the alert engine's scan hook (VERDICT / CONFIDENCE rules, plus OI_SPIKE_PCT
 *          when an OI rule is enabled — the OI series is fetched here for those symbols),
 *        - the evidence archive (VerdictRecord append),
 *        - the Glance widget ([TopVerdictsWidget], top-3 + data age + coverage).
 *   4. A run where nothing could be fetched, or nothing could be evaluated, writes
 *      NO new widget state and returns a retry/failure, so the previous good state is
 *      never overwritten by an empty run. A run that covered only part of the planned
 *      universe is still written, marked partial ([WidgetScanState.partial]).
 *
 * DOZE HONESTY: a 15-minute PeriodicWorkRequest is INEXACT. Doze and App
 * Standby defer batched work arbitrarily; nothing here promises exact timing.
 * The widget's data-age line exists precisely so a deferred run is VISIBLE
 * instead of pretended away.
 *
 * The Application-scoped [com.diveintocrypto.android.AppContainer] is reused
 * when available (the normal in-process case); only when the context is not the
 * app's own [DiveIntoCryptoApplication] (e.g. a foreign process) is a fresh
 * container built over the app's SharedPreferences — and its coroutine scopes
 * are explicitly closed in the run's `finally`.
 */
class DiveScanWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    /** One phase-1 (symbol, TF) evaluation result. */
    data class SymbolPhase1Result(
        val symbol: String,
        val tf: String,
        val signal: Signal,
        val confidence: Int,
        val price: Double,
        val finalScore: Double,
    )

    /**
     * One symbol aggregated across the phase-1 timeframes (scanner semantics).
     * [netScore] = |Σ BUY-side finalScore − Σ SELL-side finalScore|, used for ranking.
     */
    data class SymbolVerdict(
        val symbol: String,
        val signal: Signal,
        val confidence: Int,
        val price: Double,
        val netScore: Double,
        val bestScore: Double,
    )

    override suspend fun doWork(): Result {
        val app = applicationContext
        // Prefer the Application-scoped dependency graph. Building a FRESH
        // AppContainer per run makes [AlertEngine] arm a ticker-observation
        // coroutine on its own never-cancelled scope whenever a PRICE_* rule
        // exists — the whole graph leaks per run. The Application container is
        // a process singleton, so reusing it pins nothing new.
        val appScoped = (app as? DiveIntoCryptoApplication)?.container
        val container = appScoped ?: createAppContainer(app)
        return try {
            val settings = container.settingsStore.getSettings()
            val universeMode = ScanUniverseMode.fromLabel(settings.scanUniverse)

            // ── Universe (bounded by the user's phase-1 setting) ─────────────
            val fullUniverse = container.repository.futuresUniverse()
            val universe = fullUniverse.take(universeMode.limit ?: fullUniverse.size)
            if (universe.isEmpty()) {
                // Honest: no symbols available → nothing to grade; bounded retry.
                return retryOrFail()
            }

            // ── Phase-1 coarse scan (1d / 12h / 8h, capped parallelism, time budget) ──
            val planned = universe.size * ScannerViewModel.PHASE1_TFS.size
            val sem = Semaphore(WORKER_PARALLELISM)
            val lock = SynchronizedObject()
            val out = mutableListOf<SymbolPhase1Result>()
            var okFetches = 0 // guarded by [lock]
            val stopFetching = AtomicBoolean(false)
            val regionBlocked = AtomicBoolean(false)
            val jobs = mutableListOf<Deferred<Unit>>()
            // A run that exceeds the budget keeps whatever finished; the rest is
            // counted as not covered (partial), never written as complete.
            withTimeoutOrNull(RUN_BUDGET_MS) {
                coroutineScope {
                    for (tf in ScannerViewModel.PHASE1_TFS) {
                        for (sym in universe) {
                            jobs += async {
                                sem.withPermit {
                                    // Throttled or refused: skip the remaining requests of this run.
                                    if (stopFetching.get()) return@withPermit
                                    val candles = try {
                                        container.repository.futuresHistory(sym, tf, limit = 300)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (t: Throwable) {
                                        if (t.isRegionBlocked()) {
                                            regionBlocked.set(true)
                                            stopFetching.set(true)
                                        } else if (t is UpstreamHttpException && (t.status == 429 || t.status == 418)) {
                                            stopFetching.set(true)
                                        }
                                        null
                                    }
                                    if (candles == null) return@withPermit
                                    synchronized(lock) { okFetches += 1 }
                                    if (candles.size < MIN_CANDLES) return@withPermit
                                    val indicatorOuts = container.indicators.map { it.calculate(candles) }
                                    val consensus = container.consensus.evaluate(indicatorOuts)
                                    val weight = ScannerViewModel.TIME_WEIGHTS[tf] ?: 50
                                    val finalScore =
                                        (consensus.confidence.toDouble() * consensus.confidence * weight) / 100.0
                                    synchronized(lock) {
                                        out += SymbolPhase1Result(
                                            symbol = sym,
                                            tf = tf,
                                            signal = consensus.finalSignal,
                                            confidence = consensus.confidence,
                                            price = candles.last().close,
                                            finalScore = finalScore,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    jobs.awaitAll()
                }
            }

            // A regional block will not change on retry: stop without retrying.
            if (regionBlocked.get()) return Result.failure()
            val okCount = synchronized(lock) { okFetches }
            // Every fetch failed (network drop, throttling): no verdicts, no widget write.
            if (okCount == 0) return retryOrFail()
            val results = synchronized(lock) { out.toList() }
            // Parity with the foreground scanner: NEUTRAL symbols are not calls, so they never reach
            // the widget, the alert hook or the evidence archive (ScannerViewModel drops them too).
            val best = directionalVerdicts(results)
            // Nothing evaluable: keep the previous widget state instead of overwriting it.
            if (best.isEmpty()) return retryOrFail()

            val now = com.diveintocrypto.android.platform.nowMillis()

            // ── ALERT HOOK: survivor head only (bounded; a failure never breaks the run) ──
            // Cancellation is rethrown: a worker stop (or a clear-data stop) must end the run here,
            // not continue into the archive and widget writes.
            try {
                val head = best.take(ScannerViewModel.ALERT_SCAN_LIMIT)
                val verdicts = head.associate { r ->
                    r.symbol to AlertVerdict(
                        signal = r.signal.name,
                        confidence = r.confidence,
                        price = r.price,
                    )
                }
                val oiSpike = fetchOiSpikes(container, head.map { it.symbol }.toSet())
                container.alertEngine.onScanResults(verdicts, oiSpike)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logError(TAG, "alert hook failed", t)
            }

            // ── EVIDENCE ARCHIVE: survivor head (failure-tolerant) ───────────
            currentCoroutineContext().ensureActive()
            // A partial run (budget hit, throttled or failed fetches) is stamped "attempted", so the
            // grader can tell it apart from a complete run.
            val coverage = if (okCount >= planned) {
                com.diveintocrypto.android.domain.evidence.VerdictRecord.COVERAGE_OK
            } else {
                com.diveintocrypto.android.domain.evidence.VerdictRecord.COVERAGE_ATTEMPTED
            }
            runCatching {
                container.evidenceStore.appendAll(
                    best.take(ScannerViewModel.EVIDENCE_APPEND_LIMIT).map { r ->
                        com.diveintocrypto.android.domain.evidence.VerdictRecord(
                            ts = now,
                            symbol = r.symbol,
                            verdict = r.signal.name,
                            confidence = r.confidence,
                            // Background run: no 12-TF agreement data → honest N/A.
                            risk = "N/A",
                            price = r.price,
                            dominantDir = when (r.signal) {
                                Signal.BUY, Signal.STRONG_BUY -> 1
                                Signal.SELL, Signal.STRONG_SELL -> -1
                                else -> 0
                            },
                            coverage = coverage,
                        )
                    },
                )
            }

            // ── WIDGET STATE + piggyback update (failure-tolerant) ───────────
            currentCoroutineContext().ensureActive()
            try {
                WidgetScanState.save(
                    container.settingsStore,
                    WidgetScanState(
                        scannedAtMs = now,
                        items = best.take(3).map { r ->
                            WidgetVerdictRow(
                                symbol = r.symbol,
                                verdict = r.signal.name,
                                confidence = r.confidence,
                            )
                        },
                        okFetches = okCount,
                        plannedFetches = planned,
                    ),
                )
                TopVerdictsWidget().updateAll(app)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logError(TAG, "widget update failed", t)
            }

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Network/parse hiccup → bounded retries, then honest failure.
            logError(TAG, "background scan run failed", t)
            retryOrFail()
        } finally {
            // A fresh per-run container owns its own coroutine scopes (alert
            // ticker loop, portfolio tick collection) — release them. The
            // Application-scoped container is a process singleton: kept alive.
            if (appScoped == null) {
                runCatching { container.alertEngine.close() }
                runCatching { container.portfolioStore.close() }
            }
        }
    }

    /**
     * OI spike percent for the symbols that have an enabled OI_SPIKE_PCT rule,
     * mirroring the foreground scanner. Symbols without a rule are not fetched, and
     * a failed or slow fetch simply leaves that symbol out (alerts stay silent for it).
     */
    private suspend fun fetchOiSpikes(container: AppContainer, symbols: Set<String>): Map<String, Double> {
        val oiRules = container.alertEngine.rules.value.filter {
            it.enabled && it.kind == AlertKind.OI_SPIKE_PCT
        }
        if (oiRules.isEmpty()) return emptyMap()
        val wanted = oiRules.map { it.symbol }.toSet().intersect(symbols)
        if (wanted.isEmpty()) return emptyMap()
        val gate = Semaphore(WORKER_PARALLELISM)
        return withTimeoutOrNull(OI_BUDGET_MS) {
            coroutineScope {
                wanted.map { sym ->
                    async {
                        gate.withPermit {
                            try {
                                val oi = container.repository.openInterestHist(sym, "1h", limit = 30)
                                AlertEvaluator.oiSpikePct(oi.map { it.sumOpenInterestValue })?.let { sym to it }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull().toMap()
            }
        } ?: emptyMap()
    }

    private fun retryOrFail(): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()

    companion object {
        private const val TAG = "DiveScanWorker"

        /** In-flight kline fetches from one worker run. */
        const val WORKER_PARALLELISM = 8

        /** Minimum candles for one consensus evaluation (matches the scanner). */
        const val MIN_CANDLES = 50

        /** Bounded retry budget per run. */
        const val MAX_ATTEMPTS = 3

        /** Wall-clock budget for one run's kline phase, below WorkManager's ~10-minute stop threshold. */
        const val RUN_BUDGET_MS = 7L * 60_000L

        /** Budget for the optional OI fetches of the alert hook. */
        const val OI_BUDGET_MS = 60_000L

        /**
         * The rows the background run may publish: directional calls only. NEUTRAL symbols
         * (exact buy/sell tie) feed no widget row, alert evaluation or evidence record, as in
         * the foreground scanner. [aggregateBySymbol] itself keeps them, for ranking tests.
         */
        internal fun directionalVerdicts(results: List<SymbolPhase1Result>): List<SymbolVerdict> =
            aggregateBySymbol(results).filter { it.signal != Signal.NEUTRAL }

        /**
         * Scanner-parity aggregation over the phase-1 timeframes:
         *   - direction = the side with the larger summed finalScore; an exact tie has no
         *     winning side and is NEUTRAL, as in the foreground cross-rank (scanner tie -> 0);
         *   - confidence = the highest confidence across the timeframes;
         *   - price = the close of the highest-confidence timeframe.
         * A symbol whose timeframes are all NEUTRAL stays NEUTRAL, so a background run
         * never invents a directional verdict. Rows are ranked by net score.
         *
         * Output is deterministic for any input order: the parallel fetches append rows in
         * completion order, so the input is first put into scanner order (timeframe rank in
         * [ScannerViewModel.PHASE1_TFS], then symbol). An equal-confidence tie therefore picks the
         * earlier timeframe, as the foreground strict `>` does, and equal ranks keep a fixed order.
         */
        internal fun aggregateBySymbol(results: List<SymbolPhase1Result>): List<SymbolVerdict> =
            results
                .sortedWith(
                    compareBy<SymbolPhase1Result> { ScannerViewModel.PHASE1_TFS.indexOf(it.tf) }
                        .thenBy { it.symbol },
                )
                .groupBy { it.symbol }
                .map { (symbol, rows) ->
                    var buyNss = 0.0
                    var sellNss = 0.0
                    for (r in rows) {
                        when (r.signal) {
                            Signal.STRONG_BUY, Signal.BUY -> buyNss += r.finalScore
                            Signal.STRONG_SELL, Signal.SELL -> sellNss += r.finalScore
                            Signal.NEUTRAL -> Unit
                        }
                    }
                    val bestConf = rows.maxBy { it.confidence }
                    val signal = when {
                        // Exact tie (includes all-NEUTRAL 0 vs 0) has no winning side.
                        buyNss == sellNss -> Signal.NEUTRAL
                        buyNss > sellNss -> Signal.BUY
                        else -> Signal.SELL
                    }
                    SymbolVerdict(
                        symbol = symbol,
                        signal = signal,
                        confidence = bestConf.confidence,
                        price = bestConf.price,
                        netScore = kotlin.math.abs(buyNss - sellNss),
                        bestScore = rows.maxOf { it.finalScore },
                    )
                }
                .sortedWith(
                    compareByDescending<SymbolVerdict> { it.netScore }
                        .thenByDescending { it.bestScore }
                        .thenBy { it.symbol },
                )
    }
}
