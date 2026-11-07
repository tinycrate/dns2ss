package com.tiny.dns2ss.vpn

import android.content.Context
import com.tiny.dns2ss.config.LogVerbosity
import com.tiny.dns2ss.config.SsUri
import com.tiny.dns2ss.config.SsUriParser
import com.tiny.dns2ss.config.VpnMode

data class AppConfig(
    val ssUri: String,
    val ss: SsUri,
    val mode: VpnMode,
    val dnsHost: String,
    val dnsPort: Int,
    val logVerbosity: LogVerbosity,
)

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)

    var ssUri: String
        get() = prefs.getString(KEY_SS_URI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SS_URI, value).apply()

    var dnsHost: String
        get() = prefs.getString(KEY_DNS_HOST, DEFAULT_DNS_HOST) ?: DEFAULT_DNS_HOST
        set(value) = prefs.edit().putString(KEY_DNS_HOST, value).apply()

    var dnsPort: Int
        get() = prefs.getInt(KEY_DNS_PORT, DEFAULT_DNS_PORT)
        set(value) = prefs.edit().putInt(KEY_DNS_PORT, value).apply()

    var mode: VpnMode
        get() = VpnMode.fromStorage(prefs.getString(KEY_MODE, VpnMode.default.name))
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var logVerbosity: LogVerbosity
        get() = LogVerbosity.fromStorage(prefs.getString(KEY_LOG_VERBOSITY, LogVerbosity.default.name))
        set(value) = prefs.edit().putString(KEY_LOG_VERBOSITY, value.name).apply()

    fun loadValidated(): AppConfig {
        val uri = SsUriParser.parse(ssUri)
        val host = dnsHost.trim()
        require(host.isNotEmpty()) { "DNS target host is empty" }
        require(host.none { it.isWhitespace() }) { "DNS target host contains whitespace" }
        require(dnsPort in 1..65535) { "DNS target port is invalid" }
        return AppConfig(
            ssUri = ssUri.trim(),
            ss = uri,
            mode = mode,
            dnsHost = host,
            dnsPort = dnsPort,
            logVerbosity = logVerbosity,
        )
    }

    fun save(ssUri: String, mode: VpnMode, dnsHost: String, dnsPort: Int) {
        prefs.edit()
            .putString(KEY_SS_URI, ssUri.trim())
            .putString(KEY_MODE, mode.name)
            .putString(KEY_DNS_HOST, dnsHost.trim())
            .putInt(KEY_DNS_PORT, dnsPort)
            .apply()
    }

    companion object {
        private const val KEY_SS_URI = "ss_uri"
        private const val KEY_DNS_HOST = "dns_host"
        private const val KEY_DNS_PORT = "dns_port"
        private const val KEY_MODE = "vpn_mode"
        private const val KEY_LOG_VERBOSITY = "log_verbosity"
        const val DEFAULT_DNS_HOST = "127.0.0.1"
        const val DEFAULT_DNS_PORT = 53530
    }
}
