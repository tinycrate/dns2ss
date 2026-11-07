package com.tiny.dns2ss.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsCacheTest {
    @Test
    fun hitRewritesIdCaseAndTtl() {
        val query = makeQuery(id = 0x1234, name = "example.com")
        val response = makeResponse(id = 0x1234, ttl = 60)
        val cache = DnsCache()
        cache.store(query, response, response.size, nowMs = 1_000)

        val second = makeQuery(id = 0xABCD, name = "EXAMPLE.COM")
        val hit = cache.lookup(second, second.size, nowMs = 11_000)
        assertNotNull(hit)
        hit!!
        assertEquals(0xAB, hit[0].toInt() and 0xff)
        assertEquals(0xCD, hit[1].toInt() and 0xff)
        assertEquals('E'.code, hit[13].toInt() and 0xff)
        assertEquals(50, ttl(hit, 35))
    }

    @Test
    fun expiresAfterMinTtl() {
        val query = makeQuery(id = 1, name = "example.com")
        val response = makeResponse(id = 1, ttl = 5)
        val cache = DnsCache()
        cache.store(query, response, response.size, nowMs = 0)
        val later = makeQuery(id = 2, name = "example.com")
        assertNull(cache.lookup(later, later.size, nowMs = 5_001))
    }

    @Test
    fun ednsPaddingDoesNotDefeatCache() {
        val first = makePaddedQuery(id = 11, name = "example.com", padding = 16)
        val second = makePaddedQuery(id = 12, name = "EXAMPLE.COM", padding = 64)
        val response = makeResponse(id = 11, ttl = 60)
        val cache = DnsCache()
        assertTrue(cache.store(first, response, response.size, nowMs = 1_000))
        val hit = cache.lookup(second, second.size, nowMs = 1_999)
        assertNotNull(hit)
        assertEquals(60, ttl(hit!!, 35))
    }

    @Test
    fun varyingEdnsCookiesShareCache() {
        val first = makeCookieQuery(21, "example.com", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val second = makeCookieQuery(22, "EXAMPLE.COM", byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1))
        val response = makeCookieResponse(21, ttl = 60)
        val cache = DnsCache()
        assertTrue(cache.store(first, response, response.size, nowMs = 1_000))
        val hit = cache.lookup(second, second.size, nowMs = 1_500)
        assertNotNull(hit)
    }

    @Test
    fun cachedResponseDropsCookieOption() {
        val response = makeCookieResponse(31, ttl = 60)
        val stripped = DnsWire.stripOptOption(response, response.size, DnsWire.OPT_COOKIE)
        assertEquals(response.size - 12, stripped.size)
    }

    @Test
    fun negativeAnswerUsesSoaTtl() {
        val query = makeQuery(id = 7, name = "example.com")
        val response = makeNegativeResponse(id = 7, ttl = 120)
        val cache = DnsCache()
        assertTrue(cache.store(query, response, response.size, nowMs = 1_000))

        val later = makeQuery(id = 8, name = "EXAMPLE.COM")
        val hit = cache.lookup(later, later.size, nowMs = 11_000)
        assertNotNull(hit)
        hit!!
        assertEquals(3, hit[3].toInt() and 0x0f)
        assertEquals(110, ttl(hit, 35))
        assertNull(cache.lookup(later, later.size, nowMs = 121_000))
    }

    private fun makeQuery(id: Int, name: String): ByteArray {
        val out = ArrayList<Byte>()
        out.add((id ushr 8).toByte()); out.add(id.toByte())
        out.add(0x01); out.add(0x00)
        out.add(0x00); out.add(0x01)
        out.add(0); out.add(0); out.add(0); out.add(0); out.add(0); out.add(0)
        for (label in name.split('.')) {
            out.add(label.length.toByte())
            label.forEach { out.add(it.code.toByte()) }
        }
        out.add(0)
        out.add(0x00); out.add(0x01)
        out.add(0x00); out.add(0x01)
        return out.toByteArray()
    }

    private fun makePaddedQuery(id: Int, name: String, padding: Int): ByteArray {
        val header = makeQuery(id = id, name = name).copyOf()
        header[10] = 0; header[11] = 1 // ARCOUNT = 1
        val out = ArrayList<Byte>()
        header.forEach { out.add(it) }
        out.add(0) // root name
        out.add(0x00); out.add(41) // OPT
        out.add(0x10); out.add(0x00) // UDP payload size 4096
        out.add(0); out.add(0); out.add(0); out.add(0) // no DO, version 0
        val rdLength = 4 + padding
        out.add(((rdLength ushr 8) and 0xff).toByte())
        out.add((rdLength and 0xff).toByte())
        out.add(0x00); out.add(12) // EDNS PADDING option
        out.add(((padding ushr 8) and 0xff).toByte())
        out.add((padding and 0xff).toByte())
        repeat(padding) { out.add(0) }
        return out.toByteArray()
    }

    private fun makeCookieQuery(id: Int, name: String, cookie: ByteArray): ByteArray {
        val base = makeQuery(id = id, name = name)
        val header = base.copyOf()
        header[10] = 0; header[11] = 1 // ARCOUNT
        val out = ArrayList<Byte>()
        header.forEach { out.add(it) }
        out.add(0) // OPT owner root
        out.add(0x00); out.add(41)
        out.add(0x04); out.add(0xD0.toByte()) // UDP payload size 1232
        out.add(0); out.add(0); out.add(0); out.add(0)
        val rdLength = 4 + cookie.size
        out.add(((rdLength ushr 8) and 0xff).toByte())
        out.add((rdLength and 0xff).toByte())
        out.add(0x00); out.add(10) // COOKIE
        out.add(((cookie.size ushr 8) and 0xff).toByte())
        out.add((cookie.size and 0xff).toByte())
        cookie.forEach { out.add(it) }
        return out.toByteArray()
    }

    private fun makeCookieResponse(id: Int, ttl: Int): ByteArray {
        val base = makeResponse(id = id, ttl = ttl)
        val header = base.copyOf()
        header[10] = 0; header[11] = 1 // ARCOUNT
        val out = ArrayList<Byte>()
        header.forEach { out.add(it) }
        out.add(0) // OPT owner root
        out.add(0x00); out.add(41)
        out.add(0x04); out.add(0xD0.toByte())
        out.add(0); out.add(0); out.add(0); out.add(0)
        out.add(0); out.add(12) // rdlength 12
        out.add(0x00); out.add(10) // COOKIE
        out.add(0); out.add(8)
        repeat(8) { out.add(0x55) }
        return out.toByteArray()
    }

    private fun makeNegativeResponse(id: Int, ttl: Int): ByteArray {
        val out = ArrayList<Byte>()
        out.add((id ushr 8).toByte()); out.add(id.toByte())
        out.add(0x81.toByte()); out.add(0x83.toByte()) // QR, RD, RA, NXDOMAIN
        out.add(0); out.add(1)
        out.add(0); out.add(0)
        out.add(0); out.add(1)
        out.add(0); out.add(0)
        for (label in "example.com".split('.')) {
            out.add(label.length.toByte())
            label.forEach { out.add(it.code.toByte()) }
        }
        out.add(0)
        out.add(0x00); out.add(0x01)
        out.add(0x00); out.add(0x01)
        // Authority SOA, pointer to question name.
        out.add(0xC0.toByte()); out.add(0x0C)
        out.add(0x00); out.add(0x06)
        out.add(0x00); out.add(0x01)
        out.add(((ttl ushr 24) and 0xff).toByte())
        out.add(((ttl ushr 16) and 0xff).toByte())
        out.add(((ttl ushr 8) and 0xff).toByte())
        out.add((ttl and 0xff).toByte())
        out.add(0); out.add(20)
        repeat(20) { out.add(0) }
        return out.toByteArray()
    }

    private fun makeResponse(id: Int, ttl: Int): ByteArray {
        val out = ArrayList<Byte>()
        out.add((id ushr 8).toByte()); out.add(id.toByte())
        out.add(0x81.toByte()); out.add(0x80.toByte())
        out.add(0); out.add(1)
        out.add(0); out.add(1)
        out.add(0); out.add(0)
        out.add(0); out.add(0)
        for (label in "example.com".split('.')) {
            out.add(label.length.toByte())
            label.forEach { out.add(it.code.toByte()) }
        }
        out.add(0)
        out.add(0x00); out.add(0x01)
        out.add(0x00); out.add(0x01)
        // Answer: pointer to question name, A, IN, TTL, 4-byte address.
        out.add(0xC0.toByte()); out.add(0x0C)
        out.add(0x00); out.add(0x01)
        out.add(0x00); out.add(0x01)
        out.add(((ttl ushr 24) and 0xff).toByte())
        out.add(((ttl ushr 16) and 0xff).toByte())
        out.add(((ttl ushr 8) and 0xff).toByte())
        out.add((ttl and 0xff).toByte())
        out.add(0); out.add(4)
        out.add(1); out.add(2); out.add(3); out.add(4)
        return out.toByteArray()
    }

    private fun ttl(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
}
