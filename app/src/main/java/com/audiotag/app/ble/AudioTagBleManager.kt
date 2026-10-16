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

        val SERVICE_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-1234567890AB")
        val AUDIO_DATA_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-1234567890AC")
        val CLIP_SELECT_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-1234567890AD")
        val STATUS_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-1234567890AE")

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val TARGET_DEVICE_NAME = "AudioTag"
        private const val TARGET_MTU = 512
    }

    // Public callbacks
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var gatt: BluetoothGatt? = null
    private var negotiatedMtu: Int = 23  // BLE default

    // Continuations for async BLE operations
    private var connectContinuation: CancellableContinuation<Unit>? = null
    private var writeContinuation: CancellableContinuation<Unit>? = null
    private var descriptorContinuation: CancellableContinuation<Unit>? = null
    private var statusContinuation: CancellableContinuation<Unit>? = null

    // ---------------------------------------------------------------------------
    // Scan
    // ---------------------------------------------------------------------------

    fun scan(bluetoothLeScanner: BluetoothLeScanner, onDeviceFound: (BluetoothDevice) -> Unit) {
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (name == TARGET_DEVICE_NAME) {
                    Log.d(TAG, "Found AudioTag: ${result.device.address}")
                    bluetoothLeScanner.stopScan(this)
                    onDeviceFound(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed with error: $errorCode")
                onError?.invoke("BLE scan failed: error $errorCode")
            }
        }
        bluetoothLeScanner.startScan(scanCallback)
    }

    // ---------------------------------------------------------------------------
    // Connect
    // ---------------------------------------------------------------------------

    suspend fun connect(device: BluetoothDevice) {
        suspendCancellableCoroutine<Unit> { cont ->
            connectContinuation = cont

            val g = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            gatt = g

            cont.invokeOnCancellation {
                g.disconnect()
                g.close()
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Send audio
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
        // Write 4-byte LE length
        val len = pcmData.size
        firstChunk[0] = (len and 0xFF).toByte()
        firstChunk[1] = ((len shr 8) and 0xFF).toByte()
        firstChunk[2] = ((len shr 16) and 0xFF).toByte()
        firstChunk[3] = ((len shr 24) and 0xFF).toByte()
        System.arraycopy(pcmData, 0, firstChunk, headerSize, firstPayloadLen)

        // Build remaining chunks
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
    // Wait for playback done (status 0x02)
    // ---------------------------------------------------------------------------

    suspend fun waitForDone() {
        suspendCancellableCoroutine<Unit> { cont ->
            statusContinuation = cont
            // If already in the right state, the continuation will be resumed
            // by onCharacteristicChanged.
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
            // Proceed to service discovery regardless of MTU negotiation result
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

            // Enable notifications on the status characteristic
            val notifEnabled = gatt.setCharacteristicNotification(statusChar, true)
            Log.d(TAG, "setCharacteristicNotification: $notifEnabled")

            val cccd = statusChar.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                // No CCCD — resume anyway, notifications might still work on some devices
                Log.w(TAG, "CCCD descriptor not found — skipping descriptor write")
                val cont = connectContinuation
                connectContinuation = null
                if (cont != null && cont.isActive) {
                    cont.resume(Unit)
                    onConnected?.invoke()
                }
                return
            }

            // Write CCCD to enable notifications; we'll resume connectContinuation in
            // onDescriptorWrite once this completes.
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
                Log.w(TAG, "writeDescriptor for CCCD initiation returned: $writeResult")
            }
            // Actual resume happens in onDescriptorWrite
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d(TAG, "onDescriptorWrite: status=$status uuid=${descriptor.uuid}")

            // Check if this is a pending descriptor write from sendAudio flow
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

            // Otherwise this is from the connect flow (CCCD write)
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
                handleStatusNotification(characteristic.uuid, characteristic.value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleStatusNotification(characteristic.uuid, value)
        }
    }

    private fun handleStatusNotification(uuid: UUID, value: ByteArray) {
        if (uuid != STATUS_UUID) return
        val statusByte = value.firstOrNull() ?: return
        Log.d(TAG, "Status notification: 0x${statusByte.toString(16)}")
        if (statusByte == 0x02.toByte()) {
            val cont = statusContinuation
            statusContinuation = null
            if (cont != null && cont.isActive) {
                cont.resume(Unit)
            }
        }
    }
}
