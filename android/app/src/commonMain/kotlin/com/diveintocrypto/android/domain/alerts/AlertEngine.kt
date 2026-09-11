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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Local alert engine — evaluates rules cheaply on two hooks:
 *
 *   1. LIVE TICKER TICKS → PRICE_ABOVE / PRICE_BELOW (driven by the all-market
 *      mini-ticker StateFlow; evaluated per ~1s batch, coalesced).
 *   2. SCAN-CYCLE END ([onScanResults]) → VERDICT / CONFIDENCE_ABOVE, plus
 *      OI_SPIKE_PCT when the caller supplies the (30s-cached) OI series.
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
     * PRICE_* rules are evaluated on every emission after [startTickerObservation].
     */
    private val tickerSource: StateFlow<Map<String, LiveTickerEngine.LiveTicker>>? = null,
) {

    companion object {
        /** Fired-history ring size (spec: last 100). */
        const val HISTORY_CAP = 100

        const val KEY_RULES = "alert_rules_v1"
        const val KEY_HISTORY = "alert_history_v1"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = SynchronizedObject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _rules = MutableStateFlow<List<AlertRule>>(loadRules())
    val rules: StateFlow<List<AlertRule>> = _rules.asStateFlow()

    private val _firedHistory = MutableStateFlow<List<FiredAlert>>(loadHistory())
    val firedHistory: StateFlow<List<FiredAlert>> = _firedHistory.asStateFlow()

    private val _banner = MutableStateFlow<FiredAlert?>(null)
    val banner: StateFlow<FiredAlert?> = _banner.asStateFlow()

    init {
        // Restored PRICE_* rules need the live loop armed again.
        if (_rules.value.any {
                it.enabled && (it.kind == AlertKind.PRICE_ABOVE || it.kind == AlertKind.PRICE_BELOW)
            }
        ) {
            startTickerObservation()
        }
    }

    fun dismissBanner() { _banner.value = null }

    fun addRule(
        symbol: String,
        kind: AlertKind,
        direction: String = AlertRule.DIRECTION_ANY,
        threshold: Double = 0.0,
        oneShot: Boolean = false,
    ): AlertRule {
        val rule = AlertRule(
            id = randomId(),
            symbol = symbol.uppercase(),
            kind = kind,
            direction = direction,
            threshold = threshold,
            oneShot = oneShot,
            enabled = true,
            createdTs = nowMillis(),
        )
        mutexFreeAdd(rule)
        if (kind == AlertKind.PRICE_ABOVE || kind == AlertKind.PRICE_BELOW) startTickerObservation()
        return rule
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
     * only computed for symbols that have an OI rule, so the extra REST calls
     * stay bounded).
     */
    suspend fun onScanResults(
        verdicts: Map<String, AlertVerdict>,
        oiSpikePct: Map<String, Double> = emptyMap(),
    ) {
        val current = _rules.value
        if (current.none { it.enabled }) return
        val outcome = AlertEvaluator.evaluate(
            rules = current,
            inputs = AlertInputs(verdicts = verdicts, oiSpikePct = oiSpikePct),
            nowMs = nowMillis(),
        )
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

    @Volatile private var tickerObserving = false

    /**
     * Starts (once) the live-ticker observation loop that evaluates PRICE_*
     * rules on every mini-ticker map emission. Cheap when no price rules exist.
     */
    fun startTickerObservation() {
        if (tickerObserving) return
        val source = tickerSource ?: return
        tickerObserving = true
        scope.launch {
            source.collect { tickers ->
                val current = _rules.value
                if (current.none { it.enabled && (it.kind == AlertKind.PRICE_ABOVE || it.kind == AlertKind.PRICE_BELOW) }) {
                    return@collect
                }
                val prices = tickers.mapValues { it.value.price }
                val outcome = AlertEvaluator.evaluate(
                    rules = current,
                    inputs = AlertInputs(prices = prices),
                    nowMs = nowMillis(),
                )
                applyOutcome(outcome)
            }
        }
    }

    private fun applyOutcome(outcome: AlertEvaluator.Outcome) {
        if (outcome.fired.isEmpty()) return
        synchronized(lock) {
            _rules.value = outcome.rules
            persistRules(outcome.rules)
            // Newest-first ring.
            _firedHistory.value = (outcome.fired.asReversed() + _firedHistory.value).take(HISTORY_CAP)
            persistHistory(_firedHistory.value)
        }
        val latest = outcome.fired.last()
        _banner.value = latest
        // The Android notifier no-ops safely when the permission is absent.
        outcome.fired.forEach { notifier.notify(it) }
    }

    private fun persistRules(rules: List<AlertRule>) {
        runCatching {
            settingsStore.putRaw(KEY_RULES, json.encodeToString(ListSerializer(AlertRule.serializer()), rules))
        }
    }

    private fun loadRules(): List<AlertRule> = runCatching {
        val raw = settingsStore.getRaw(KEY_RULES) ?: return emptyList()
        if (raw.isBlank()) emptyList()
        else json.decodeFromString(ListSerializer(AlertRule.serializer()), raw)
    }.getOrDefault(emptyList())

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
