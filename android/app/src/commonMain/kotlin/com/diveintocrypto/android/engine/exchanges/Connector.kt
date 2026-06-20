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
