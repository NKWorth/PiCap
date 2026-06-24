package com.picap.mobile.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.picap.mobile.api.PicapClient
import com.picap.mobile.data.CaptureState
import com.picap.mobile.data.ConnectionState
import com.picap.mobile.data.DeviceStatus
import com.picap.mobile.data.PicapConfig
import com.picap.mobile.data.Reading
import com.picap.mobile.data.ScannedDevice
import com.picap.mobile.data.parseJsonArray
import com.picap.mobile.data.parseJsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

@SuppressLint("MissingPermission")
class PicapBleClient(
    context: Context,
    private val listener: PicapClient.Listener,
) : PicapClient {
    companion object {
        private val NAME_HINTS = listOf("picap", "picam")
    }

    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bluetoothGatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null
    private val pendingOperations = ArrayDeque<() -> Unit>()
    private var isOperationInFlight = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val record = result.scanRecord
            val name = device.name ?: record?.deviceName
            val hasService = record?.serviceUuids?.any { it.uuid == PicapUuids.SERVICE } == true
            val names = listOfNotNull(name, record?.deviceName)
            val matchesName = names.any { candidate ->
                NAME_HINTS.any { hint -> candidate.contains(hint, ignoreCase = true) }
            }
            if (!matchesName && !hasService) return

            listener.onScannedDevice(
                ScannedDevice(
                    name = name ?: PicapUuids.DEVICE_NAME,
                    address = device.address,
                ),
            )
        }

        override fun onScanFailed(errorCode: Int) {
            listener.onConnectionStateChanged(ConnectionState.DISCONNECTED)
            listener.onError("BLE scan failed with code $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                cleanupGatt()
                listener.onConnectionStateChanged(ConnectionState.DISCONNECTED)
                listener.onError(gattErrorMessage(status))
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    listener.onConnectionStateChanged(ConnectionState.CONNECTING)
                    gatt.requestMtu(517)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    cleanupGatt()
                    listener.onConnectionStateChanged(ConnectionState.DISCONNECTED)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.discoverServices()
            } else {
                listener.onError("MTU negotiation failed")
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("Service discovery failed")
                return
            }

            val service = gatt.getService(PicapUuids.SERVICE)
            if (service == null) {
                listener.onError("PiCap service not found on device")
                disconnect()
                return
            }

            listener.onConnectionStateChanged(ConnectionState.CONNECTED)
            enableNotifications(gatt)
            enqueue { readCharacteristic(PicapUuids.STATUS) }
            enqueue { readCharacteristic(PicapUuids.LATEST) }
            enqueue { readCharacteristic(PicapUuids.CONFIG) }
            enqueue {
                writeCharacteristic(
                    PicapUuids.HISTORY,
                    JSONObject()
                        .put("limit", 20)
                        .put("offset", 0)
                        .toString()
                        .toByteArray(Charsets.UTF_8),
                )
            }
            enqueue { readCharacteristic(PicapUuids.HISTORY) }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            try {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onError("Read failed for ${characteristic.uuid}")
                    return
                }
                handleCharacteristicPayload(characteristic.uuid, value)
            } catch (exc: Exception) {
                listener.onError(exc.message ?: "BLE read failed")
            } finally {
                completeOperation()
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            try {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onError("Read failed for ${characteristic.uuid}")
                    return
                }
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: byteArrayOf()
                handleCharacteristicPayload(characteristic.uuid, value)
            } catch (exc: Exception) {
                listener.onError(exc.message ?: "BLE read failed")
            } finally {
                completeOperation()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            try {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onError("Write failed for ${characteristic.uuid}")
                    return
                }
                if (characteristic.uuid == PicapUuids.HISTORY) {
                    enqueue { readCharacteristic(PicapUuids.HISTORY) }
                }
                if (characteristic.uuid == PicapUuids.CONFIG) {
                    mainHandler.postDelayed({
                        enqueue { readCharacteristic(PicapUuids.CONFIG) }
                    }, 400)
                }
            } finally {
                completeOperation()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            try {
                handleCharacteristicPayload(characteristic.uuid, value)
            } catch (exc: Exception) {
                listener.onError(exc.message ?: "BLE notification failed")
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            try {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: byteArrayOf()
                handleCharacteristicPayload(characteristic.uuid, value)
            } catch (exc: Exception) {
                listener.onError(exc.message ?: "BLE notification failed")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            completeOperation()
        }
    }

    fun startScan(): Unit {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onError("Bluetooth is not enabled")
            return
        }

        stopScan()
        listener.onConnectionStateChanged(ConnectionState.SCANNING)

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        adapter.bluetoothLeScanner.startScan(emptyList(), settings, scanCallback)
    }

    fun stopScan(): Unit {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        if (bluetoothGatt == null) {
            listener.onConnectionStateChanged(ConnectionState.DISCONNECTED)
        }
    }

    fun connect(address: String): Unit {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onError("Bluetooth is not enabled")
            return
        }

        stopScan()
        listener.onConnectionStateChanged(ConnectionState.CONNECTING)
        val device = adapter.getRemoteDevice(address)
        when (device.bondState) {
            BluetoothDevice.BOND_BONDING -> {
                listener.onConnectionStateChanged(ConnectionState.DISCONNECTED)
                listener.onError(
                    "Android is pairing in the background. Cancel pairing in Bluetooth settings, " +
                        "forget PiCap/PiCam, then use Scan in this app.",
                )
                return
            }
        }
        connectedDevice = device
        bluetoothGatt?.close()
        bluetoothGatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun gattErrorMessage(status: Int): String {
        return when (status) {
            8 -> "Connection timed out. Move closer to the Pi and try again."
            19 -> "The Pi disconnected. Restart PiCap on the Pi and try again."
            133 -> "BLE connection failed. Forget PiCap/PiCam in Android Bluetooth settings, " +
                "then connect using Scan in this app (do not pair in settings)."
            else -> "Connection failed (BLE status $status). Use Scan in this app, not Android Bluetooth settings."
        }
    }

    override fun disconnect() {
        stopScan()
        bluetoothGatt?.disconnect()
    }

    override fun refreshStatus() {
        enqueue { readCharacteristic(PicapUuids.STATUS) }
    }

    override fun refreshLatest() {
        enqueue { readCharacteristic(PicapUuids.LATEST) }
    }

    override fun refreshHistory(limit: Int, offset: Int) {
        enqueue {
            writeCharacteristic(
                PicapUuids.HISTORY,
                JSONObject()
                    .put("limit", limit)
                    .put("offset", offset)
                    .toString()
                    .toByteArray(Charsets.UTF_8),
            )
        }
    }

    override fun refreshConfig() {
        enqueue { readCharacteristic(PicapUuids.CONFIG) }
    }

    override fun updateConfig(patchJson: String) {
        enqueue {
            writeCharacteristic(
                PicapUuids.CONFIG,
                patchJson.toByteArray(Charsets.UTF_8),
            )
        }
    }

    override fun triggerCapture() {
        enqueue {
            writeCharacteristic(
                PicapUuids.CAPTURE,
                "capture".toByteArray(Charsets.UTF_8),
            )
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt) {
        listOf(PicapUuids.CAPTURE, PicapUuids.LATEST, PicapUuids.STATUS).forEach { uuid ->
            val characteristic = gatt.getService(PicapUuids.SERVICE)?.getCharacteristic(uuid)
                ?: return@forEach
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(PicapUuids.CLIENT_CONFIG_DESCRIPTOR)
            if (descriptor != null) {
                enqueue {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }
        }
    }

    private fun readCharacteristic(uuid: java.util.UUID) {
        val gatt = bluetoothGatt ?: return
        val characteristic = gatt.getService(PicapUuids.SERVICE)?.getCharacteristic(uuid) ?: return
        gatt.readCharacteristic(characteristic)
    }

    private fun writeCharacteristic(uuid: java.util.UUID, value: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val characteristic = gatt.getService(PicapUuids.SERVICE)?.getCharacteristic(uuid) ?: return
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun handleCharacteristicPayload(uuid: java.util.UUID, value: ByteArray) {
        when (uuid) {
            PicapUuids.CONFIG -> {
                val json = parseJsonObject(value)
                val error = json.optString("error").ifBlank { null }
                if (error != null) {
                    listener.onError(error)
                } else {
                    listener.onConfigUpdated(PicapConfig.fromJson(json))
                }
            }
            PicapUuids.STATUS -> listener.onStatusUpdated(DeviceStatus.fromJson(parseJsonObject(value)))
            PicapUuids.LATEST -> listener.onLatestReading(Reading.fromJson(parseJsonObject(value)))
            PicapUuids.HISTORY -> {
                val payload = value.toString(Charsets.UTF_8).trim()
                if (payload.startsWith("[")) {
                    listener.onHistoryUpdated(Reading.listFromJsonArray(parseJsonArray(value)))
                } else {
                    val objectPayload = parseJsonObject(value)
                    val error = objectPayload.optString("error").ifBlank { null }
                    if (error != null) {
                        listener.onError(error)
                    }
                }
            }
            PicapUuids.CAPTURE -> listener.onCaptureStateUpdated(
                CaptureState.fromJson(parseJsonObject(value)),
            )
        }
    }

    private fun enqueue(operation: () -> Unit) {
        pendingOperations.add(operation)
        runNextOperation()
    }

    private fun runNextOperation() {
        if (isOperationInFlight || pendingOperations.isEmpty()) return
        isOperationInFlight = true
        val operation = pendingOperations.removeFirst()
        mainHandler.post(operation)
    }

    private fun completeOperation() {
        isOperationInFlight = false
        runNextOperation()
    }

    private fun cleanupGatt() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectedDevice = null
        pendingOperations.clear()
        isOperationInFlight = false
    }
}
