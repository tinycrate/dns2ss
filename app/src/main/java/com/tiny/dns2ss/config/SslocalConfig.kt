package com.tiny.dns2ss.config

data class SslocalConfig(
    val ssUri: SsUri,
    val mode: VpnMode,
    val tunnelPort: Int,
    val socksPort: Int?,
    val dnsHost: String,
    val dnsPort: Int,
    val logLevel: Int = 1,
)

object SslocalConfigBuilder {
    fun build(config: SslocalConfig): String {
        require(config.tunnelPort in 1..65535) { "Invalid tunnel port" }
        require(config.dnsPort in 1..65535) { "Invalid DNS target port" }
        if (config.mode == VpnMode.FULL) {
            require(config.socksPort != null && config.socksPort in 1..65535) {
                "Full mode requires a local SOCKS port"
            }
        }

        val locals = mutableListOf<String>()
        locals += """
            {
              "protocol": "tunnel",
              "local_address": "127.0.0.1",
              "local_port": ${config.tunnelPort},
              "forward_address": ${jsonString(config.dnsHost)},
              "forward_port": ${config.dnsPort},
              "mode": "tcp_and_udp"
            }
        """.trimIndent()

        if (config.mode == VpnMode.FULL) {
            locals += """
                {
                  "protocol": "socks",
                  "local_address": "127.0.0.1",
                  "local_port": ${config.socksPort},
                  "mode": "tcp_and_udp"
                }
            """.trimIndent()
        }

        return """
            {
              "server": ${jsonString(config.ssUri.host)},
              "server_port": ${config.ssUri.port},
              "method": ${jsonString(config.ssUri.method)},
              "password": ${jsonString(config.ssUri.password)},
              "locals": [
                ${locals.joinToString(",\n    ")}
              ],
              "udp_timeout": 60,
              "udp_max_associations": 512,
              "log": { "level": ${config.logLevel} }
            }
        """.trimIndent()
    }

    private fun jsonString(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
                }
            }
        }
        out.append('"')
        return out.toString()
    }
}
