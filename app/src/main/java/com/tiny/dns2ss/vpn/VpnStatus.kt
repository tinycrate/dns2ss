package com.tiny.dns2ss.vpn

import com.tiny.dns2ss.config.VpnMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnRunState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR,
}

data class VpnStatusData(
    val state: VpnRunState = VpnRunState.STOPPED,
    val mode: VpnMode = VpnMode.default,
    val message: String = "",
    val dnsQueries: Long = 0,
    val dnsResponses: Long = 0,
    val directBytes: Long = 0,
    val tunnelBytes: Long = 0,
    val fullBytes: Long = 0,
    val errors: Long = 0,
)

object VpnStatus {
    private val mutable = MutableStateFlow(VpnStatusData())
    val data: StateFlow<VpnStatusData> = mutable.asStateFlow()

    @Volatile
    var activeTunnelPort: Int? = null
        private set

    fun set(state: VpnRunState, mode: VpnMode = mutable.value.mode, message: String = "") {
        val old = mutable.value
        mutable.value = old.copy(state = state, mode = mode, message = message)
        quickTileRefresh?.invoke()
    }

    fun updateStats(
        dnsQueries: Long,
        dnsResponses: Long,
        directBytes: Long,
        tunnelBytes: Long,
        fullBytes: Long,
        errors: Long,
    ) {
        val old = mutable.value
        mutable.value = old.copy(
            dnsQueries = dnsQueries,
            dnsResponses = dnsResponses,
            directBytes = directBytes,
            tunnelBytes = tunnelBytes,
            fullBytes = fullBytes,
            errors = errors,
        )
    }

    fun reset() {
        mutable.value = VpnStatusData()
        activeTunnelPort = null
        quickTileRefresh?.invoke()
    }

    fun setActiveTunnelPort(port: Int?) {
        activeTunnelPort = port
    }

    @Volatile
    var quickTileRefresh: (() -> Unit)? = null
}
