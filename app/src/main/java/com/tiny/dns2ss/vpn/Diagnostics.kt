package com.tiny.dns2ss.vpn

import com.tiny.dns2ss.config.VpnMode

data class AppDiagnostics(
    val mode: VpnMode = VpnMode.default,
    val state: VpnRunState = VpnRunState.STOPPED,
    val routedDnsServers: List<String> = emptyList(),
    val cacheEntries: Int = 0,
    val cacheHits: Long = 0,
    val cacheMisses: Long = 0,
    val dnsQueries: Long = 0,
    val dnsResponses: Long = 0,
    val tunnelBytes: Long = 0,
    val directBytes: Long = 0,
    val fullBytes: Long = 0,
    val errors: Long = 0,
    val pendingDns: Int = 0,
    val uptimeMs: Long = 0,
)

object DiagnosticsProvider {
    @Volatile
    var provider: (() -> AppDiagnostics)? = null

    fun snapshot(): AppDiagnostics =
        runCatching { provider?.invoke() }.getOrNull() ?: AppDiagnostics()
}
