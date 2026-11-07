package com.tiny.dns2ss.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.tiny.dns2ss.R
import com.tiny.dns2ss.config.LogVerbosity
import com.tiny.dns2ss.databinding.ActivityDiagnosticsBinding
import com.tiny.dns2ss.ui.applySystemBarInsets

class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDiagnosticsBinding
    private val viewModel: DiagnosticsViewModel by viewModels()
    private var verbosityTouched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        binding.logVerbosity.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            LogVerbosity.entries.map { it.label },
        )
        // Spinner forbids setOnItemClickListener. Use the selection callback but
        // gate it with a touch flag so programmatic setSelection during refresh
        // does not show the restart note.
        binding.logVerbosity.setOnTouchListener { _, _ ->
            verbosityTouched = true
            false
        }
        binding.logVerbosity.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (!verbosityTouched) return
                verbosityTouched = false
                val selected = LogVerbosity.entries[position]
                if (selected == viewModel.currentVerbosity()) return
                viewModel.setLogVerbosity(selected)
                Toast.makeText(this@DiagnosticsActivity, R.string.log_restart_note, Toast.LENGTH_SHORT).show()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                verbosityTouched = false
            }
        })

        binding.logWrap.setOnCheckedChangeListener { _, checked -> applyLogWrap(checked) }
        applyLogWrap(false)
        binding.refresh.setOnClickListener { refresh() }
        binding.copy.setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.logs), activeLogText()))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        binding.clear.setOnClickListener {
            viewModel.clearLogs()
            refresh()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // Pick up logs/state after returning from a VPN restart.
        refresh()
    }

    private fun applyLogWrap(wrap: Boolean) {
        // Two dedicated views avoid the nested-scroll measurement problem that
        // prevented a single TextView from re-wrapping reliably.
        binding.logNoWrapVertical.visibility = if (wrap) View.GONE else View.VISIBLE
        binding.logWrapVertical.visibility = if (wrap) View.VISIBLE else View.GONE
    }

    private fun activeLogText(): CharSequence =
        if (binding.logWrap.isChecked) binding.logsWrap.text else binding.logsNoWrap.text

    private fun refresh() {
        val state = viewModel.snapshot()
        binding.summary.text = state.summary
        val logText = state.logs.ifBlank { "-" }
        binding.logsNoWrap.text = logText
        binding.logsWrap.text = logText
        verbosityTouched = false
        binding.logVerbosity.setSelection(LogVerbosity.entries.indexOf(state.verbosity).coerceAtLeast(0))
    }
}
