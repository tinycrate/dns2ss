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
)

object VpnStatus {
    private val mutable = MutableStateFlow(VpnStatusData())
    val data: StateFlow<VpnStatusData> = mutable.asStateFlow()

    fun set(state: VpnRunState, mode: VpnMode = mutable.value.mode, message: String = "") {
        val old = mutable.value
        mutable.value = old.copy(state = state, mode = mode, message = message)
        quickTileRefresh?.invoke()
    }

    @Volatile
    var quickTileRefresh: (() -> Unit)? = null
}
