package com.tiny.dns2ss.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.ViewGroup
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
        // onItemClick fires for a user selection from the dropdown, not for the
        // programmatic setSelection performed when this page is refreshed.
        binding.logVerbosity.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val selected = LogVerbosity.entries[position]
            if (selected != viewModel.currentVerbosity()) {
                viewModel.setLogVerbosity(selected)
                Toast.makeText(this@DiagnosticsActivity, R.string.log_restart_note, Toast.LENGTH_SHORT).show()
            }
        }

        binding.logWrap.setOnCheckedChangeListener { _, checked -> applyLogWrap(checked) }
        applyLogWrap(false)
        binding.refresh.setOnClickListener { refresh() }
        binding.copy.setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.logs), binding.logs.text))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        binding.clear.setOnClickListener {
            viewModel.clearLogs()
            refresh()
        }
        refresh()
    }

    private fun applyLogWrap(wrap: Boolean) {
        binding.logs.setHorizontallyScrolling(!wrap)
        binding.logs.layoutParams = binding.logs.layoutParams.apply {
            width = if (wrap) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        binding.logVertical.layoutParams = binding.logVertical.layoutParams.apply {
            width = if (wrap) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        binding.logs.requestLayout()
        binding.logVertical.requestLayout()
    }

    private fun refresh() {
        val state = viewModel.snapshot()
        binding.summary.text = state.summary
        binding.logs.text = state.logs.ifBlank { "-" }
        binding.logVerbosity.setSelection(LogVerbosity.entries.indexOf(state.verbosity).coerceAtLeast(0))
    }
}
