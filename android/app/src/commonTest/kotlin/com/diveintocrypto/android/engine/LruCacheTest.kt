package com.diveintocrypto.android.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests for the bounded LRU used by MarketDataEngine's candle cache. */
class LruCacheTest {

    @Test
    fun `evicts least recently used when over capacity`() {
        val cache = LruCache<String, Int>(3)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)
        assertEquals(listOf("a", "b", "c"), cache.keysLruFirst())

        cache.put("d", 4) // over capacity → "a" (LRU) evicted
        assertEquals(3, cache.size)
        assertEquals(null, cache.get("a"))
        assertEquals(listOf("b", "c", "d"), cache.keysLruFirst())
    }

    @Test
    fun `get refreshes recency and protects the entry from eviction`() {
        val cache = LruCache<String, Int>(3)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)

        assertEquals(1, cache.get("a")) // "a" is now most recent
        cache.put("d", 4)               // evicts "b", not "a"

        assertEquals(1, cache.get("a"))
        assertEquals(null, cache.get("b"))
        assertEquals(listOf("c", "d", "a"), cache.keysLruFirst())
    }

    @Test
    fun `put same key twice keeps single entry`() {
        val cache = LruCache<String, Int>(2)
        cache.put("a", 1)
        cache.put("a", 9)
        assertEquals(1, cache.size)
        assertEquals(9, cache.get("a"))
    }

    @Test
    fun `size 1 cache keeps only latest key`() {
        val cache = LruCache<Int, String>(1)
        cache.put(1, "one")
        cache.put(2, "two")
        assertEquals(1, cache.size)
        assertEquals(null, cache.get(1))
        assertEquals("two", cache.get(2))
    }

    @Test
    fun `clear empties the cache`() {
        val cache = LruCache<String, Int>(2)
        cache.put("a", 1)
        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(null, cache.get("a"))
    }

    @Test
    fun `maxSize must be positive`() {
        var threw = false
        try {
            LruCache<String, Int>(0)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
