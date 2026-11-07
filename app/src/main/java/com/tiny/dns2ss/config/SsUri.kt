package com.tiny.dns2ss.config

import java.util.Base64

data class SsUri(
    val host: String,
    val port: Int,
    val method: String,
    val password: String,
    val tag: String? = null,
) {
    val serverAddress: String
        get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    override fun toString(): String =
        "SsUri(host=$host, port=$port, method=$method, password=<redacted>, tag=$tag)"
}

object SsUriParser {
    private val supportedMethods = setOf(
        "aes-128-gcm",
        "aes-256-gcm",
        "chacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm",
        "2022-blake3-aes-256-gcm",
        "2022-blake3-chacha20-poly1305",
        "2022-blake3-chacha8-poly1305",
    )

    val methods: Set<String> get() = supportedMethods

    fun parse(input: String): SsUri {
        val raw = input.trim()
        require(raw.startsWith("ss://", ignoreCase = true)) { "Shadowsocks URI must start with ss://" }
        var body = raw.substring(5)

        val fragment = body.substringAfter('#', "")
        body = body.substringBefore('#')

        val query = body.substringAfter('?', "")
        body = body.substringBefore('?')
        if (query.isNotEmpty()) {
            for (part in query.split('&')) {
                val key = part.substringBefore('=')
                val value = part.substringAfter('=', "")
                if (key.equals("plugin", ignoreCase = true) && value.isNotBlank()) {
                    throw IllegalArgumentException("SIP003 Shadowsocks plugins are not supported")
                }
            }
        }

        // Some SIP002 links use a trailing path such as /?plugin=...
        body = body.trim()
        if (body.endsWith("/")) body = body.dropLast(1)

        val tag = fragment.takeIf { it.isNotBlank() }?.let(::percentDecode)

        if (!body.contains('@')) {
            val decoded = decodeBase64(body)
            require(decoded.contains('@')) { "Invalid legacy Shadowsocks URI" }
            return parseAuthority(decoded, tag)
        }

        val at = body.lastIndexOf('@')
        val userInfo = body.substring(0, at)
        var authority = body.substring(at + 1)
        // A path after the authority is not meaningful for us.
        val pathIndex = authority.indexOf('/')
        if (pathIndex >= 0) authority = authority.substring(0, pathIndex)

        val credentials = decodeUserInfo(userInfo)
        return parseAuthority("$credentials@$authority", tag)
    }

    private fun parseAuthority(decoded: String, tag: String?): SsUri {
        val at = decoded.lastIndexOf('@')
        require(at > 0) { "Missing credentials in Shadowsocks URI" }
        val credentials = decoded.substring(0, at)
        val authority = decoded.substring(at + 1)

        val colon = credentials.indexOf(':')
        require(colon > 0) { "Missing Shadowsocks method or password" }
        val method = credentials.substring(0, colon).trim().lowercase()
        val password = credentials.substring(colon + 1)
        require(password.isNotEmpty()) { "Shadowsocks password is empty" }
        require(method in supportedMethods) {
            "Unsupported Shadowsocks method '$method'. Supported: ${supportedMethods.joinToString()}"
        }

        val (host, port) = splitHostPort(authority)
        require(host.isNotEmpty()) { "Shadowsocks server host is empty" }
        require(host.none { it.isWhitespace() }) { "Shadowsocks server host contains whitespace" }

        return SsUri(host = host, port = port, method = method, password = password, tag = tag)
    }

    private fun splitHostPort(authority: String): Pair<String, Int> {
        val host: String
        val portText: String
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            require(close > 1) { "Invalid IPv6 authority in Shadowsocks URI" }
            host = authority.substring(1, close)
            require(authority.length > close + 1 && authority[close + 1] == ':') {
                "Missing port in Shadowsocks URI"
            }
            portText = authority.substring(close + 2)
        } else {
            val colon = authority.lastIndexOf(':')
            require(colon > 0) { "Missing port in Shadowsocks URI" }
            host = authority.substring(0, colon)
            portText = authority.substring(colon + 1)
        }
        val port = portText.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid Shadowsocks port '$portText'")
        require(port in 1..65535) { "Shadowsocks port out of range" }
        return host to port
    }

    private fun decodeUserInfo(userInfo: String): String {
        if (userInfo.isEmpty()) throw IllegalArgumentException("Missing Shadowsocks credentials")
        val decoded = runCatching { decodeBase64OrNull(userInfo) }.getOrNull()
        if (decoded != null && decoded.contains(':')) return decoded
        val plain = percentDecode(userInfo)
        require(plain.contains(':')) { "Invalid Shadowsocks credentials" }
        return plain
    }

    private fun decodeBase64(input: String): String =
        decodeBase64OrNull(input)?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Invalid base64 in Shadowsocks URI")

    private fun decodeBase64OrNull(input: String): String? {
        val cleaned = input.trim()
        if (cleaned.isEmpty()) return null
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        val urlSafe = padded.replace('-', '+').replace('_', '/')
        val bytes = runCatching { Base64.getDecoder().decode(urlSafe) }
            .recoverCatching { Base64.getUrlDecoder().decode(padded) }
            .getOrNull() ?: return null
        return bytes.toString(Charsets.UTF_8)
    }

    private fun percentDecode(input: String): String {
        if (!input.contains('%')) return input
        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '%' && i + 2 < input.length) {
                val hex = input.substring(i + 1, i + 3)
                val value = hex.toIntOrNull(16)
                if (value != null) {
                    out.append(value.toChar())
                    i += 3
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
