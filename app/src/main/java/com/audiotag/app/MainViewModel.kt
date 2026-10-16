package com.audiotag.app

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audiotag.app.audio.TtsConverter
import com.audiotag.app.ble.AudioTagBleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.media.AudioTrack as AT

sealed class UiState {
    object Idle : UiState()
    object ConvertingTts : UiState()
    object PlayingLocally : UiState()
    object Scanning : UiState()
    object Connecting : UiState()
    data class Sending(val progress: Int, val total: Int) : UiState()
    object WaitingForAck : UiState()
    object Delivered : UiState()
    data class Error(val message: String) : UiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val ttsConverter = TtsConverter(application)
    private var bleManager: AudioTagBleManager? = null

    @SuppressLint("MissingPermission")
    fun sendMessage(text: String) {
        if (text.isBlank()) {
            _uiState.value = UiState.Error("Please enter a message to send.")
            return
        }

        viewModelScope.launch {
            try {
                // --- Step 1: Convert text to PCM ---
                _uiState.value = UiState.ConvertingTts
                Log.d(TAG, "Converting TTS for: $text")
                val pcmData = withContext(Dispatchers.IO) {
                    ttsConverter.textToPcm(text)
                }
                Log.d(TAG, "PCM size: ${pcmData.size} bytes")

                // --- Step 2: Scan for AudioTag ---
                _uiState.value = UiState.Scanning
                val device = withContext(Dispatchers.IO) {
                    scanForDevice()
                }
                Log.d(TAG, "Found device: ${device.address}")

                // --- Step 3: Connect ---
                _uiState.value = UiState.Connecting
                val manager = AudioTagBleManager(getApplication())
                bleManager = manager

                withContext(Dispatchers.IO) {
                    manager.connect(device)
                }
                Log.d(TAG, "Connected and services discovered")

                // --- Step 4: Send audio chunks ---
                withContext(Dispatchers.IO) {
                    manager.sendAudio(pcmData) { chunkIndex, total ->
                        // Post to main thread
                        _uiState.value = UiState.Sending(chunkIndex + 1, total)
                    }
                }

                // --- Step 5: Wait for playback done acknowledgement ---
                _uiState.value = UiState.WaitingForAck
                withContext(Dispatchers.IO) {
                    manager.waitForDone()
                }

                // --- Step 6: Delivered ---
                _uiState.value = UiState.Delivered
                Log.d(TAG, "Audio delivered and acknowledged")

            } catch (e: Exception) {
                Log.e(TAG, "sendMessage failed", e)
                _uiState.value = UiState.Error(e.message ?: "Unknown error")
            } finally {
                bleManager?.disconnect()
                bleManager = null
            }
        }
    }

    fun testLocally(text: String) {
        if (text.isBlank()) {
            _uiState.value = UiState.Error("Please enter a message to test.")
            return
        }
        viewModelScope.launch {
            try {
                _uiState.value = UiState.ConvertingTts
                val pcmData = withContext(Dispatchers.IO) {
                    ttsConverter.textToPcm(text)
                }
                Log.d(TAG, "Local test: PCM size ${pcmData.size} bytes")

                _uiState.value = UiState.PlayingLocally
                withContext(Dispatchers.IO) {
                    playPcmOnSpeaker(pcmData)
                }
                _uiState.value = UiState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "testLocally failed", e)
                _uiState.value = UiState.Error(e.message ?: "Playback failed")
            }
        }
    }

    private suspend fun playPcmOnSpeaker(pcmData: ByteArray) {
        val totalSamples = pcmData.size / 2  // 16-bit = 2 bytes per sample
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(TtsConverter.TARGET_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcmData.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        suspendCancellableCoroutine { cont ->
            track.setNotificationMarkerPosition(totalSamples)
            track.setPlaybackPositionUpdateListener(object : AT.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AT) {
                    t.release()
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onPeriodicNotification(t: AT) {}
            })
            track.write(pcmData, 0, pcmData.size)
            track.play()
            cont.invokeOnCancellation { track.release() }
        }
    }

    fun reset() {
        bleManager?.disconnect()
        bleManager = null
        _uiState.value = UiState.Idle
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanForDevice(): android.bluetooth.BluetoothDevice {
        val bluetoothManager =
            getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE)
                    as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
            ?: throw IllegalStateException("Bluetooth is not available on this device")

        if (!bluetoothAdapter.isEnabled) {
            throw IllegalStateException("Bluetooth is disabled. Please enable Bluetooth and try again.")
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner
            ?: throw IllegalStateException("BLE scanner not available — is Bluetooth enabled?")

        return suspendCancellableCoroutine { cont ->
            val tempManager = AudioTagBleManager(getApplication())

            tempManager.onError = { msg ->
                if (cont.isActive) cont.resumeWithException(IllegalStateException(msg))
            }

            tempManager.scan(scanner) { device ->
                if (cont.isActive) cont.resume(device)
            }

            cont.invokeOnCancellation {
                // Scanner will be stopped by the onDeviceFound callback;
                // nothing extra needed here.
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        bleManager?.disconnect()
    }
}
