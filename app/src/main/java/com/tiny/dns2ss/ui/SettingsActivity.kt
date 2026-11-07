package com.tiny.dns2ss.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.tiny.dns2ss.R
import com.tiny.dns2ss.databinding.ActivitySettingsBinding
import com.tiny.dns2ss.vpn.QuickTileService

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        val state = viewModel.load()
        binding.ssUri.setText(state.ssUri)
        binding.dnsHost.setText(state.dnsHost)
        binding.dnsPort.setText(state.dnsPort.toString())
        binding.ipv6.isChecked = state.ipv6Enabled

        binding.save.setOnClickListener {
            val error = viewModel.save(
                binding.ssUri.text?.toString().orEmpty(),
                binding.dnsHost.text?.toString().orEmpty(),
                binding.dnsPort.text?.toString().orEmpty(),
                binding.ipv6.isChecked,
            )
            if (error == null) {
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        }
        binding.addTile.setOnClickListener { requestAddTile() }
        binding.privateDnsButton.setOnClickListener { openPrivateDnsSettings() }
    }

    override fun onResume() {
        super.onResume()
        val enabled = privateDnsMode()?.let { it != "off" } == true
        binding.privateDnsWarning.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.privateDnsButton.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun requestAddTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.tile_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            val manager = getSystemService(StatusBarManager::class.java)
            manager.requestAddTileService(
                ComponentName(this, QuickTileService::class.java),
                getString(R.string.app_name),
                Icon.createWithResource(this, R.drawable.ic_tile),
                mainExecutor,
            ) { result ->
                when (result) {
                    0, 1 -> Toast.makeText(this, R.string.tile_added, Toast.LENGTH_SHORT).show()
                    else -> Toast.makeText(this, R.string.tile_failed, Toast.LENGTH_LONG).show()
                }
            }
        }.onFailure {
            Toast.makeText(this, R.string.tile_unsupported, Toast.LENGTH_LONG).show()
        }
    }

    private fun openPrivateDnsSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Intent("android.settings.PRIVATE_DNS_SETTINGS")
        } else {
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        }
        runCatching { startActivity(intent) }
    }

    private fun privateDnsMode(): String? = runCatching {
        Settings.Global.getString(contentResolver, "private_dns_mode")
    }.getOrNull()
}
