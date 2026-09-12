package com.diveintocrypto.android.domain.portfolio

import com.diveintocrypto.android.data.SettingsStore
import com.diveintocrypto.android.domain.evidence.EvidenceStore
import com.diveintocrypto.android.domain.evidence.VerdictRecord
import com.diveintocrypto.android.engine.LiveTickerEngine
import com.diveintocrypto.android.platform.nowMillis
import com.diveintocrypto.android.platform.randomId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One local portfolio entry. LOCAL-ONLY by design: nothing here is ever
 * networked — entries persist through [SettingsStore.putRaw] under ONE key
 * ([PortfolioStore.KEY]) and P&L is computed against whatever mark price the
 * live ticker engine actually has (null when it has none — honest).
 */
@Serializable
data class PortfolioEntry(
    val id: String,
    val symbol: String,
    val entryPrice: Double,
    /** Position size in base-asset units (e.g. BTC). */
    val size: Double,
    /** [DIRECTION_LONG] or [DIRECTION_SHORT]. */
    val direction: String,
    val createdTs: Long,
) {
    companion object {
        const val DIRECTION_LONG = "LONG"
        const val DIRECTION_SHORT = "SHORT"

        /** PURE: normalized signed direction (+1 long / −1 short / 0 unknown). */
        fun directionSign(direction: String): Int = when (direction.uppercase()) {
            DIRECTION_LONG -> 1
            DIRECTION_SHORT -> -1
            else -> 0
        }
    }
}

/**
 * Direction-adjusted P&L for one entry against a mark price.
 * Every derived field is `null` when the mark is unknown (no fabricated zeros).
 *
 * @param unrealizedPct direction-adjusted return vs entry, percent (signed)
 * @param unrealizedPnl (mark − entry)·size·dir, in quote currency (USD)
 * @param engineAgreement "AGREE"/"AGAINST"/"UNKNOWN" — the entry direction vs the
 *        last archived engine verdict for the symbol ([VerdictProvider])
 */
data class PositionPnl(
    val entry: PortfolioEntry,
    val markPrice: Double?,
    val unrealizedPct: Double?,
    val unrealizedPnl: Double?,
    val engineAgreement: String?,
)

/**
 * Injectable "what did the engine last say about this symbol?" source. The
 * default implementation reads the [EvidenceStore] archive; a live verdict
 * provider can be injected instead (tests use fakes).
 */
interface VerdictProvider {
    /** +1 long / −1 short / 0 neutral; null = no archived verdict for the symbol. */
    fun lastVerdictDirection(symbol: String): Int?
}

/**
 * Reads the newest archived [VerdictRecord] per symbol from the evidence ring.
 *
 * The archive holds up to [com.diveintocrypto.android.domain.evidence.EvidenceStore.RING_CAP]
 * JSON lines, and [PortfolioStore.recomputePnl] asks for the newest verdict
 * direction on EVERY ticker tick PER ENTRY — re-reading the whole JSONL each
 * time would re-parse thousands of lines per second. So the per-symbol newest
 * direction map is CACHED and recomputed at most once per archive invalidation
 * ([EvidenceStore.version] bumps on append) with a 30s wall-clock floor as a
 * safety net for out-of-band store writes.
 */
class EvidenceStoreVerdictProvider(
    private val store: EvidenceStore,
    private val clock: () -> Long = ::nowMillis,
) : VerdictProvider {

    // Cache bookkeeping — plain @Volatile fields: worst case under a race two
    // threads recompute the same map (idempotent, cheap relative to re-parsing).
    @Volatile private var cachedVersion: Long = -1L
    @Volatile private var cachedComputedAtMs: Long = 0L
    @Volatile private var newestDirBySymbol: Map<String, Int> = emptyMap()

    override fun lastVerdictDirection(symbol: String): Int? {
        val version = store.version
        val stale = version != cachedVersion ||
            clock() - cachedComputedAtMs >= REFRESH_FLOOR_MS
        if (stale) {
            newestDirBySymbol = store.readAll()
                .groupBy { it.symbol }
                .mapValues { (_, records) -> records.maxByOrNull { it.ts }?.dominantDir ?: 0 }
            cachedVersion = version
            cachedComputedAtMs = clock()
        }
        return newestDirBySymbol[symbol.uppercase()]
    }

    companion object {
        /** Minimum wall-clock interval between two recomputations (safety floor). */
        const val REFRESH_FLOOR_MS: Long = 30_000L
    }
}

/**
 * PURE portfolio math (no storage, no clock, no network).
 */
object PortfolioMath {

    /** Direction-adjusted percent return; null for non-positive prices. */
    fun unrealizedPct(entry: PortfolioEntry, mark: Double): Double? {
        val dir = PortfolioEntry.directionSign(entry.direction)
        if (dir == 0 || entry.entryPrice <= 0.0 || mark <= 0.0) return null
        return (mark - entry.entryPrice) / entry.entryPrice * 100.0 * dir
    }

    /** Quote-currency P&L: (mark − entry)·size·dir; null for non-positive prices. */
    fun unrealizedPnl(entry: PortfolioEntry, mark: Double): Double? {
        val dir = PortfolioEntry.directionSign(entry.direction)
        if (dir == 0 || entry.entryPrice <= 0.0 || mark <= 0.0) return null
        return (mark - entry.entryPrice) * entry.size * dir
    }

