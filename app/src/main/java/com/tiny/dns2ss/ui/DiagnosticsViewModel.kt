package com.tiny.dns2ss.ui

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import java.util.Locale
import com.tiny.dns2ss.config.LogVerbosity
import com.tiny.dns2ss.vpn.AppDiagnostics
import com.tiny.dns2ss.vpn.AppPreferences
import com.tiny.dns2ss.vpn.DiagnosticsProvider
import com.tiny.dns2ss.vpn.VpnLog
import com.tiny.dns2ss.vpn.VpnStatus

data class DiagnosticsUiState(
    val summary: String,
    val logs: String,
    val verbosity: LogVerbosity,
)

class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = AppPreferences(application)

    fun snapshot(): DiagnosticsUiState {
        val diag = DiagnosticsProvider.snapshot()
        val status = VpnStatus.data.value
        val config = runCatching { prefs.loadValidated() }.getOrNull()
        val privateDns = runCatching {
            Settings.Global.getString(getApplication<Application>().contentResolver, "private_dns_mode")
        }.getOrNull() ?: "unknown"
        val routed = if (diag.routedDnsServers.isEmpty()) "none" else diag.routedDnsServers.joinToString(", ")
        val summary = buildString {
            appendLine("State: ${status.state}")
            appendLine("Mode: ${status.mode}")
            appendLine("Log verbosity: ${diag.logVerbosity.label}")
            appendLine("Uptime: ${formatDuration(diag.uptimeMs)}")
            appendLine("DNS target: ${config?.dnsHost ?: prefs.dnsHost}:${config?.dnsPort ?: prefs.dnsPort}")
            appendLine("SS server: ${config?.ss?.serverAddress ?: "-"}")
            appendLine("Routed DNS servers: $routed")
            appendLine("Cache: ${diag.cacheEntries} entries, ${diag.cacheHits} hits, ${diag.cacheMisses} misses")
            appendLine("DNS: ${diag.dnsQueries} queries, ${diag.dnsResponses} responses, ${diag.pendingDns} pending")
            appendLine("Traffic: tunnel ${formatBytes(diag.tunnelBytes)}, direct ${formatBytes(diag.directBytes)}, full ${formatBytes(diag.fullBytes)}")
            appendLine("Errors: ${diag.errors}")
            append("Private DNS: $privateDns")
        }
        return DiagnosticsUiState(summary, VpnLog.snapshot(), prefs.logVerbosity)
    }

    fun currentVerbosity(): LogVerbosity = prefs.logVerbosity

    fun setLogVerbosity(verbosity: LogVerbosity) {
        prefs.logVerbosity = verbosity
    }

    fun clearLogs() = VpnLog.clear()

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "-"
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toDouble()
        var unit = 0
        val units = arrayOf("KB", "MB", "GB", "TB")
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}
