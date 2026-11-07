package com.tiny.dns2ss

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tiny.dns2ss.config.VpnMode
import com.tiny.dns2ss.databinding.ActivityMainBinding
import com.tiny.dns2ss.ui.DiagnosticsActivity
import com.tiny.dns2ss.ui.MainViewModel
import com.tiny.dns2ss.ui.SettingsActivity
import com.tiny.dns2ss.vpn.DnsVpnService
import com.tiny.dns2ss.vpn.VpnRunState
import com.tiny.dns2ss.vpn.VpnStatus
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TILE_START = "tile_start"
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var suppressModeCallback = false

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            DnsVpnService.start(this)
        } else {
            toast(getString(R.string.vpn_permission_missing))
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressModeCallback) return@addOnButtonCheckedListener
            viewModel.setMode(modeFromId(checkedId))
        }

        binding.toggle.setOnClickListener {
            when (VpnStatus.data.value.state) {
                VpnRunState.RUNNING, VpnRunState.STARTING -> DnsVpnService.stop(this)
                else -> startVpn()
            }
        }
        binding.settings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.diagnostics.setOnClickListener { startActivity(Intent(this, DiagnosticsActivity::class.java)) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch {
            viewModel.state.collect { render(it) }
        }

        if (intent.getBooleanExtra(EXTRA_TILE_START, false)) {
            handleTileStart()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_TILE_START, false)) {
            handleTileStart()
        }
    }

    private fun handleTileStart() {
        binding.root.post {
            toast(getString(R.string.tile_starting))
            startVpn()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun render(ui: com.tiny.dns2ss.ui.MainUiState) {
        val status = ui.status
        binding.status.text = when (status.state) {
            VpnRunState.STOPPED -> getString(R.string.status_stopped)
            VpnRunState.STARTING -> getString(R.string.status_starting)
            VpnRunState.RUNNING -> getString(R.string.status_running)
            VpnRunState.STOPPING -> getString(R.string.status_stopping)
            VpnRunState.ERROR -> getString(R.string.status_error)
        }
        binding.statusDetail.text = when (status.state) {
            VpnRunState.RUNNING -> status.mode.name.lowercase()
            VpnRunState.STARTING -> status.message.ifBlank { getString(R.string.status_starting) }
            VpnRunState.ERROR -> status.message.ifBlank { getString(R.string.status_error) }
            else -> getString(modeDescriptionRes(ui.mode))
        }
        binding.targetSummary.text = getString(R.string.current_target, ui.targetHost, ui.targetPort)
        binding.toggle.text = if (status.state == VpnRunState.RUNNING || status.state == VpnRunState.STARTING) {
            getString(R.string.stop)
        } else {
            getString(R.string.start)
        }
        binding.modeDescription.text = getString(modeDescriptionRes(ui.mode))
        suppressModeCallback = true
        val buttonId = when (ui.mode) {
            VpnMode.FAST -> R.id.modeFast
            VpnMode.STRICT -> R.id.modeStrict
            VpnMode.FULL -> R.id.modeFull
        }
        if (binding.modeGroup.checkedButtonId != buttonId) binding.modeGroup.check(buttonId)
        suppressModeCallback = false
    }

    private fun startVpn() {
        val error = viewModel.validateForStart()
        if (error != null) {
            toast(getString(R.string.config_invalid, error))
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            vpnPermissionLauncher.launch(prepare)
        } else {
            DnsVpnService.start(this)
        }
    }

    private fun modeFromId(id: Int): VpnMode = when (id) {
        R.id.modeStrict -> VpnMode.STRICT
        R.id.modeFull -> VpnMode.FULL
        else -> VpnMode.FAST
    }

    private fun modeDescriptionRes(mode: VpnMode): Int = when (mode) {
        VpnMode.FAST -> R.string.mode_fast_desc
        VpnMode.STRICT -> R.string.mode_strict_desc
        VpnMode.FULL -> R.string.mode_full_desc
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
