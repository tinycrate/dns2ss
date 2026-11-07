package com.tiny.dns2ss.config

import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderTest {
    private val ss = SsUri("vpn.example.com", 8388, "aes-256-gcm", "secret")

    @Test
    fun buildsTunnelOnlyConfigForFastAndStrict() {
        for (mode in listOf(VpnMode.FAST, VpnMode.STRICT)) {
            val json = SslocalConfigBuilder.build(
                SslocalConfig(ss, mode, 21053, null, "127.0.0.1", 53530),
            )
            assertTrue(json.contains("\"protocol\": \"tunnel\""))
            assertTrue(json.contains("\"forward_port\": 53530"))
            assertTrue(json.contains("\"mode\": \"tcp_and_udp\""))
            assertTrue(!json.contains("\"protocol\": \"socks\""))
            assertTrue(json.contains("\"method\": \"aes-256-gcm\""))
        }
    }

    @Test
    fun buildsTunnelAndSocksForFull() {
        val json = SslocalConfigBuilder.build(
            SslocalConfig(ss, VpnMode.FULL, 21053, 21054, "127.0.0.1", 53530),
        )
        assertTrue(json.contains("\"protocol\": \"tunnel\""))
        assertTrue(json.contains("\"protocol\": \"socks\""))
        assertTrue(json.contains("\"local_port\": 21054"))
    }

    @Test
    fun escapesJsonValues() {
        val odd = SsUri("vpn.example.com", 8388, "aes-256-gcm", "quote\"back\\slash")
        val json = SslocalConfigBuilder.build(
            SslocalConfig(odd, VpnMode.FAST, 21053, null, "127.0.0.1", 53530),
        )
        assertTrue(json.contains("quote\\\"back\\\\slash"))
    }

    @Test
    fun buildsHevConfig() {
        val yaml = HevConfigBuilder.build(HevConfig(21055, "user", "pass"))
        assertTrue(yaml.contains("port: 21055"))
        assertTrue(yaml.contains("username: 'user'"))
        assertTrue(yaml.contains("password: 'pass'"))
        assertTrue(yaml.contains("mtu: 1500"))
    }
}
