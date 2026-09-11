package com.diveintocrypto.android.domain.evidence

import com.diveintocrypto.android.data.KeyValueStore
import com.diveintocrypto.android.platform.nowMillis
import com.diveintocrypto.android.platform.synchronized
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One archived verdict observation (Android self-grading input).
 *
 * [verdict] is a [com.diveintocrypto.android.domain.model.Signal] name;
 * [dominantDir] is the signed direction (+1 long / −1 short / 0 neutral);
 * [risk] is the scanner's LOW/MEDIUM/HIGH/N/A label.
 */
@Serializable
data class VerdictRecord(
    val ts: Long,
    val symbol: String,
    val verdict: String,
    val confidence: Int,
    val risk: String,
    val price: Double,
    val dominantDir: Int,
)

/**
 * Append-only verdict archive stored as a bounded JSONL blob under ONE
 * [KeyValueStore] key (ring cap [RING_CAP] lines). Deliberately no Room.
 *
 * Failure-tolerant: a corrupt line is skipped on read; any storage error is
 * swallowed (the archive is an audit trail, never allowed to break the scan).
 */
class EvidenceStore(
    private val kv: KeyValueStore,
    private val clock: () -> Long = ::nowMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()

    /** Appends one record (ring-capped). */
    fun append(record: VerdictRecord) {
        synchronized(lock) {
            val lines = readLinesUnlocked()
            writeLinesUnlocked(ringAppend(lines, encodeLine(record), RING_CAP))
        }
    }

    /** Appends a batch in one read-modify-write (used per scan cycle). */
    fun appendAll(records: List<VerdictRecord>) {
        if (records.isEmpty()) return
        synchronized(lock) {
            var lines = readLinesUnlocked()
            for (r in records) lines = ringAppend(lines, encodeLine(r), RING_CAP)
            writeLinesUnlocked(lines)
        }
    }

    /** All records, oldest first. Corrupt lines are skipped. */
    fun readAll(): List<VerdictRecord> = synchronized(lock) {
        readLinesUnlocked().mapNotNull { line ->
            runCatching { json.decodeFromString(VerdictRecord.serializer(), line) }.getOrNull()
        }
    }

    fun lastGradedMs(): Long = kv.getLongCompat(LAST_GRADED_KEY, 0L)

    fun setLastGradedMs(ms: Long) {
        runCatching { kv.putLongCompat(LAST_GRADED_KEY, ms) }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private fun encodeLine(record: VerdictRecord): String =
        json.encodeToString(VerdictRecord.serializer(), record)

    private fun readLinesUnlocked(): List<String> = runCatching {
        val raw = kv.getString(KEY, null) ?: return emptyList()
        if (raw.isBlank()) emptyList() else raw.split('\n').filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun writeLinesUnlocked(lines: List<String>) {
        runCatching { kv.putString(KEY, lines.joinToString("\n")) }
    }

    companion object {
        const val KEY = "evidence_log_v1"
        const val LAST_GRADED_KEY = "evidence_last_graded_ms"

        /** JSONL ring capacity (spec: 2000 lines). */
        const val RING_CAP = 2000

        /** PURE ring append: keep the newest [cap] lines. */
        fun ringAppend(existing: List<String>, newLine: String, cap: Int): List<String> =
            (existing + newLine).takeLast(cap)
    }
}

/** SharedPreferences has no long API on the common surface — store as string. */
private fun KeyValueStore.getLongCompat(key: String, default: Long): Long =
    getString(key, null)?.toLongOrNull() ?: default

private fun KeyValueStore.putLongCompat(key: String, value: Long) {
    putString(key, value.toString())
}