    /**
     * Engine-agreement tag: LONG entry + last verdict dir > 0 → AGREE, < 0 →
     * AGAINST; SHORT mirrored; null verdict direction (or unknown entry
     * direction) → "UNKNOWN".
     */
    fun agreementTag(entry: PortfolioEntry, lastVerdictDir: Int?): String? {
        val dir = PortfolioEntry.directionSign(entry.direction)
        if (dir == 0) return null
        val v = lastVerdictDir ?: return "UNKNOWN"
        return if (v == 0) "UNKNOWN" else if (v == dir) "AGREE" else "AGAINST"
    }
}

/**
 * Local-only portfolio tracker store.
 *
 *   - [entries] persisted as one JSON blob ([KEY]) through [SettingsStore.putRaw]
 *   - [pnl] recomputed on every live-ticker emission (when [tickerSource] is
 *     supplied) AND after every add/remove/update — always against the last
 *     known REAL mark prices
 *
 * NEVER networked: the only external inputs are the injected ticker flow and
 * the injected [VerdictProvider].
 */
class PortfolioStore(
    private val settingsStore: SettingsStore,
    private val clock: () -> Long = ::nowMillis,
    private val verdictProvider: VerdictProvider? = null,
    private val tickerSource: StateFlow<Map<String, LiveTickerEngine.LiveTicker>>? = null,
) {

    companion object {
        /** Persistence key (spec: portfolio_v1). */
        const val KEY = "portfolio_v1"

        /** Ring cap on stored entries (bounds the blob). */
        const val MAX_ENTRIES = 100

        const val AGREE = "AGREE"
        const val AGAINST = "AGAINST"
        const val UNKNOWN = "UNKNOWN"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Last known real mark prices (symbol → price); fed by the ticker flow. */
    @Volatile private var lastMarks: Map<String, Double> = emptyMap()

    private val _entries = MutableStateFlow<List<PortfolioEntry>>(loadEntries())
    val entries: StateFlow<List<PortfolioEntry>> = _entries.asStateFlow()

    private val _pnl = MutableStateFlow<List<PositionPnl>>(emptyList())
    val pnl: StateFlow<List<PositionPnl>> = _pnl.asStateFlow()

    init {
        recomputePnl()
        tickerSource?.let { src ->
            scope.launch {
                src.collect { tickers ->
                    lastMarks = tickers.mapValues { it.value.price }
                    recomputePnl()
                }
            }
        }
    }

    /**
     * Releases the ticker-collection scope. Only for THROWAWAY stores (e.g. a
     * per-run worker container) — the Application-scoped store lives for the
     * process lifetime and is never closed.
     */
    fun close() {
        scope.cancel()
    }

    /** Adds one entry (symbol uppercased; entry goes to the head of the list). */
    fun add(
        symbol: String,
        entryPrice: Double,
        size: Double,
        direction: String,
    ): PortfolioEntry {
        val entry = PortfolioEntry(
            id = randomId(),
            symbol = symbol.uppercase(),
            entryPrice = entryPrice,
            size = size,
            direction = direction.uppercase(),
            createdTs = clock(),
        )
        synchronized(lock) {
            _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
            persistEntries(_entries.value)
        }
        recomputePnl()
        return entry
    }

    fun remove(id: String) {
        synchronized(lock) {
            _entries.value = _entries.value.filter { it.id != id }
            persistEntries(_entries.value)
        }
        recomputePnl()
    }

    /** Partial update — only non-null fields change; identity/createdTs are immutable. */
    fun update(id: String, entryPrice: Double? = null, size: Double? = null, direction: String? = null) {
        synchronized(lock) {
            _entries.value = _entries.value.map {
                if (it.id != id) it else it.copy(
                    entryPrice = entryPrice ?: it.entryPrice,
                    size = size ?: it.size,
                    direction = (direction ?: it.direction).uppercase(),
                )
            }
            persistEntries(_entries.value)
        }
        recomputePnl()
    }

    /**
     * Recomputes [pnl] from the CURRENT entries and the last known mark prices.
     * Public so hosts without a ticker flow (tests, widgets) can drive it.
     */
    fun recomputePnl(markPrices: Map<String, Double>? = null) {
        if (markPrices != null) lastMarks = markPrices
        val marks = lastMarks
        val provider = verdictProvider
        _pnl.value = _entries.value.map { entry ->
            val mark = marks[entry.symbol]
            PositionPnl(
                entry = entry,
                markPrice = mark,
                unrealizedPct = mark?.let { PortfolioMath.unrealizedPct(entry, it) },
                unrealizedPnl = mark?.let { PortfolioMath.unrealizedPnl(entry, it) },
                engineAgreement = provider?.let { PortfolioMath.agreementTag(entry, it.lastVerdictDirection(entry.symbol)) },
            )
        }
    }

    private fun persistEntries(entries: List<PortfolioEntry>) {
        runCatching {
            settingsStore.putRaw(KEY, json.encodeToString(ListSerializer(PortfolioEntry.serializer()), entries))
        }
    }

    private fun loadEntries(): List<PortfolioEntry> = runCatching {
        val raw = settingsStore.getRaw(KEY) ?: return emptyList()
        if (raw.isBlank()) emptyList()
        else json.decodeFromString(ListSerializer(PortfolioEntry.serializer()), raw)
    }.getOrDefault(emptyList())
}
