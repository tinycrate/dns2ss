package com.tiny.dns2ss.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.tiny.dns2ss.config.SsUriParser
import com.tiny.dns2ss.vpn.AppPreferences

data class SettingsUiState(
    val ssUri: String = "",
    val dnsHost: String = AppPreferences.DEFAULT_DNS_HOST,
    val dnsPort: Int = AppPreferences.DEFAULT_DNS_PORT,
    val ipv6Enabled: Boolean = true,
    val autoStartOnBoot: Boolean = false,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = AppPreferences(application)

    fun load(): SettingsUiState = SettingsUiState(
        ssUri = prefs.ssUri,
        dnsHost = prefs.dnsHost,
        dnsPort = prefs.dnsPort,
        ipv6Enabled = prefs.ipv6Enabled,
        autoStartOnBoot = prefs.autoStartOnBoot,
    )

    /** Returns null on success, otherwise a user-facing error. */
    fun save(
        ssUri: String,
        dnsHost: String,
        dnsPortText: String,
        ipv6Enabled: Boolean,
        autoStartOnBoot: Boolean,
    ): String? {
        val host = dnsHost.trim()
        val port = dnsPortText.trim().toIntOrNull() ?: return "DNS port is not a number"
        return try {
            SsUriParser.parse(ssUri)
            require(host.isNotEmpty()) { "DNS target host is empty" }
            require(host.none { it.isWhitespace() }) { "DNS target host contains whitespace" }
            require(port in 1..65535) { "DNS port is out of range" }
            prefs.save(ssUri, prefs.mode, host, port, ipv6Enabled, autoStartOnBoot)
            null
        } catch (t: Throwable) {
            t.message ?: "Invalid configuration"
        }
    }
}
