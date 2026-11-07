package com.tiny.dns2ss.config

data class HevConfig(
    val routerPort: Int,
    val username: String,
    val password: String,
    val mtu: Int = 1500,
    val logLevel: String = "warn",
    val pipeline: Boolean = true,
)

object HevConfigBuilder {
    fun build(config: HevConfig): String {
        require(config.routerPort in 1..65535) { "Invalid router port" }
        require(config.mtu >= 576) { "Invalid MTU" }
        return """
            tunnel:
              mtu: ${config.mtu}
              icmp: 'reply'
            socks5:
              address: '127.0.0.1'
              port: ${config.routerPort}
              udp: 'udp'
              pipeline: ${config.pipeline}
              username: '${yamlQuote(config.username)}'
              password: '${yamlQuote(config.password)}'
            misc:
              log-level: '${yamlQuote(config.logLevel)}'
              tcp-buffer-size: 65536
              udp-recv-buffer-size: 524288
        """.trimIndent() + "\n"
    }

    private fun yamlQuote(value: String): String = value.replace("'", "''")
}
