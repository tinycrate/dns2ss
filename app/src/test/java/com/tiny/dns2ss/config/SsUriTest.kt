package com.tiny.dns2ss.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class SsUriTest {
    @Test
    fun parsesSip002Base64UserInfo() {
        val userInfo = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-256-gcm:p@ss".toByteArray())
        val uri = SsUriParser.parse("ss://$userInfo@vpn.example.com:8388#home")
        assertEquals("vpn.example.com", uri.host)
        assertEquals(8388, uri.port)
        assertEquals("aes-256-gcm", uri.method)
        assertEquals("p@ss", uri.password)
        assertEquals("home", uri.tag)
    }

    @Test
    fun parsesPlainUserInfo() {
        val uri = SsUriParser.parse("ss://chacha20-ietf-poly1305:secret@1.2.3.4:9000")
        assertEquals("1.2.3.4", uri.host)
        assertEquals("chacha20-ietf-poly1305", uri.method)
        assertEquals("secret", uri.password)
    }

    @Test
    fun parsesLegacyBase64Uri() {
        val decoded = "aes-128-gcm:legacy@[2001:db8::1]:8443"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded.toByteArray())
        val uri = SsUriParser.parse("ss://$encoded")
        assertEquals("2001:db8::1", uri.host)
        assertEquals(8443, uri.port)
        assertEquals("aes-128-gcm", uri.method)
        assertEquals("legacy", uri.password)
    }

    @Test
    fun parsesAead2022() {
        val uri = SsUriParser.parse("ss://2022-blake3-aes-128-gcm:key==@example.com:443")
        assertEquals("2022-blake3-aes-128-gcm", uri.method)
    }

    @Test
    fun rejectsPlugins() {
        assertThrows(IllegalArgumentException::class.java) {
            SsUriParser.parse("ss://aes-256-gcm:x@example.com:8388/?plugin=v2ray-plugin")
        }
    }

    @Test
    fun rejectsUnsupportedMethod() {
        assertThrows(IllegalArgumentException::class.java) {
            SsUriParser.parse("ss://rc4-md5:x@example.com:8388")
        }
    }

    @Test
    fun rejectsMissingPort() {
        assertThrows(IllegalArgumentException::class.java) {
            SsUriParser.parse("ss://aes-256-gcm:x@example.com")
        }
    }
}
