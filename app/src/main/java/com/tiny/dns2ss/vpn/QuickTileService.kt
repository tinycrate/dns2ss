package com.tiny.dns2ss.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.tiny.dns2ss.MainActivity
import com.tiny.dns2ss.R

class QuickTileService : TileService() {
    companion object {
        fun requestUpdate(context: android.content.Context) {
            runCatching {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, QuickTileService::class.java),
                )
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        when (VpnStatus.data.value.state) {
            VpnRunState.RUNNING, VpnRunState.STARTING -> {
                optimistic(Tile.STATE_INACTIVE, "Stopping…")
                DnsVpnService.stop(this)
            }
            VpnRunState.STOPPING -> Unit
            VpnRunState.STOPPED, VpnRunState.ERROR -> startFromTile()
        }
    }

    private fun startFromTile() {
        val valid = runCatching { AppPreferences(this).loadValidated() }.isSuccess
        if (!valid) {
            launchMain(firstRun = true)
            return
        }
        if (VpnService.prepare(this) != null) {
            launchMain(firstRun = true)
            return
        }
        optimistic(Tile.STATE_ACTIVE, "Starting…")
        DnsVpnService.start(this)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchMain(firstRun: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_TILE_START, firstRun)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                200,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun optimistic(state: Int, subtitle: String) {
        val tile = qsTile ?: return
        tile.state = state
        tile.label = "${modeLabel(selectedMode())} ${getString(R.string.app_name)}"
        tile.subtitle = subtitle
        tile.updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val status = VpnStatus.data.value
        val state = status.state
        val mode = if (state == VpnRunState.STOPPED || state == VpnRunState.ERROR) {
            selectedMode()
        } else {
            status.mode
        }
        val modeText = modeLabel(mode)
        val stateText = when (state) {
            VpnRunState.RUNNING -> "Running"
            VpnRunState.STARTING -> "Starting…"
            VpnRunState.STOPPING -> "Stopping…"
            VpnRunState.ERROR -> "Error"
            VpnRunState.STOPPED -> "Off"
        }
        // Some launchers do not render Tile.subtitle; keep the mode in the label
        // so it is always visible.
        tile.label = "$modeText ${getString(R.string.app_name)}"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.state = when (state) {
            VpnRunState.RUNNING, VpnRunState.STARTING -> Tile.STATE_ACTIVE
            VpnRunState.STOPPING -> Tile.STATE_UNAVAILABLE
            VpnRunState.STOPPED, VpnRunState.ERROR -> Tile.STATE_INACTIVE
        }
        tile.subtitle = stateText
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = "$modeText, $stateText"
        }
        tile.updateTile()
    }

    private fun selectedMode(): com.tiny.dns2ss.config.VpnMode =
        runCatching { AppPreferences(this).mode }.getOrDefault(VpnStatus.data.value.mode)

    private fun modeLabel(mode: com.tiny.dns2ss.config.VpnMode): String =
        mode.name.lowercase().replaceFirstChar { it.uppercase() }
}
