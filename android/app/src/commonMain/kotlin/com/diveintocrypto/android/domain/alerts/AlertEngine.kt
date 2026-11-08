package com.diveintocrypto.android.domain.alerts

import com.diveintocrypto.android.data.SettingsStore
import com.diveintocrypto.android.engine.LiveTickerEngine
import com.diveintocrypto.android.platform.nowMillis
import com.diveintocrypto.android.platform.randomId
import com.diveintocrypto.android.platform.synchronized
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Local alert engine — evaluates rules cheaply on two hooks:
 *
 *   1. LIVE TICKER TICKS → PRICE_ABOVE / PRICE_BELOW conditions (driven by the
 *      all-market mini-ticker StateFlow; evaluated per ~1s batch, coalesced).
 *   2. SCAN-CYCLE END ([onScanResults]) → VERDICT / CONFIDENCE_ABOVE, plus
 *      OI_SPIKE_PCT when the caller supplies the (30s-cached) OI series.
 *
 * AND rules may mix both kinds (e.g. PRICE_ABOVE + VERDICT LONG). Each hook
 * therefore evaluates against the freshest value it has for every condition:
 * prices are remembered from the ticker stream (fresh for [PRICE_TTL_MS]), and
 * verdict / OI values are remembered from the last scan cycle (fresh for
 * [SCAN_TTL_MS]). A ticker tick evaluates only the rules that carry a price
 * condition, so a pure VERDICT/OI rule is never re-fired by a stale snapshot
 * between scans.
 *
 * v2 (0.3.0): a rule carries a LIST of AND-ed [AlertCondition]s plus a
 * per-rule [AlertRule.coalesceMs] re-fire window. v1 single-condition rules are
 * MIGRATED on load: each becomes a v2 rule with exactly one condition
 * (kind/direction/threshold preserved) and is persisted under the versioned
 * key [KEY_RULES_V2] (the v1 blob stays on disk as a read-only fallback).
 *
 * When a rule fires:
 *   (a) the event is prepended to the fired-history ring (last [HISTORY_CAP],
 *       newest first) — in-memory AND persisted;
 *   (b) the platform [AlertNotifier] fires a local notification (must no-op
 *       safely when the OS notification permission is absent);
 *   (c) [banner] exposes the latest fire for an in-app banner (clear via
 *       [dismissBanner]).
 *
 * Rules + history persist as JSON blobs through [SettingsStore.putRaw] —
 * deliberately no Room.
 */
