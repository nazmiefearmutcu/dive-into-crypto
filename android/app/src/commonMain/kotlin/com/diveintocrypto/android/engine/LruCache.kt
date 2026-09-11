package com.diveintocrypto.android.engine

/**
 * Minimal bounded LRU (least-recently-used) cache.
 *
 * NOT thread-safe by design — the owner ([MarketDataEngine]) already guards every
 * access with its `candleCacheLock`. Recency is refreshed on both [get] and [put];
 * when [maxSize] is exceeded the least recently used entry is evicted.
 *
 * Backstory: the candle cache used to be a plain `MutableMap` inside an
 * app-scoped singleton, so it grew without bound (~500 universe symbols × 12 TF
 * keys × up to 1000 candles). It is now capped at
 * [MarketDataEngine.CANDLE_CACHE_MAX_KEYS] keys.
 */
internal class LruCache<K : Any, V : Any>(private val maxSize: Int) {

    init {
        require(maxSize > 0) { "maxSize must be positive, was $maxSize" }
    }

    // LinkedHashMap iterates in insertion order; recency is implemented by
    // remove+reinsert on access, so the first key is always the LRU entry.
    private val map = LinkedHashMap<K, V>()

    fun get(key: K): V? {
        val value = map.remove(key) ?: return null
        map[key] = value // mark as most recently used
        return value
    }

    fun put(key: K, value: V) {
        map.remove(key)
        map[key] = value
        while (map.size > maxSize) {
            val eldest = map.keys.firstOrNull() ?: break
            map.remove(eldest)
        }
    }

    val size: Int get() = map.size

    fun clear() = map.clear()

    /** Keys ordered least-recently-used first (exposed for tests/diagnostics). */
    fun keysLruFirst(): List<K> = map.keys.toList()
}
