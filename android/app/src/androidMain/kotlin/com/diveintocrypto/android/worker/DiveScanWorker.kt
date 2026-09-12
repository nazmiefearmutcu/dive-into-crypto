package com.diveintocrypto.android.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.glance.appwidget.updateAll
import com.diveintocrypto.android.DiveIntoCryptoApplication
import com.diveintocrypto.android.data.ScanUniverseMode
import com.diveintocrypto.android.data.createAppContainer
import com.diveintocrypto.android.domain.model.Signal
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Periodic BACKGROUND scan (opt-in — see [BackgroundScanScheduler] and the
 * `background_scans_enabled` setting, default FALSE).
 *
 * What it does (BOUNDED, phase-1 coarse only — never the full 12-TF scan):
 *   1. Universe = volume-sorted futures symbols capped by the stored scan-universe
 *      setting ([ScanUniverseMode]).
 *   2. Phase-1 timeframes only ([ScannerViewModel.PHASE1_TFS] = 1d/12h/8h).
 *   3. Aggregates per-symbol best phase-1 scores, feeds the survivor HEAD to:
 *        - the alert engine's scan hook (VERDICT / CONFIDENCE / OI rules),
 *        - the evidence archive (VerdictRecord append),
 *        - the Glance widget ([TopVerdictsWidget], top-3 + data age).
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

            // ── Phase-1 coarse scan (1d / 12h / 8h, capped parallelism) ──────
            val sem = Semaphore(WORKER_PARALLELISM)
            val lock = SynchronizedObject()
            val results: List<SymbolPhase1Result> = coroutineScope {
                val jobs = mutableListOf<Deferred<Unit>>()
                val out = mutableListOf<SymbolPhase1Result>()
                for (tf in ScannerViewModel.PHASE1_TFS) {
                    for (sym in universe) {
                        jobs += async {
                            sem.withPermit {
                                val candles = try {
                                    container.repository.futuresHistory(sym, tf, limit = 300)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Throwable) {
                                    emptyList()
                                }
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
                out
            }

            // ── Per-symbol best result (highest finalScore across phase-1 TFs) ──
            val best = results
                .groupBy { it.symbol }
                .map { (_, rows) -> rows.maxBy { it.finalScore } }
                .sortedByDescending { it.finalScore }

            val now = com.diveintocrypto.android.platform.nowMillis()

            // ── ALERT HOOK: survivor head only (bounded; never breaks the run) ──
            runCatching {
                val head = best.take(ScannerViewModel.ALERT_SCAN_LIMIT)
                val verdicts = head.associate { r ->
                    r.symbol to com.diveintocrypto.android.domain.alerts.AlertVerdict(
                        signal = r.signal.name,
                        confidence = r.confidence,
                        price = r.price,
                    )
                }
                container.alertEngine.onScanResults(verdicts)
            }

            // ── EVIDENCE ARCHIVE: survivor head (failure-tolerant) ───────────
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
                        )
                    },
                )
            }

            // ── WIDGET STATE + piggyback update (failure-tolerant) ───────────
            runCatching {
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
                    ),
                )
                TopVerdictsWidget().updateAll(app)
            }

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Network/parse hiccup → bounded retries, then honest failure.
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

    private fun retryOrFail(): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()

    companion object {
        /** In-flight kline fetches from one worker run. */
        const val WORKER_PARALLELISM = 8

        /** Minimum candles for one consensus evaluation (matches the scanner). */
        const val MIN_CANDLES = 50

        /** Bounded retry budget per run. */
        const val MAX_ATTEMPTS = 3
    }
}