class AlertEngine(
    private val settingsStore: SettingsStore,
    private val notifier: AlertNotifier,
    /**
     * Live last-price map (from [LiveTickerEngine]). Optional so pure tests can
     * construct the engine without a running ticker stream; when supplied,
     * PRICE_* conditions are evaluated on every emission after
     * [startTickerObservation].
     */
    private val tickerSource: StateFlow<Map<String, LiveTickerEngine.LiveTicker>>? = null,
    /**
     * Message formatter provider, read on every evaluation so a language switch
     * applies to alerts fired afterwards. Defaults to English (tests, workers).
     */
    private val messages: () -> AlertMessages = { EnglishAlertMessages },
) {

    companion object {
        /** Fired-history ring size (spec: last 100). */
        const val HISTORY_CAP = 100

        /** v2 rules blob (current). */
        const val KEY_RULES_V2 = "alert_rules_v2"

        /** v1 rules blob — READ-ONLY fallback for migration. */
        const val KEY_RULES = "alert_rules_v1"

        const val KEY_HISTORY = "alert_history_v1"

        /** A remembered live price older than this no longer satisfies a mixed rule. */
        const val PRICE_TTL_MS: Long = 60_000L

        /** A remembered scan-cycle verdict / OI value older than this no longer satisfies a mixed rule. */
        const val SCAN_TTL_MS: Long = 10 * 60_000L

        /** PURE migration: a v1 rule becomes a v2 rule with one identical condition. */
        fun migrateV1(v1: AlertRule): AlertRule = if (v1.conditions.isNotEmpty()) v1 else v1.copy(
            conditions = listOf(AlertCondition(v1.kind, v1.direction, v1.threshold)),
            coalesceMs = if (v1.coalesceMs > 0) v1.coalesceMs else AlertRule.COALESCE_DEFAULT_MS,
        )
    }

    /** A value together with the wall-clock time it was observed. */
    private class Stamped<T>(val value: T, val ts: Long)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = SynchronizedObject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Latest observed values per symbol. Guarded by [lock].
    private val priceSnap = HashMap<String, Stamped<Double>>()
    private val verdictSnap = HashMap<String, Stamped<AlertVerdict>>()
    private val oiSnap = HashMap<String, Stamped<Double>>()

    private val _rules = MutableStateFlow<List<AlertRule>>(loadRules())
    val rules: StateFlow<List<AlertRule>> = _rules.asStateFlow()

    private val _firedHistory = MutableStateFlow<List<FiredAlert>>(loadHistory())
    val firedHistory: StateFlow<List<FiredAlert>> = _firedHistory.asStateFlow()

    private val _banner = MutableStateFlow<FiredAlert?>(null)
    val banner: StateFlow<FiredAlert?> = _banner.asStateFlow()

    /**
     * True once a ticker collector is running. Must be declared BEFORE the init
     * block: property initializers run in textual order, so a declaration placed
     * below init would reset the flag after init armed the collector, letting a
     * second collector start.
     */
    @Volatile private var tickerObserving = false

    /** Test hook: whether the ticker observation loop has been armed. */
    internal val isTickerObserving: Boolean get() = tickerObserving

    init {
        // Restored rules with a PRICE_* condition need the live loop armed again.
        if (_rules.value.any { it.enabled && hasPriceCondition(it) }) {
            startTickerObservation()
        }
    }

    /**
     * Releases the engine's coroutine scope (ticker-observation loop included).
     * Only for THROWAWAY engines (e.g. a per-run worker container) — the
     * Application-scoped engine lives for the process lifetime and is never
     * closed. The engine is unusable afterwards.
     */
    fun close() {
        scope.cancel()
    }

    fun dismissBanner() { _banner.value = null }

    /** v1-shaped add (single condition). Kept working — the UI + tests use it. */
    fun addRule(
        symbol: String,
        kind: AlertKind,
        direction: String = AlertRule.DIRECTION_ANY,
        threshold: Double = 0.0,
        oneShot: Boolean = false,
    ): AlertRule = addRule(
        symbol = symbol,
        conditions = listOf(AlertCondition(kind, direction, threshold)),
        oneShot = oneShot,
    )

    /** v2 add: a rule from an AND-ed condition group (kind/direction/threshold mirror the first). */
    fun addRule(
        symbol: String,
        conditions: List<AlertCondition>,
        oneShot: Boolean = false,
        coalesceMs: Long = AlertRule.COALESCE_DEFAULT_MS,
    ): AlertRule {
        val primary = conditions.first()
        val rule = AlertRule(
            id = randomId(),
            symbol = symbol.uppercase(),
            kind = primary.kind,
            direction = primary.direction,
            threshold = primary.threshold,
            oneShot = oneShot,
            enabled = true,
            createdTs = nowMillis(),
            conditions = conditions,
            coalesceMs = coalesceMs,
        )
        mutexFreeAdd(rule)
        if (hasPriceCondition(rule)) startTickerObservation()
        return rule
    }

    /** Sets the per-rule re-fire coalescing window (ms). 0 = engine default. */
    fun setRuleCoalesceMs(id: String, coalesceMs: Long) {
        synchronized(lock) {
            _rules.value = _rules.value.map {
                if (it.id == id) it.copy(coalesceMs = coalesceMs) else it
            }
            persistRules(_rules.value)
        }
    }

    private fun hasPriceCondition(rule: AlertRule): Boolean =
        rule.effectiveConditions.any {
            it.kind == AlertKind.PRICE_ABOVE || it.kind == AlertKind.PRICE_BELOW
        }

    private fun mutexFreeAdd(rule: AlertRule) {
        synchronized(lock) {
            _rules.value = _rules.value + rule
            persistRules(_rules.value)
        }
    }

    fun removeRule(id: String) {
        synchronized(lock) {
            _rules.value = _rules.value.filter { it.id != id }
            persistRules(_rules.value)
        }
    }

    /** Flips enabled (or sets it explicitly when [enabled] is non-null). */
    fun toggleRule(id: String, enabled: Boolean? = null) {
        synchronized(lock) {
            _rules.value = _rules.value.map {
                if (it.id == id) it.copy(enabled = enabled ?: !it.enabled) else it
            }
            persistRules(_rules.value)
        }
    }

    /**
     * SCAN-CYCLE HOOK. [verdicts] = symbol-keyed verdict snapshots (usually the
     * survivor table head). [oiSpikePct] = symbol → OI spike percent (optional —
     * only computed for symbols that have an OI condition, so the extra REST
     * calls stay bounded). The verdict/OI inputs of this pass are exactly the
     * ones supplied; live prices come from the ticker memory.
     */
    suspend fun onScanResults(
        verdicts: Map<String, AlertVerdict>,
        oiSpikePct: Map<String, Double> = emptyMap(),
        nowMs: Long = nowMillis(),
    ) {
        synchronized(lock) {
            for ((sym, v) in verdicts) verdictSnap[sym] = Stamped(v, nowMs)
            for ((sym, v) in oiSpikePct) oiSnap[sym] = Stamped(v, nowMs)
        }
        val current = _rules.value
        if (current.none { it.enabled }) return
        val outcome = synchronized(lock) {
            AlertEvaluator.evaluate(
                rules = current,
                inputs = AlertInputs(
                    prices = freshPrices(nowMs),
                    verdicts = verdicts,
                    oiSpikePct = oiSpikePct,
                ),
                nowMs = nowMs,
                messages = messages(),
            )
        }
        applyOutcome(outcome)
    }

    /**
     * Non-suspending variant used by the scanner hot path (fire-and-forget on
     * the engine scope — evaluation is cheap and order is protected by the mutex).
     */
    fun onScanResultsAsync(
        verdicts: Map<String, AlertVerdict>,
        oiSpikePct: Map<String, Double> = emptyMap(),
    ) {
        scope.launch { onScanResults(verdicts, oiSpikePct) }
    }

    /**
     * Starts (once) the live-ticker observation loop that evaluates PRICE_*
     * conditions on every mini-ticker map emission. Cheap when no price rules exist.
     */
    fun startTickerObservation() {
        if (tickerObserving) return
        val source = tickerSource ?: return
        tickerObserving = true
        scope.launch {
            source.collect { tickers -> onTickerBatch(tickers) }
        }
    }

    /**
     * TICKER HOOK: one mini-ticker batch. Remembers the prices, then evaluates
     * only the enabled rules that carry a PRICE condition, against fresh prices
     * and the last scan-cycle verdict / OI values.
     */
    internal fun onTickerBatch(
        tickers: Map<String, LiveTickerEngine.LiveTicker>,
        nowMs: Long = nowMillis(),
    ) {
        synchronized(lock) {
            for ((sym, t) in tickers) priceSnap[sym] = Stamped(t.price, nowMs)
        }
        val priceRules = _rules.value.filter { it.enabled && hasPriceCondition(it) }
        if (priceRules.isEmpty()) return
        val outcome = synchronized(lock) {
            AlertEvaluator.evaluate(
                rules = priceRules,
                inputs = AlertInputs(
                    prices = freshPrices(nowMs),
                    verdicts = freshVerdicts(nowMs),
                    oiSpikePct = freshOi(nowMs),
                ),
                nowMs = nowMs,
                messages = messages(),
            )
        }
        applyOutcome(outcome)
    }

    // Freshness filters. Callers must hold [lock].
    private fun freshPrices(nowMs: Long): Map<String, Double> =
        priceSnap.filterValues { nowMs - it.ts <= PRICE_TTL_MS }.mapValues { it.value.value }

    private fun freshVerdicts(nowMs: Long): Map<String, AlertVerdict> =
        verdictSnap.filterValues { nowMs - it.ts <= SCAN_TTL_MS }.mapValues { it.value.value }

    private fun freshOi(nowMs: Long): Map<String, Double> =
        oiSnap.filterValues { nowMs - it.ts <= SCAN_TTL_MS }.mapValues { it.value.value }

    /**
     * Persists an evaluation outcome. The outcome was computed from a rule
     * snapshot taken BEFORE the lock, so every fire is re-checked against the
     * CURRENT rule state under the lock:
     *   - a rule the user disabled meanwhile does not fire;
     *   - a rule already fired inside its coalescing window (two evaluations raced
     *     over the same data) does not fire twice;
     *   - a rule removed meanwhile keeps its fire in the history (the fire
     *     genuinely happened) but is never re-added.
     * Accepted fires adopt only lastFiredTs, and enabled is only ever cleared
     * (one-shot), never re-set from the stale snapshot.
     */
    internal fun applyOutcome(outcome: AlertEvaluator.Outcome) {
        if (outcome.fired.isEmpty()) return
        val accepted = synchronized(lock) {
            val currentById = _rules.value.associateBy { it.id }
            val ok = outcome.fired.filter { f ->
                val cur = currentById[f.ruleId] ?: return@filter true
                if (!cur.enabled) return@filter false
                val window = if (cur.coalesceMs > 0) cur.coalesceMs else AlertEvaluator.COALESCE_MS
                val last = cur.lastFiredTs
                last == null || f.firedTs - last >= window
            }
            if (ok.isEmpty()) return
            val acceptedIds = ok.map { it.ruleId }.toSet()
            val updatedById = outcome.rules.associateBy { it.id }
            _rules.value = _rules.value.map { current ->
                if (current.id !in acceptedIds) current
                else updatedById[current.id]?.let { updated ->
                    current.copy(lastFiredTs = updated.lastFiredTs, enabled = current.enabled && updated.enabled)
                } ?: current
            }
            persistRules(_rules.value)
            // Newest-first ring.
            _firedHistory.value = (ok.asReversed() + _firedHistory.value).take(HISTORY_CAP)
            persistHistory(_firedHistory.value)
            ok
        }
        val latest = accepted.last()
        _banner.value = latest
        // The Android notifier no-ops safely when the permission is absent.
        accepted.forEach { notifier.notify(it) }
    }

    private fun persistRules(rules: List<AlertRule>) {
        runCatching {
            settingsStore.putRaw(KEY_RULES_V2, json.encodeToString(ListSerializer(AlertRule.serializer()), rules))
        }
    }

    /**
     * Load order: the v2 blob first. Once the v2 key EXISTS it is authoritative,
     * even when it holds an empty list (every rule deleted) — falling back to the
     * v1 blob there would resurrect deleted rules. Only an absent or undecodable
     * v2 value falls back to the v1 blob, which is MIGRATED (each v1 rule → one-
     * condition v2, immediately persisted to v2). The v1 blob is never deleted.
     */
    private fun loadRules(): List<AlertRule> {
        val v2Raw = runCatching { settingsStore.getRaw(KEY_RULES_V2) }.getOrNull()
        if (v2Raw != null) {
            val decoded = runCatching {
                json.decodeFromString(ListSerializer(AlertRule.serializer()), v2Raw)
            }.getOrNull()
            if (decoded != null) return decoded
        }
        val v1Raw = runCatching { settingsStore.getRaw(KEY_RULES) }.getOrNull()
        if (v1Raw.isNullOrBlank()) return emptyList()
        val v1 = runCatching {
            json.decodeFromString(ListSerializer(AlertRule.serializer()), v1Raw)
        }.getOrDefault(emptyList())
        if (v1.isEmpty()) return emptyList()
        val migrated = v1.map { migrateV1(it) }
        // Write the migrated blob forward so the next load skips the fallback.
        runCatching { persistRules(migrated) }
        return migrated
    }

    private fun persistHistory(history: List<FiredAlert>) {
        runCatching {
            settingsStore.putRaw(KEY_HISTORY, json.encodeToString(ListSerializer(FiredAlert.serializer()), history))
        }
    }

    private fun loadHistory(): List<FiredAlert> = runCatching {
        val raw = settingsStore.getRaw(KEY_HISTORY) ?: return emptyList()
        if (raw.isBlank()) emptyList()
        else json.decodeFromString(ListSerializer(FiredAlert.serializer()), raw)
    }.getOrDefault(emptyList())
}
