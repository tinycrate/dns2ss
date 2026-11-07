package com.tiny.dns2ss.socks

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.net.InetAddress

class Socks5AddressTest {
    @Test
    fun ipv4UdpRoundTrip() {
        val source = Socks5Address.fromHostPort("203.0.113.53", 53)
        val packet = Socks5Address.buildUdp(source, byteArrayOf(1, 2, 3, 4))
        val parsed = Socks5Address.parseUdp(packet, packet.size)!!
        assertEquals(Socks5Address.ATYP_IPV4, parsed.first.atyp)
        assertEquals("203.0.113.53", parsed.first.host)
        assertEquals(53, parsed.first.port)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), parsed.second)
    }

    @Test
    fun domainAndIpv6RoundTrip() {
        val domain = Socks5Address.domain("example.com", 5353)
        val domainPacket = Socks5Address.buildUdp(domain, byteArrayOf(9))
        val parsedDomain = Socks5Address.parseUdp(domainPacket, domainPacket.size)!!
        assertEquals(Socks5Address.ATYP_DOMAIN, parsedDomain.first.atyp)
        assertEquals("example.com", parsedDomain.first.host)
        assertEquals(5353, parsedDomain.first.port)

        val ipv6 = Socks5Address.fromInetAddress(InetAddress.getByName("2001:db8::1"), 443)
        val ipv6Packet = Socks5Address.buildUdp(ipv6, byteArrayOf(7))
        val parsedIpv6 = Socks5Address.parseUdp(ipv6Packet, ipv6Packet.size)!!
        assertEquals(Socks5Address.ATYP_IPV6, parsedIpv6.first.atyp)
        assertEquals(443, parsedIpv6.first.port)
        assertArrayEquals(byteArrayOf(7), parsedIpv6.second)
    }

    @Test
    fun fragmentedUdpIsRejected() {
        val source = Socks5Address.fromHostPort("203.0.113.53", 53)
        val packet = Socks5Address.buildUdp(source, byteArrayOf(1))
        packet[0] = 0
        packet[1] = 0
        packet[2] = 1 // FRAG
        assertNull(Socks5Address.parseUdp(packet, packet.size))
    }

    @Test
    fun addressReadMatchesWireBytes() {
        val address = Socks5Address.domain("dns.example", 53)
        val bytes = address.toBytes()
        val decoded = Socks5Address.read(DataInputStream(ByteArrayInputStream(bytes)))
        assertEquals(address.atyp, decoded.atyp)
        assertEquals(address.host, decoded.host)
        assertEquals(address.port, decoded.port)
        assertArrayEquals(address.encodedAddress, decoded.encodedAddress)
    }
}
