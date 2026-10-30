package com.audiotag.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.audiotag.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    // ---------------------------------------------------------------------------
    // Permission request
    // ---------------------------------------------------------------------------

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val denied = results.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                Toast.makeText(
                    this,
                    "Permissions denied: ${denied.joinToString()}. BLE will not work.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestBlePermissions()
        setupUi()
        observeUiState()
    }

    // ---------------------------------------------------------------------------
    // UI setup
    // ---------------------------------------------------------------------------

    private fun setupUi() {
        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text?.toString()?.trim() ?: ""
            if (text.isEmpty()) {
                binding.etMessage.error = getString(R.string.error_empty_message)
                return@setOnClickListener
            }
            hideKeyboard()
            viewModel.sendMessage(text)
        }

        binding.btnTestLocally.setOnClickListener {
            val text = binding.etMessage.text?.toString()?.trim() ?: ""
            if (text.isEmpty()) {
                binding.etMessage.error = getString(R.string.error_empty_message)
                return@setOnClickListener
            }
            hideKeyboard()
            viewModel.testLocally(text)
        }

        binding.btnListen.setOnClickListener {
            hideKeyboard()
            viewModel.startListeningForPtt()
        }

        binding.btnSendResult.setOnClickListener {
            val text = binding.tvSpeechResult.text?.toString() ?: ""
            viewModel.sendTextToDevice(text)
        }

        binding.btnReset.setOnClickListener {
            viewModel.reset()
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUi(state)
                }
            }
        }
    }

    private fun updateUi(state: UiState) {
        // Default: hide transient controls
        binding.progressBar.visibility = View.GONE
        binding.btnReset.visibility = View.GONE
        binding.tvSpeechResultLabel.visibility = View.GONE
        binding.tvSpeechResult.visibility = View.GONE
        binding.btnSendResult.visibility = View.GONE

        when (state) {
            is UiState.Idle -> {
                binding.tvStatus.text = getString(R.string.status_idle)
                binding.tvStatus.setTextColor(getColor(android.R.color.darker_gray))
                setInputEnabled(true)
            }

            is UiState.ConvertingTts -> {
                binding.tvStatus.text = getString(R.string.status_converting)
                binding.tvStatus.setTextColor(getColor(android.R.color.darker_gray))
                setInputEnabled(false)
            }

            is UiState.PlayingLocally -> {
                binding.tvStatus.text = getString(R.string.status_playing_locally)
                binding.tvStatus.setTextColor(getColor(android.R.color.darker_gray))
                setInputEnabled(false)
            }

            is UiState.Scanning -> {
                binding.tvStatus.text = getString(R.string.status_scanning)
                binding.tvStatus.setTextColor(getColor(android.R.color.darker_gray))
                setInputEnabled(false)
            }

            is UiState.Connecting -> {
                binding.tvStatus.text = getString(R.string.status_connecting)
                binding.tvStatus.setTextColor(getColor(android.R.color.darker_gray))
                setInputEnabled(false)
            }

            is UiState.Sending -> {
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.max = state.total
                binding.progressBar.progress = state.progress
                binding.tvStatus.text = getString(
                    R.string.status_sending,
                    state.progress,
                    state.total
                )
                binding.tvStatus.setTextColor(getColor(android.R.color.darker_gray))
                setInputEnabled(false)
            }

            is UiState.WaitingForAck -> {
                binding.tvStatus.text = getString(R.string.status_waiting_ack)
                binding.tvStatus.setTextColor(getColor(android.R.color.darker_gray))
                setInputEnabled(false)
            }

            is UiState.Delivered -> {
                binding.tvStatus.text = getString(R.string.status_delivered)
                binding.tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                setInputEnabled(true)
                binding.btnReset.visibility = View.VISIBLE
            }

            // --- PTT states ---

            is UiState.WaitingForPtt -> {
                binding.tvStatus.text = getString(R.string.status_waiting_ptt)
                binding.tvStatus.setTextColor(getColor(android.R.color.darker_gray))
                setInputEnabled(false)
            }

            is UiState.RecordingOnDevice -> {
                binding.tvStatus.text = getString(R.string.status_recording_device)
                binding.tvStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
                setInputEnabled(false)
            }

            is UiState.ReceivingMicAudio -> {
                binding.tvStatus.text = getString(R.string.status_receiving_mic)
                binding.tvStatus.setTextColor(getColor(android.R.color.darker_gray))
                setInputEnabled(false)
            }

            is UiState.RecognizingSpeech -> {
                binding.tvStatus.text = getString(R.string.status_recognizing)
                binding.tvStatus.setTextColor(getColor(android.R.color.darker_gray))
                setInputEnabled(false)
            }

            is UiState.SpeechResult -> {
                binding.tvStatus.text = getString(R.string.status_speech_done)
                binding.tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                binding.tvSpeechResultLabel.visibility = View.VISIBLE
                binding.tvSpeechResult.visibility = View.VISIBLE
                binding.tvSpeechResult.text = state.text
                binding.btnSendResult.visibility = View.VISIBLE
                binding.btnReset.visibility = View.VISIBLE
                setInputEnabled(true)
            }

            is UiState.Error -> {
                binding.tvStatus.text = getString(R.string.status_error, state.message)
                binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                setInputEnabled(true)
                binding.btnReset.visibility = View.VISIBLE
            }
        }
    }

    private fun setInputEnabled(enabled: Boolean) {
        binding.btnSend.isEnabled = enabled
        binding.btnTestLocally.isEnabled = enabled
        binding.btnListen.isEnabled = enabled
    }

    // ---------------------------------------------------------------------------
    // Permissions
    // ---------------------------------------------------------------------------

    private fun requestBlePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ---------------------------------------------------------------------------
    // Keyboard helper
    // ---------------------------------------------------------------------------

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }
}
