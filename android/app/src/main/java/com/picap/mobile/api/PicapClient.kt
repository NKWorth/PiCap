package com.picap.mobile.api

import android.graphics.Bitmap
import com.picap.mobile.data.AutoCalibrateResult
import com.picap.mobile.data.CameraControlsState
import com.picap.mobile.data.CaptureState
import com.picap.mobile.data.ConnectionState
import com.picap.mobile.data.DayReport
import com.picap.mobile.data.DeviceStatus
import com.picap.mobile.data.PicapConfig
import com.picap.mobile.data.Reading
import com.picap.mobile.data.ScannedDevice

interface PicapClient {
    interface Listener {
        fun onConnectionStateChanged(state: ConnectionState)
        fun onScannedDevice(device: ScannedDevice)
        fun onStatusUpdated(status: DeviceStatus?)
        fun onLatestReading(reading: Reading?)
        fun onHistoryUpdated(history: List<Reading>)
        fun onDayReportUpdated(report: DayReport?) {}
        fun onCaptureStateUpdated(state: CaptureState)
        fun onConfigUpdated(config: PicapConfig?)
        fun onError(message: String)
        fun onHttpLinkStateChanged(linking: Boolean, linked: Boolean, host: String?) {}
        fun onAutoCalibrateComplete(result: AutoCalibrateResult) {}
        fun onAutoCalibrateFailed(message: String) {}
        fun onBleCalibrationImageProgress(received: Int, total: Int, status: String) {}
        fun onBleCalibrationImageComplete(
            bitmap: Bitmap,
            width: Int,
            height: Int,
            sourceWidth: Int = 0,
            sourceHeight: Int = 0,
        ) {}
        fun onBleCalibrationImageFailed(message: String) {}
        fun onCameraControlsUpdated(state: CameraControlsState) {}
        fun onCameraControlsFailed(message: String) {}
    }

    fun disconnect()
    fun refreshStatus()
    fun refreshLatest()
    fun refreshHistory(limit: Int = 20, offset: Int = 0)
    fun refreshDayReport(date: String? = null)
    fun refreshConfig()
    fun updateConfig(patchJson: String)
    fun triggerCapture()
    fun requestCalibrationImage(action: String)
    fun refreshCameraControls()
    fun autoCalibrateRegions(source: String = "latest")
}
