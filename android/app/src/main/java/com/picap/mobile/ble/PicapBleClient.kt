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
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import com.picap.mobile.api.PicapClient
import com.picap.mobile.data.CaptureState
import com.picap.mobile.data.ConnectionState
import com.picap.mobile.data.DayReport
import com.picap.mobile.data.DeviceStatus
import com.picap.mobile.data.PicapConfig
import com.picap.mobile.data.Reading
import com.picap.mobile.data.ScannedDevice
import com.picap.mobile.data.parseJsonArray
import com.picap.mobile.data.parseJsonArrayOrNull
import com.picap.mobile.data.parseJsonObject
import com.picap.mobile.data.parseJsonObjectOrNull
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
        private const val DAY_REPORT_PAGE_SIZE = 12
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
    private var calibrationTransfer: CalibrationTransfer? = null
    private var calibrationImageSupported = false
    private var calibrationTransferActive = false
    private var dayReportSupported = false
    private var dayReportDate: String? = null
    private var dayReportOffset = 0
    private var dayReportSlotCount = 0
    private val dayReportSlots = mutableListOf<Reading>()
    private var operationTimeout: Runnable? = null

    private data class CalibrationTransfer(
        val byteSize: Int,
        val buffer: ByteArray,
        val width: Int,
        val height: Int,
        var received: Int = 0,
    )

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
            val serviceChars = gatt.getService(PicapUuids.SERVICE)
            calibrationImageSupported =
                serviceChars?.getCharacteristic(PicapUuids.CALIBRATION_IMAGE) != null
            dayReportSupported =
                serviceChars?.getCharacteristic(PicapUuids.DAY_REPORT) != null
            enableNotifications(gatt)
            enqueue { readCharacteristic(PicapUuids.STATUS) }
            enqueue { readCharacteristic(PicapUuids.LATEST) }
            enqueue { readCharacteristic(PicapUuids.CONFIG) }
            enqueue {
                writeCharacteristic(
                    PicapUuids.HISTORY,
                    JSONObject()
                        .put("limit", 5)
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
                    reportGattFailure("read", characteristic.uuid, status)
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
                    reportGattFailure("read", characteristic.uuid, status)
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
                    reportGattFailure("write", characteristic.uuid, status)
                    return
                }
                if (characteristic.uuid == PicapUuids.HISTORY) {
                    enqueue { readCharacteristic(PicapUuids.HISTORY) }
                }
                if (characteristic.uuid == PicapUuids.DAY_REPORT) {
                    enqueue { readCharacteristic(PicapUuids.DAY_REPORT) }
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

    override fun refreshDayReport(date: String?) {
        if (!dayReportSupported) {
            listener.onError(
                "This Pi does not support Bluetooth day reports yet. Update PiCap on the Pi, or connect WiFi.",
            )
            listener.onDayReportUpdated(null)
            return
        }
        dayReportDate = date?.trim()?.ifBlank { null }
        dayReportOffset = 0
        dayReportSlotCount = 0
        dayReportSlots.clear()
        enqueue { writeDayReportPage(offset = 0) }
    }

    private fun writeDayReportPage(offset: Int): Boolean {
        val body = JSONObject()
            .put("offset", offset)
            .put("limit", DAY_REPORT_PAGE_SIZE)
        dayReportDate?.let { body.put("date", it) }
        return writeCharacteristic(
            PicapUuids.DAY_REPORT,
            body.toString().toByteArray(Charsets.UTF_8),
        )
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

    override fun requestCalibrationImage(action: String) {
        if (!calibrationImageSupported) {
            listener.onBleCalibrationImageFailed(
                "This Pi does not support BLE calibration images yet. Update PiCap on the Pi.",
            )
            return
        }
        if (calibrationTransferActive) {
            return
        }
        calibrationTransfer = null
        calibrationTransferActive = true
        enqueue {
            writeCharacteristic(
                PicapUuids.CALIBRATION_IMAGE,
                JSONObject().put("action", action).toString().toByteArray(Charsets.UTF_8),
            )
        }
    }

    override fun refreshCameraControls() {
        enqueue { readCharacteristic(PicapUuids.CONFIG) }
    }

    override fun autoCalibrateRegions(source: String) {
        listener.onAutoCalibrateFailed("Auto-calibrate requires a WiFi HTTP connection")
    }

    private fun enableNotifications(gatt: BluetoothGatt) {
        val notifyUuids = buildList {
            add(PicapUuids.CAPTURE)
            add(PicapUuids.LATEST)
            add(PicapUuids.STATUS)
            if (calibrationImageSupported) {
                add(PicapUuids.CALIBRATION_IMAGE)
            }
        }
        notifyUuids.forEach { uuid ->
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

    private fun readCharacteristic(uuid: java.util.UUID): Boolean {
        val gatt = bluetoothGatt ?: return false
        val characteristic = gatt.getService(PicapUuids.SERVICE)?.getCharacteristic(uuid) ?: return false
        val started = gatt.readCharacteristic(characteristic)
        if (!started) {
            reportGattFailure("read", uuid, -1)
            completeOperation()
        }
        return started
    }

    private fun writeCharacteristic(uuid: java.util.UUID, value: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val characteristic = gatt.getService(PicapUuids.SERVICE)?.getCharacteristic(uuid) ?: return false
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val started = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            gatt.writeCharacteristic(characteristic)
        }
        if (!started) {
            reportGattFailure("write", uuid, -1)
            completeOperation()
        }
        return started
    }

    private fun reportGattFailure(operation: String, uuid: java.util.UUID, status: Int) {
        val label = characteristicLabel(uuid)
        if (uuid == PicapUuids.CALIBRATION_IMAGE) {
            calibrationTransferActive = false
            calibrationTransfer = null
            listener.onBleCalibrationImageFailed(
                "Bluetooth $operation failed for calibration image. Move closer and try Reload.",
            )
            return
        }
        if (calibrationTransferActive) {
            // Image transfer saturates the link; ignore transient failures for other characteristics.
            return
        }
        val detail = if (status >= 0) " (status $status)" else ""
        listener.onError("Bluetooth $operation failed for $label$detail. Try Refresh or reconnect.")
    }

    private fun characteristicLabel(uuid: java.util.UUID): String {
        return when (uuid) {
            PicapUuids.CONFIG -> "config"
            PicapUuids.CAPTURE -> "capture"
            PicapUuids.LATEST -> "latest reading"
            PicapUuids.HISTORY -> "history"
            PicapUuids.DAY_REPORT -> "day report"
            PicapUuids.STATUS -> "status"
            PicapUuids.CALIBRATION_IMAGE -> "calibration image"
            else -> "device"
        }
    }

    private fun handleCharacteristicPayload(uuid: java.util.UUID, value: ByteArray) {
        when (uuid) {
            PicapUuids.CONFIG -> {
                val json = parseJsonObjectOrNull(value)
                if (json == null) {
                    val text = value.toString(Charsets.UTF_8).trim()
                    val truncated = text.startsWith("{") && !text.endsWith("}")
                    listener.onError(
                        if (truncated) {
                            "Config is too large for Bluetooth. Connect WiFi on the Dashboard to load full settings."
                        } else {
                            "Could not parse config from Bluetooth"
                        },
                    )
                    return
                }
                val error = json.optString("error").ifBlank { null }
                if (error != null) {
                    listener.onError(error)
                } else {
                    listener.onConfigUpdated(PicapConfig.fromJson(json))
                }
            }
            PicapUuids.STATUS -> listener.onStatusUpdated(DeviceStatus.fromJson(parseJsonObject(value)))
            PicapUuids.LATEST -> parseJsonObjectOrNull(value)
                ?.let(Reading::fromJson)
                ?.let(listener::onLatestReading)
            PicapUuids.HISTORY -> {
                val payload = value.toString(Charsets.UTF_8).trim()
                when {
                    payload.startsWith("[") -> {
                        parseJsonArrayOrNull(value)
                            ?.let(Reading::listFromJsonArray)
                            ?.let(listener::onHistoryUpdated)
                    }
                    else -> {
                        val objectPayload = parseJsonObjectOrNull(value) ?: return
                        val error = objectPayload.optString("error").ifBlank { null }
                        if (error != null) {
                            listener.onError(error)
                        }
                    }
                }
            }
            PicapUuids.DAY_REPORT -> handleDayReportPayload(value)
            PicapUuids.CAPTURE -> parseJsonObjectOrNull(value)
                ?.let(CaptureState::fromJson)
                ?.let(listener::onCaptureStateUpdated)
            PicapUuids.CALIBRATION_IMAGE -> handleCalibrationImagePayload(value)
        }
    }

    private fun handleDayReportPayload(value: ByteArray) {
        val json = parseJsonObjectOrNull(value)
        if (json == null) {
            listener.onError("Could not parse day report from Bluetooth")
            listener.onDayReportUpdated(null)
            return
        }
        val error = json.optString("error").ifBlank { null }
        if (error != null) {
            listener.onError(error)
            listener.onDayReportUpdated(null)
            return
        }

        val page = DayReport.fromJson(json) ?: run {
            listener.onDayReportUpdated(null)
            return
        }
        if (dayReportOffset == 0) {
            dayReportSlots.clear()
            dayReportSlotCount = page.slotCount
            dayReportDate = page.date
        }
        dayReportSlots.addAll(page.slots)
        val hasMore = json.optBoolean("has_more", false)
        val nextOffset = json.optInt("offset", dayReportOffset) + json.optInt("limit", DAY_REPORT_PAGE_SIZE)
        if (hasMore) {
            dayReportOffset = nextOffset
            enqueue { writeDayReportPage(offset = nextOffset) }
            return
        }

        listener.onDayReportUpdated(
            DayReport(
                date = page.date,
                slotCount = dayReportSlotCount.takeIf { it > 0 } ?: dayReportSlots.size,
                slots = dayReportSlots.toList(),
            ),
        )
        dayReportOffset = 0
    }

    private fun handleCalibrationImagePayload(value: ByteArray) {
        if (value.isEmpty()) {
            return
        }
        if (value[0] == '{'.code.toByte()) {
            val json = parseJsonObject(value)
            when (json.optString("status")) {
                "loading" -> {
                    calibrationTransfer = null
                    calibrationTransferActive = true
                    listener.onBleCalibrationImageProgress(0, 0, "loading")
                }
                "transferring" -> {
                    val byteSize = json.optInt("byte_size")
                    if (byteSize <= 0) {
                        calibrationTransferActive = false
                        listener.onBleCalibrationImageFailed("Invalid BLE image transfer size")
                        return
                    }
                    calibrationTransfer = CalibrationTransfer(
                        byteSize = byteSize,
                        buffer = ByteArray(byteSize),
                        width = json.optInt("image_width"),
                        height = json.optInt("image_height"),
                    )
                    calibrationTransferActive = true
                    listener.onBleCalibrationImageProgress(0, byteSize, "transferring")
                }
                "complete" -> {
                    val transfer = calibrationTransfer
                    calibrationTransfer = null
                    calibrationTransferActive = false
                    if (transfer == null || transfer.received < transfer.byteSize) {
                        listener.onBleCalibrationImageFailed("Incomplete BLE image transfer")
                        return
                    }
                    val bitmap = BitmapFactory.decodeByteArray(transfer.buffer, 0, transfer.buffer.size)
                        ?: run {
                            listener.onBleCalibrationImageFailed("Could not decode calibration image")
                            return
                        }
                    listener.onBleCalibrationImageComplete(bitmap, transfer.width, transfer.height)
                }
                "cancelled" -> {
                    calibrationTransfer = null
                    calibrationTransferActive = false
                    listener.onBleCalibrationImageFailed("Calibration image transfer cancelled")
                }
                "error" -> {
                    calibrationTransfer = null
                    calibrationTransferActive = false
                    listener.onBleCalibrationImageFailed(
                        json.optString("message", "Calibration image transfer failed"),
                    )
                }
            }
            return
        }

        val transfer = calibrationTransfer ?: return
        val remaining = transfer.byteSize - transfer.received
        if (remaining <= 0) {
            return
        }
        val toCopy = minOf(value.size, remaining)
        System.arraycopy(value, 0, transfer.buffer, transfer.received, toCopy)
        transfer.received += toCopy
        listener.onBleCalibrationImageProgress(transfer.received, transfer.byteSize, "transferring")
    }

    private fun enqueue(operation: () -> Unit) {
        pendingOperations.add(operation)
        runNextOperation()
    }

    private fun runNextOperation() {
        if (isOperationInFlight || pendingOperations.isEmpty()) return
        isOperationInFlight = true
        val operation = pendingOperations.removeFirst()
        scheduleOperationTimeout()
        mainHandler.post(operation)
    }

    private fun completeOperation() {
        clearOperationTimeout()
        isOperationInFlight = false
        runNextOperation()
    }

    private fun scheduleOperationTimeout() {
        clearOperationTimeout()
        val timeout = Runnable {
            if (!isOperationInFlight) {
                return@Runnable
            }
            isOperationInFlight = false
            if (calibrationTransferActive) {
                // Keep waiting for image notifications; just unblock the queue.
                runNextOperation()
                return@Runnable
            }
            listener.onError("Bluetooth operation timed out. Try Refresh or reconnect.")
            runNextOperation()
        }
        operationTimeout = timeout
        mainHandler.postDelayed(timeout, 8_000)
    }

    private fun clearOperationTimeout() {
        operationTimeout?.let { mainHandler.removeCallbacks(it) }
        operationTimeout = null
    }

    private fun cleanupGatt() {
        clearOperationTimeout()
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectedDevice = null
        pendingOperations.clear()
        isOperationInFlight = false
        calibrationTransfer = null
        calibrationTransferActive = false
        calibrationImageSupported = false
    }
}
