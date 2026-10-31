package com.audiotag.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@SuppressLint("MissingPermission")
class AudioTagBleManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioTagBleManager"

        val SERVICE_UUID: UUID    = UUID.fromString("12345678-1234-1234-1234-1234567890AB")
        val AUDIO_DATA_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-1234567890AC")
        val TTS_UUID: UUID        = UUID.fromString("12345678-1234-1234-1234-1234567890AD")
        val STATUS_UUID: UUID     = UUID.fromString("12345678-1234-1234-1234-1234567890AE")
        val MIC_DATA_UUID: UUID   = UUID.fromString("12345678-1234-1234-1234-1234567890AF")
        val ALARM_UUID: UUID      = UUID.fromString("12345678-1234-1234-1234-1234567890B0")

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val TARGET_DEVICE_NAME = "AudioTag"
        private const val TARGET_MTU = 512

        /**
         * Start a BLE scan for AudioTag devices.  The scan continues until the
         * caller explicitly stops it with [stopScan].  Duplicate addresses are
         * reported on every scan result so the caller must deduplicate if needed.
         *
         * Uses extended / Coded-PHY scanning on API 26+ so devices that are only
         * advertising on Coded PHY are found; falls back to legacy scan on older
         * Android.
         */
        @SuppressLint("MissingPermission")
        fun startScan(
            bluetoothLeScanner: BluetoothLeScanner,
            onDeviceFound: (BluetoothDevice) -> Unit,
            onScanFailed: (Int) -> Unit = {}
        ): ScanCallback {
            val settings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ScanSettings.Builder()
                    .setLegacy(false)
                    .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
            } else {
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
            }

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name = result.device.name ?: return
                    if (name == TARGET_DEVICE_NAME) {
                        Log.d(TAG, "Found AudioTag: ${result.device.address}")
                        onDeviceFound(result.device)
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "Scan failed: $errorCode")
                    onScanFailed(errorCode)
                }
            }
            bluetoothLeScanner.startScan(null, settings, callback)
            return callback
        }

        @SuppressLint("MissingPermission")
        fun stopScan(bluetoothLeScanner: BluetoothLeScanner, callback: ScanCallback) {
            bluetoothLeScanner.stopScan(callback)
        }
    }

    // Public callbacks
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStatusReceived: ((Byte) -> Unit)? = null
    var onMicDataReceived: ((ByteArray) -> Unit)? = null

    private var gatt: BluetoothGatt? = null
    private var negotiatedMtu: Int = 23  // BLE default

    // Continuations for async BLE operations
    private var connectContinuation: CancellableContinuation<Unit>? = null
    private var writeContinuation: CancellableContinuation<Unit>? = null
    private var descriptorContinuation: CancellableContinuation<Unit>? = null
    private var statusContinuation: CancellableContinuation<Unit>? = null

    // MIC_DATA reassembly state
    private var micBuf: ByteArray? = null
    private var micTotal: Int = 0
    private var micReceived: Int = 0

    // Cached MIC_DATA characteristic for CCCD chaining in connect flow
    private var micDataChar: BluetoothGattCharacteristic? = null

    // True while we still need to write the MIC_DATA CCCD (connect flow only)
    private var pendingMicCccd: Boolean = false

    // ---------------------------------------------------------------------------
    // Connect
    // ---------------------------------------------------------------------------

    suspend fun connect(device: BluetoothDevice) {
        suspendCancellableCoroutine<Unit> { cont ->
            connectContinuation = cont

            // On API 26+ request Coded PHY as the preferred initial PHY.
            // The stack will negotiate; if the remote doesn't support it the
            // connection falls back to 1M without error.
            val g = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                device.connectGatt(
                    context, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_CODED_MASK
                )
            } else {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
            gatt = g

            cont.invokeOnCancellation {
                g.disconnect()
                g.close()
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Send audio (raw PCM)
    // ---------------------------------------------------------------------------

    suspend fun sendAudio(pcmData: ByteArray, onProgress: (Int, Int) -> Unit) {
        val g = gatt ?: throw IllegalStateException("Not connected")
        val service = g.getService(SERVICE_UUID)
            ?: throw IllegalStateException("AudioTag service not found")
        val characteristic = service.getCharacteristic(AUDIO_DATA_UUID)
            ?: throw IllegalStateException("AudioData characteristic not found")

        val payloadSize = negotiatedMtu - 3
        if (payloadSize <= 0) throw IllegalStateException("MTU too small: $negotiatedMtu")

        // First chunk includes the 4-byte little-endian total length header
        val headerSize = 4
        val firstPayloadCapacity = payloadSize - headerSize
        val firstPayloadLen = minOf(firstPayloadCapacity, pcmData.size)

        val firstChunk = ByteArray(headerSize + firstPayloadLen)
        val len = pcmData.size
        firstChunk[0] = (len and 0xFF).toByte()
        firstChunk[1] = ((len shr 8) and 0xFF).toByte()
        firstChunk[2] = ((len shr 16) and 0xFF).toByte()
        firstChunk[3] = ((len shr 24) and 0xFF).toByte()
        System.arraycopy(pcmData, 0, firstChunk, headerSize, firstPayloadLen)

        val chunks = mutableListOf(firstChunk)
        var offset = firstPayloadLen
        while (offset < pcmData.size) {
            val end = minOf(offset + payloadSize, pcmData.size)
            chunks.add(pcmData.copyOfRange(offset, end))
            offset = end
        }

        val totalChunks = chunks.size
        Log.d(TAG, "Sending $totalChunks chunks, payloadSize=$payloadSize, totalPcm=${pcmData.size}")

        for ((index, chunk) in chunks.withIndex()) {
            writeCharacteristic(g, characteristic, chunk)
            onProgress(index, totalChunks)
            Log.d(TAG, "Sent chunk ${index + 1}/$totalChunks (${chunk.size} bytes)")
        }
    }

    // ---------------------------------------------------------------------------
    // Send text (→ Flite TTS on device)
    // ---------------------------------------------------------------------------

    suspend fun sendText(text: String) {
        val g = gatt ?: throw IllegalStateException("Not connected")
        val service = g.getService(SERVICE_UUID)
            ?: throw IllegalStateException("AudioTag service not found")
        val characteristic = service.getCharacteristic(TTS_UUID)
            ?: throw IllegalStateException("TTS characteristic not found")
        writeCharacteristic(g, characteristic, text.toByteArray(Charsets.UTF_8))
    }

    suspend fun sendAlarm() {
        val g = gatt ?: throw IllegalStateException("Not connected")
        val service = g.getService(SERVICE_UUID)
            ?: throw IllegalStateException("AudioTag service not found")
        val characteristic = service.getCharacteristic(ALARM_UUID)
            ?: throw IllegalStateException("Alarm characteristic not found")
        writeCharacteristic(g, characteristic, byteArrayOf(0x01))
    }

    // ---------------------------------------------------------------------------
    // Wait for playback done (status 0x02)
    // ---------------------------------------------------------------------------

    suspend fun waitForDone() {
        suspendCancellableCoroutine<Unit> { cont ->
            statusContinuation = cont
        }
    }

    // ---------------------------------------------------------------------------
    // Disconnect
    // ---------------------------------------------------------------------------

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    // ---------------------------------------------------------------------------
    // Write helpers
    // ---------------------------------------------------------------------------

    private suspend fun writeCharacteristic(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray
    ) {
        suspendCancellableCoroutine<Unit> { cont ->
            writeContinuation = cont

            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(
                    characteristic,
                    data,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = data
                @Suppress("DEPRECATION")
                g.writeCharacteristic(characteristic)
            }

            if (!success) {
                writeContinuation = null
                if (cont.isActive) cont.resumeWithException(
                    IllegalStateException("writeCharacteristic() initiation failed")
                )
            }
        }
    }

    private suspend fun writeDescriptor(
        g: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        data: ByteArray
    ) {
        suspendCancellableCoroutine<Unit> { cont ->
            descriptorContinuation = cont

            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(descriptor, data) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = data
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }

            if (!success) {
                descriptorContinuation = null
                if (cont.isActive) cont.resumeWithException(
                    IllegalStateException("writeDescriptor() initiation failed")
                )
            }
        }
    }

    // ---------------------------------------------------------------------------
    // GATT callback
    // ---------------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected — requesting MTU $TARGET_MTU")
                    gatt.requestMtu(TARGET_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected")
                    val cont = connectContinuation
                    connectContinuation = null
                    if (cont != null && cont.isActive) {
                        cont.resumeWithException(
                            IllegalStateException("Disconnected before services discovered (status=$status)")
                        )
                    }
                    onDisconnected?.invoke()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged: mtu=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
            }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered: status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val cont = connectContinuation
                connectContinuation = null
                if (cont != null && cont.isActive) {
                    cont.resumeWithException(
                        IllegalStateException("Service discovery failed: status $status")
                    )
                }
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                val cont = connectContinuation
                connectContinuation = null
                if (cont != null && cont.isActive) {
                    cont.resumeWithException(
                        IllegalStateException("AudioTag service UUID not found on device")
                    )
                }
                return
            }

            val statusChar = service.getCharacteristic(STATUS_UUID)
            if (statusChar == null) {
                val cont = connectContinuation
                connectContinuation = null
                if (cont != null && cont.isActive) {
                    cont.resumeWithException(
                        IllegalStateException("Status characteristic not found")
                    )
                }
                return
            }

            // Request Coded PHY upgrade after services are known.
            // This is a best-effort call — if the remote doesn't support Coded PHY
            // it stays on 1M and onPhyUpdate is not called (or called with 1M).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                gatt.setPreferredPhy(
                    BluetoothDevice.PHY_LE_CODED_MASK,
                    BluetoothDevice.PHY_LE_CODED_MASK,
                    BluetoothDevice.PHY_OPTION_S8
                )
                Log.d(TAG, "Requested Coded PHY (S8)")
            }

            // Cache MIC_DATA characteristic for CCCD chaining
            micDataChar = service.getCharacteristic(MIC_DATA_UUID)

            // Enable local notifications for both characteristics
            gatt.setCharacteristicNotification(statusChar, true)
            micDataChar?.let { gatt.setCharacteristicNotification(it, true) }

            // Flag: still need to write MIC_DATA CCCD after STATUS CCCD completes
            pendingMicCccd = (micDataChar?.getDescriptor(CCCD_UUID) != null)
            Log.d(TAG, "pendingMicCccd=$pendingMicCccd")

            // Write CCCD for STATUS first; MIC_DATA CCCD will be written in onDescriptorWrite
            val cccd = statusChar.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                // No CCCD — skip straight to done
                Log.w(TAG, "STATUS CCCD descriptor not found — skipping")
                pendingMicCccd = false
                val cont = connectContinuation
                connectContinuation = null
                if (cont != null && cont.isActive) {
                    cont.resume(Unit)
                    onConnected?.invoke()
                }
                return
            }

            val enableValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val writeResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, enableValue)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enableValue
                @Suppress("DEPRECATION")
                if (gatt.writeDescriptor(cccd)) BluetoothGatt.GATT_SUCCESS else -1
            }

            if (writeResult != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "STATUS CCCD write initiation returned: $writeResult")
            }
            // Chain continues in onDescriptorWrite
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            Log.d(TAG, "onPhyUpdate: txPhy=$txPhy rxPhy=$rxPhy status=$status")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d(TAG, "onDescriptorWrite: status=$status uuid=${descriptor.uuid} pendingMicCccd=$pendingMicCccd")

            // If a descriptor write was issued from the sendAudio flow, handle it first
            val descCont = descriptorContinuation
            if (descCont != null && descCont.isActive) {
                descriptorContinuation = null
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    descCont.resume(Unit)
                } else {
                    descCont.resumeWithException(
                        IllegalStateException("Descriptor write failed: status $status")
                    )
                }
                return
            }

            // Connect-flow: use pendingMicCccd flag to avoid descriptor.characteristic null issues
            if (pendingMicCccd) {
                // This was the STATUS CCCD completing — now write MIC_DATA CCCD
                pendingMicCccd = false
                val micCccd = micDataChar?.getDescriptor(CCCD_UUID)
                if (micCccd != null) {
                    val enableValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    val writeResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(micCccd, enableValue)
                    } else {
                        @Suppress("DEPRECATION")
                        micCccd.value = enableValue
                        @Suppress("DEPRECATION")
                        if (gatt.writeDescriptor(micCccd)) BluetoothGatt.GATT_SUCCESS else -1
                    }
                    if (writeResult == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "MIC_DATA CCCD write initiated")
                        return  // Wait for next onDescriptorWrite
                    }
                    Log.w(TAG, "MIC_DATA CCCD write initiation failed: $writeResult — resuming anyway")
                }
                resumeConnect(status)
            } else {
                // Either the MIC_DATA CCCD completing, or a one-shot STATUS write (no mic char)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Final CCCD write failed: $status — continuing anyway")
                }
                resumeConnect(BluetoothGatt.GATT_SUCCESS)
            }
        }

        private fun resumeConnect(status: Int) {
            val cont = connectContinuation
            connectContinuation = null
            if (cont != null && cont.isActive) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    cont.resume(Unit)
                    onConnected?.invoke()
                } else {
                    cont.resumeWithException(
                        IllegalStateException("CCCD descriptor write failed: status $status")
                    )
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicWrite: status=$status uuid=${characteristic.uuid}")
            val cont = writeContinuation
            writeContinuation = null
            if (cont != null && cont.isActive) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    cont.resume(Unit)
                } else {
                    cont.resumeWithException(
                        IllegalStateException("Characteristic write failed: status $status")
                    )
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                dispatchNotification(characteristic.uuid, characteristic.value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            dispatchNotification(characteristic.uuid, value)
        }
    }

    private fun dispatchNotification(uuid: UUID, value: ByteArray) {
        when (uuid) {
            STATUS_UUID   -> handleStatusNotification(value)
            MIC_DATA_UUID -> handleMicDataNotification(value)
        }
    }

    private fun handleStatusNotification(value: ByteArray) {
        val statusByte = value.firstOrNull() ?: return
        Log.d(TAG, "Status notification: 0x${statusByte.toInt().and(0xFF).toString(16)}")
        onStatusReceived?.invoke(statusByte)
        if (statusByte == 0x02.toByte()) {
            // STATUS_DONE: only flush if MIC_DATA is still partially buffered (packet loss /
            // no-CCCD case). If MIC_DATA already completed, micBuf is null and the callback
            // was already re-armed — don't steal it with empty data.
            if (micBuf != null && micReceived > 0) {
                val micCallback = onMicDataReceived
                if (micCallback != null) {
                    onMicDataReceived = null
                    val data = micBuf!!.copyOf(micReceived)
                    micBuf = null; micTotal = 0; micReceived = 0
                    Log.d(TAG, "STATUS_DONE flush: delivering ${data.size} mic bytes")
                    micCallback.invoke(data)
                }
            } else {
                micBuf = null; micTotal = 0; micReceived = 0
            }
            // Resume waitForDone() in the send-audio flow
            val cont = statusContinuation
            statusContinuation = null
            if (cont != null && cont.isActive) {
                cont.resume(Unit)
            }
        }
    }

    private fun handleMicDataNotification(value: ByteArray) {
        if (micBuf == null) {
            // First notification: 4-byte LE total length header
            if (value.size < 4) return
            micTotal = (value[0].toInt() and 0xFF) or
                       ((value[1].toInt() and 0xFF) shl 8) or
                       ((value[2].toInt() and 0xFF) shl 16) or
                       ((value[3].toInt() and 0xFF) shl 24)
            if (micTotal <= 0) return
            micBuf = ByteArray(micTotal)
            micReceived = 0
            val payload = value.size - 4
            if (payload > 0) {
                val copy = minOf(payload, micTotal)
                System.arraycopy(value, 4, micBuf!!, 0, copy)
                micReceived = copy
            }
            Log.d(TAG, "MIC_DATA start: total=$micTotal, first chunk payload=$micReceived")
        } else {
            val space = micTotal - micReceived
            val copy = minOf(value.size, space)
            System.arraycopy(value, 0, micBuf!!, micReceived, copy)
            micReceived += copy
        }

        if (micReceived >= micTotal) {
            val data = micBuf!!.copyOf(micTotal)
            micBuf = null; micTotal = 0; micReceived = 0
            Log.d(TAG, "MIC_DATA complete: ${data.size} bytes")
            onMicDataReceived?.invoke(data)
        }
    }
}
