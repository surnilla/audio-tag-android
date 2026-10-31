package com.audiotag.app

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audiotag.app.audio.SttConverter
import com.audiotag.app.audio.TtsConverter
import com.audiotag.app.ble.AudioTagBleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// workerLabel: short ID shown in the chat (last 4 hex chars of MAC, e.g. "A4B1")
// null = message sent by the phone operator
data class Message(val text: String, val fromWorker: Boolean, val workerLabel: String? = null)

sealed class UiState {
    object Disconnected : UiState()
    object Connecting : UiState()
    data class Connected(
        val messages: List<Message> = emptyList(),
        val deviceStatus: String = ""
    ) : UiState()
    data class Error(val message: String) : UiState()
}

@SuppressLint("MissingPermission")
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        const val MIC_SAMPLE_RATE = 8000
        private const val SCAN_DURATION_MS = 10_000L
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Disconnected)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val ttsConverter = TtsConverter(application)
    private val messages = mutableListOf<Message>()

    // Map of device address → manager for all currently connected workers
    private val managers = mutableMapOf<String, AudioTagBleManager>()
    // Map of device address → current activity string (blank when idle)
    private val workerStatuses = mutableMapOf<String, String>()

    private var activeScanCallback: ScanCallback? = null
    private var bleScanner: android.bluetooth.le.BluetoothLeScanner? = null

    // true = phone converts text to PCM; false = device Flite TTS
    val usePhoneVoice = MutableStateFlow(true)

    // ---------------------------------------------------------------------------
    // Connect — scan for SCAN_DURATION_MS, connect to every AudioTag found
    // ---------------------------------------------------------------------------

    fun connect() {
        if (_uiState.value is UiState.Connecting) return
        viewModelScope.launch {
            _uiState.value = UiState.Connecting

            val btManager = getApplication<Application>()
                .getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = btManager.adapter ?: run {
                _uiState.value = UiState.Error("Bluetooth not available")
                return@launch
            }
            if (!adapter.isEnabled) {
                _uiState.value = UiState.Error("Bluetooth is disabled")
                return@launch
            }
            val scanner = adapter.bluetoothLeScanner ?: run {
                _uiState.value = UiState.Error("BLE scanner not available")
                return@launch
            }
            bleScanner = scanner

            val foundAddresses = mutableSetOf<String>()
            activeScanCallback = AudioTagBleManager.startScan(
                scanner,
                onDeviceFound = { device ->
                    // Each address triggers at most one connection attempt
                    if (foundAddresses.add(device.address)) {
                        connectWorker(device)
                    }
                },
                onScanFailed = { code ->
                    viewModelScope.launch {
                        if (managers.isEmpty()) {
                            _uiState.value = UiState.Error("BLE scan failed (error $code)")
                        }
                    }
                }
            )

            // Scan for fixed duration then stop regardless of how many devices found
            delay(SCAN_DURATION_MS)
            stopScanning()

            // If still in Connecting and nothing connected, report error
            if (_uiState.value is UiState.Connecting) {
                _uiState.value = UiState.Error("No AudioTag devices found")
            }
        }
    }

    private fun stopScanning() {
        val cb = activeScanCallback ?: return
        activeScanCallback = null
        bleScanner?.let { AudioTagBleManager.stopScan(it, cb) }
        Log.d(TAG, "Scan stopped")
    }

    // ---------------------------------------------------------------------------
    // Connect a single discovered worker device
    // ---------------------------------------------------------------------------

    private fun connectWorker(device: BluetoothDevice) {
        val address = device.address
        // Short human-readable label from the last 4 hex chars of the MAC address
        val label = address.replace(":", "").takeLast(4).uppercase()

        viewModelScope.launch {
            val manager = AudioTagBleManager(getApplication())

            manager.onStatusReceived = { statusByte ->
                viewModelScope.launch {
                    when (statusByte) {
                        0x03.toByte() -> updateWorkerStatus(address, "Recording…")
                        0x04.toByte() -> updateWorkerStatus(address, "Sending audio…")
                        0x02.toByte() -> updateWorkerStatus(address, "")
                    }
                }
            }

            manager.onDisconnected = {
                viewModelScope.launch {
                    managers.remove(address)
                    workerStatuses.remove(address)
                    refreshDeviceStatus()
                    if (managers.isEmpty()) _uiState.value = UiState.Disconnected
                }
            }

            try {
                withContext(Dispatchers.IO) { manager.connect(device) }

                // Connection succeeded — register and transition to Connected
                managers[address] = manager
                workerStatuses[address] = ""
                if (_uiState.value !is UiState.Connected) {
                    _uiState.value = UiState.Connected(messages.toList())
                }
                refreshDeviceStatus()
                armMicCallback(manager, address, label)
                Log.d(TAG, "Worker $label ($address) connected")

            } catch (e: Exception) {
                Log.e(TAG, "connectWorker $address failed: ${e.message}")
            }
        }
    }

    // Re-arms itself after each delivery so subsequent PTT presses work automatically.
    private fun armMicCallback(
        manager: AudioTagBleManager,
        address: String,
        label: String
    ) {
        manager.onMicDataReceived = { pcmData ->
            armMicCallback(manager, address, label)   // re-arm before async work
            viewModelScope.launch {
                updateWorkerStatus(address, "Recognizing speech…")
                try {
                    val transcript = SttConverter().recognize(pcmData, MIC_SAMPLE_RATE)
                    if (transcript.isNotBlank()) {
                        messages.add(Message(transcript, fromWorker = true, workerLabel = label))
                        updateConnected { it.copy(messages = messages.toList()) }
                        updateWorkerStatus(address, "")
                    } else {
                        updateWorkerStatus(address, "No speech recognized")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "STT failed", e)
                    updateWorkerStatus(address, "STT error: ${e.message}")
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Send reply — broadcast to ALL connected workers simultaneously
    // ---------------------------------------------------------------------------

    fun sendReply(text: String) {
        if (text.isBlank()) return
        val currentManagers = managers.values.toList()
        if (currentManagers.isEmpty()) {
            _uiState.value = UiState.Error("No workers connected")
            return
        }

        viewModelScope.launch {
            try {
                if (usePhoneVoice.value) {
                    val n = currentManagers.size
                    updateConnected { it.copy(deviceStatus = "Converting…") }
                    val pcmData = withContext(Dispatchers.IO) { ttsConverter.textToPcm(text) }
                    updateConnected {
                        it.copy(deviceStatus = "Sending to $n worker${if (n > 1) "s" else ""}…")
                    }
                    withContext(Dispatchers.IO) {
                        coroutineScope {
                            currentManagers.map { mgr ->
                                async {
                                    try {
                                        mgr.sendAudio(pcmData) { _, _ -> }
                                        mgr.waitForDone()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "sendAudio to a worker failed", e)
                                    }
                                }
                            }.awaitAll()
                        }
                    }
                } else {
                    val n = currentManagers.size
                    updateConnected {
                        it.copy(deviceStatus = "Sending to $n worker${if (n > 1) "s" else ""}…")
                    }
                    withContext(Dispatchers.IO) {
                        coroutineScope {
                            currentManagers.map { mgr ->
                                async {
                                    try {
                                        mgr.sendText(text)
                                        mgr.waitForDone()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "sendText to a worker failed", e)
                                    }
                                }
                            }.awaitAll()
                        }
                    }
                }
                messages.add(Message(text, fromWorker = false))
                updateConnected { it.copy(messages = messages.toList()) }
                refreshDeviceStatus()

            } catch (e: Exception) {
                Log.e(TAG, "sendReply failed", e)
                updateConnected { it.copy(deviceStatus = "Send failed: ${e.message}") }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Disconnect all workers
    // ---------------------------------------------------------------------------

    fun disconnect() {
        stopScanning()
        managers.values.forEach { it.disconnect() }
        managers.clear()
        workerStatuses.clear()
        _uiState.value = UiState.Disconnected
    }

    fun clearError() {
        if (_uiState.value is UiState.Error) _uiState.value = UiState.Disconnected
    }

    override fun onCleared() {
        super.onCleared()
        stopScanning()
        managers.values.forEach { it.disconnect() }
        managers.clear()
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun updateWorkerStatus(address: String, status: String) {
        workerStatuses[address] = status
        refreshDeviceStatus()
    }

    /** Recompute the deviceStatus string shown in the UI from all workers' states. */
    private fun refreshDeviceStatus() {
        val count = managers.size
        val activeStatus = workerStatuses.values.firstOrNull { it.isNotBlank() }
        val status = activeStatus
            ?: if (count > 0) "$count worker${if (count > 1) "s" else ""} connected" else ""
        updateConnected { it.copy(deviceStatus = status) }
    }

    private fun updateConnected(update: (UiState.Connected) -> UiState.Connected) {
        val current = _uiState.value
        if (current is UiState.Connected) _uiState.value = update(current)
    }
}
