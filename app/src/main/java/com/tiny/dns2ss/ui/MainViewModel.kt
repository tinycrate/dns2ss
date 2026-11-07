package com.tiny.dns2ss.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tiny.dns2ss.config.VpnMode
import com.tiny.dns2ss.vpn.AppPreferences
import com.tiny.dns2ss.vpn.DnsVpnService
import com.tiny.dns2ss.vpn.VpnRunState
import com.tiny.dns2ss.vpn.VpnStatus
import com.tiny.dns2ss.vpn.VpnStatusData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MainUiState(
    val status: VpnStatusData = VpnStatusData(),
    val mode: VpnMode = VpnMode.default,
    val targetHost: String = AppPreferences.DEFAULT_DNS_HOST,
    val targetPort: Int = AppPreferences.DEFAULT_DNS_PORT,
    val configReady: Boolean = false,
    val configError: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = AppPreferences(application)
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<MainUiState> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            VpnStatus.data.collect { status ->
                mutable.value = mutable.value.copy(status = status)
            }
        }
    }

    fun refresh() {
        mutable.value = load()
    }

    fun setMode(mode: VpnMode) {
        if (prefs.mode == mode) return
        prefs.mode = mode
        mutable.value = mutable.value.copy(mode = mode)
        if (VpnStatus.data.value.state == VpnRunState.RUNNING) {
            DnsVpnService.restart(getApplication())
        }
    }

    fun validateForStart(): String? = try {
        prefs.loadValidated()
        null
    } catch (t: Throwable) {
        t.message ?: "Invalid configuration"
    }

    fun hasValidConfig(): Boolean = validateForStart() == null

    private fun load(): MainUiState {
        val validation = runCatching { prefs.loadValidated() }
        val error = validation.exceptionOrNull()?.message
        return MainUiState(
            status = VpnStatus.data.value,
            mode = prefs.mode,
            targetHost = prefs.dnsHost,
            targetPort = prefs.dnsPort,
            configReady = validation.isSuccess,
            configError = error,
        )
    }
}
