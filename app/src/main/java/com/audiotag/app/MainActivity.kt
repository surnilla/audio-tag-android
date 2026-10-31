package com.audiotag.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.audiotag.app.databinding.ActivityMainBinding
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val denied = results.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                Toast.makeText(this, "Permissions denied — BLE will not work.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestBlePermissions()

        binding.switchVoiceMode.setOnCheckedChangeListener { _, checked ->
            viewModel.usePhoneVoice.value = checked
            binding.tvVoiceMode.text = getString(
                if (checked) R.string.label_phone_voice else R.string.label_device_voice
            )
        }

        binding.btnConnect.setOnClickListener {
            when (viewModel.uiState.value) {
                is UiState.Connected -> viewModel.disconnect()
                is UiState.Disconnected, is UiState.Error -> viewModel.connect()
                else -> { /* Connecting / Scanning — ignore taps */ }
            }
        }

        binding.btnSend.setOnClickListener { sendReply() }
        binding.btnAlert.setOnClickListener { viewModel.triggerAlarm() }

        binding.etReply.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendReply(); true } else false
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.workers.collect { workers -> renderWorkers(workers) }
            }
        }
    }

    private fun sendReply() {
        val text = binding.etReply.text?.toString()?.trim() ?: return
        if (text.isBlank()) return
        hideKeyboard()
        viewModel.sendReply(text)
        binding.etReply.setText("")
    }

    private var lastMessageCount = 0

    private fun render(state: UiState) {
        when (state) {
            is UiState.Disconnected -> {
                binding.tvConnectionStatus.text = getString(R.string.status_disconnected)
                binding.tvConnectionStatus.setTextColor(getColor(android.R.color.darker_gray))
                binding.btnConnect.text = getString(R.string.btn_connect)
                binding.btnConnect.isEnabled = true
                binding.btnSend.isEnabled = false
                binding.tvDeviceStatus.isVisible = false
                binding.btnAlert.isVisible = false
                viewModel.clearError()
            }

            is UiState.Connecting -> {
                binding.tvConnectionStatus.text = getString(R.string.status_connecting)
                binding.tvConnectionStatus.setTextColor(getColor(android.R.color.darker_gray))
                binding.btnConnect.text = getString(R.string.btn_connect)
                binding.btnConnect.isEnabled = false
                binding.btnSend.isEnabled = false
                binding.tvDeviceStatus.isVisible = false
                binding.btnAlert.isVisible = false
            }

            is UiState.Connected -> {
                binding.tvConnectionStatus.text = getString(R.string.status_connected)
                binding.tvConnectionStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                binding.btnConnect.text = getString(R.string.btn_disconnect_all)
                binding.btnConnect.isEnabled = true
                binding.btnSend.isEnabled = true
                binding.btnAlert.isVisible = true

                val status = state.deviceStatus
                binding.tvDeviceStatus.isVisible = status.isNotBlank()
                binding.tvDeviceStatus.text = status

                // Append only new messages
                if (state.messages.size > lastMessageCount) {
                    for (i in lastMessageCount until state.messages.size) {
                        appendMessage(state.messages[i])
                    }
                    lastMessageCount = state.messages.size
                    binding.scrollMessages.post {
                        binding.scrollMessages.fullScroll(android.view.View.FOCUS_DOWN)
                    }
                }
            }

            is UiState.Error -> {
                binding.tvConnectionStatus.text = state.message
                binding.tvConnectionStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                binding.btnConnect.text = getString(R.string.btn_connect)
                binding.btnConnect.isEnabled = true
                binding.btnSend.isEnabled = false
                binding.tvDeviceStatus.isVisible = false
                binding.btnAlert.isVisible = false
            }
        }
    }

    private fun renderWorkers(workers: List<WorkerInfo>) {
        binding.workerScrollView.isVisible = workers.isNotEmpty()
        binding.workerChipGroup.removeAllViews()
        if (workers.isEmpty()) return

        val selectedColor   = getColor(android.R.color.holo_green_dark)
        val deselectedColor = getColor(android.R.color.darker_gray)
        val whiteColor      = getColor(android.R.color.white)

        fun styleChip(chip: Chip, selected: Boolean) {
            chip.isCheckable = false   // we manage appearance manually
            chip.setChipBackgroundColorResource(android.R.color.transparent)
            if (selected) {
                chip.chipStrokeWidth = 0f
                chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(selectedColor))
                chip.setTextColor(whiteColor)
            } else {
                chip.chipStrokeWidth = 2f * resources.displayMetrics.density
                chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(getColor(android.R.color.transparent)))
                chip.setTextColor(deselectedColor)
                chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(deselectedColor)
            }
        }

        // "All" chip — selects all workers
        val allSelected = workers.all { it.selected }
        val allChip = Chip(this).apply {
            text = "All"
            styleChip(this, allSelected)
            setOnClickListener { viewModel.selectAllWorkers() }
        }
        binding.workerChipGroup.addView(allChip)

        // One chip per worker
        workers.forEach { worker ->
            val chip = Chip(this).apply {
                text = worker.label
                styleChip(this, worker.selected)
                setOnClickListener { viewModel.toggleWorker(worker.address) }
            }
            binding.workerChipGroup.addView(chip)
        }
    }

    private fun appendMessage(msg: Message) {
        val tv = TextView(this).apply {
            val senderLabel = if (msg.fromWorker) "Worker ${msg.workerLabel ?: ""}: " else "You: "
            text = "$senderLabel${msg.text}"
            textSize = 15f
            gravity = if (msg.fromWorker) Gravity.START else Gravity.END
            setTextColor(
                if (msg.fromWorker) getColor(android.R.color.black)
                else getColor(android.R.color.holo_blue_dark)
            )
            val pad = (12 * resources.displayMetrics.density).toInt()
            val vertPad = (6 * resources.displayMetrics.density).toInt()
            setPadding(pad, vertPad, pad, vertPad)
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (msg.fromWorker) params.gravity = Gravity.START else params.gravity = Gravity.END
        binding.messageContainer.addView(tv, params)
    }

    private fun requestBlePermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            add(Manifest.permission.RECORD_AUDIO)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
    }
}
