package com.auroratv.jellyfin

import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A small bounded cache: at most [capacity] entries (the oldest goes first), each
 * kept for [ttl]. Thread-safe by copy-on-write (tiny maps, read far more than written).
 */
class TimedCache<K, V>(private val capacity: Int, private val ttl: Duration) {
    private class Entry<V>(val value: V, val at: TimeSource.Monotonic.ValueTimeMark)
    @kotlin.concurrent.Volatile private var map: Map<K, Entry<V>> = emptyMap()

    fun get(key: K): V? {
        val e = map[key] ?: return null
        if (e.at.elapsedNow() > ttl) { map = map - key; return null }
        return e.value
    }

    fun put(key: K, value: V) {
        val next = LinkedHashMap(map)
        next.remove(key)
        next[key] = Entry(value, TimeSource.Monotonic.markNow())
        while (next.size > capacity) next.remove(next.keys.first())
        map = next
    }

    fun clear() { map = emptyMap() }

    val size: Int get() = map.size
}
