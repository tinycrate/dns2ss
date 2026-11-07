package com.tiny.dns2ss.dns

import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

class DnsCache(
    private val maxEntries: Int = 512,
) {
    data class Stats(
        val entries: Int,
        val hits: Long,
        val misses: Long,
        val evictions: Long,
    )

    private val cache = object : LinkedHashMap<String, DnsWire.CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DnsWire.CacheEntry>?): Boolean {
            val remove = size > maxEntries
            if (remove) evictions.incrementAndGet()
            return remove
        }
    }

    private val hits = AtomicLong()
    private val misses = AtomicLong()
    private val evictions = AtomicLong()

    fun lookup(query: ByteArray, length: Int, nowMs: Long = System.currentTimeMillis()): ByteArray? {
        val key = DnsWire.cacheKey(query, length)
        if (key == null) {
            misses.incrementAndGet()
            return null
        }
        synchronized(cache) {
            val entry = cache[key]
            if (entry == null) {
                misses.incrementAndGet()
                return null
            }
            if (nowMs - entry.storedAtMs >= entry.ttlSeconds * 1000L) {
                cache.remove(key)
                misses.incrementAndGet()
                return null
            }
            val response = DnsWire.buildCachedResponse(query, entry, nowMs)
            if (response == null) {
                cache.remove(key)
                misses.incrementAndGet()
                return null
            }
            hits.incrementAndGet()
            return response
        }
    }

    fun store(
        query: ByteArray,
        response: ByteArray,
        responseLength: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val key = DnsWire.cacheKey(query, query.size) ?: return false
        val sanitized = DnsWire.stripOptOption(response, responseLength, DnsWire.OPT_COOKIE)
        val entry = DnsWire.analyzeResponse(query, sanitized, sanitized.size, nowMs) ?: return false
        synchronized(cache) {
            cache[key] = entry
        }
        return true
    }

    fun stats(): Stats = synchronized(cache) {
        Stats(cache.size, hits.get(), misses.get(), evictions.get())
    }

    fun clear() {
        synchronized(cache) { cache.clear() }
    }
}
