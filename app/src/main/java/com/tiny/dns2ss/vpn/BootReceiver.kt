package com.tiny.dns2ss.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/**
 * Opt-in boot auto-start. BOOT_COMPLETED is delivered after the user unlock, so
 * the credential-protected preferences are available without direct-boot mode.
 * The VPN is only started if the saved config is valid and VPN permission has
 * already been granted; there is no UI to prompt for permission from a receiver.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = AppPreferences(context)
        if (!prefs.autoStartOnBoot) return
        if (runCatching { prefs.loadValidated() }.isFailure) return
        val permission = runCatching { VpnService.prepare(context) }.getOrNull()
        if (permission != null) return
        runCatching { DnsVpnService.start(context) }
    }
}
