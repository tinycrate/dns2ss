package com.tiny.dns2ss.dns

import com.tiny.dns2ss.socks.RouterStats
import com.tiny.dns2ss.socks.Socks5Address
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A single persistent UDP socket into the sslocal DNS tunnel, with DNS transaction-ID
 * multiplexing. One SS UDP association now serves all clients and all queries.
 */
class DnsTunnel(
    host: String,
    port: Int,
    private val cache: DnsCache,
    private val stats: RouterStats,
    private val logger: (String) -> Unit,
) : Closeable {
    private data class Pending(
        val originalId: Int,
        val client: InetSocketAddress,
        val target: Socks5Address,
        val cacheKey: String?,
        val query: ByteArray,
        val createdAtMs: Long,
        val responder: (ByteArray, Socks5Address, InetSocketAddress) -> Unit,
    )

    private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply {
        connect(InetSocketAddress(host, port))
    }
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val pendingLock = Any()
    private val nextId = AtomicInteger((System.nanoTime() and 0xffff).toInt())
    private val running = AtomicBoolean(true)
    private val receiveThread = Thread({ receiveLoop() }, "dns-tunnel-recv").apply {
        isDaemon = true
        start()
    }
    private var queryCount = 0

    fun pendingCount(): Int = pending.size

    fun query(
        payload: ByteArray,
        client: InetSocketAddress,
        target: Socks5Address,
        responder: (ByteArray, Socks5Address, InetSocketAddress) -> Unit,
    ) {
        if (!running.get()) return
        stats.dnsQueries.incrementAndGet()
        val now = System.currentTimeMillis()
        val cached = cache.lookup(payload, payload.size, now)
        if (cached != null) {
            stats.cacheHits.incrementAndGet()
            stats.dnsResponses.incrementAndGet()
            responder(cached, target, client)
            return
        }
        stats.cacheMisses.incrementAndGet()
        stats.tunnelBytes.addAndGet(payload.size.toLong())
        if (payload.size < 2) return
        val originalId = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
        val cacheKey = DnsWire.cacheKey(payload, payload.size)
        cleanupPending(now)
        val query = payload.copyOf()
        val internalId = reserveId(
            Pending(originalId, client, target, cacheKey, payload.copyOf(), now, responder),
        )
        if (internalId < 0) {
            stats.errors.incrementAndGet()
            return
        }
        query[0] = ((internalId ushr 8) and 0xff).toByte()
        query[1] = (internalId and 0xff).toByte()
        try {
            socket.send(DatagramPacket(query, query.size))
        } catch (t: Throwable) {
            pending.remove(internalId)
            stats.errors.incrementAndGet()
            logger("DNS tunnel send failed: ${t.message}")
        }
    }

    private fun reserveId(item: Pending): Int {
        synchronized(pendingLock) {
            repeat(256) {
                val id = nextId.getAndIncrement() and 0xffff
                if (pending.putIfAbsent(id, item) == null) return id
            }
        }
        return -1
    }

    private fun receiveLoop() {
        val buffer = ByteArray(65535)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val length = packet.length
                if (length < 12) continue
                val internalId = ((buffer[0].toInt() and 0xff) shl 8) or (buffer[1].toInt() and 0xff)
                val item = pending.remove(internalId) ?: continue
                val response = buffer.copyOf(length)
                response[0] = ((item.originalId ushr 8) and 0xff).toByte()
                response[1] = (item.originalId and 0xff).toByte()
                val key = item.cacheKey
                if (key != null && length <= 4096) {
                    runCatching { cache.store(item.query, response, length) }
                }
                stats.dnsResponses.incrementAndGet()
                item.responder(response, item.target, item.client)
            } catch (t: Throwable) {
                if (!running.get()) break
                stats.errors.incrementAndGet()
                logger("DNS tunnel receive failed: ${t.message}")
            }
        }
    }

    private fun cleanupPending(now: Long) {
        queryCount++
        if (queryCount % 64 != 0 && pending.size < 4096) return
        val cutoff = now - 5000
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.createdAtMs < cutoff) iterator.remove()
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket.close() }
        receiveThread.interrupt()
        pending.clear()
    }
}
