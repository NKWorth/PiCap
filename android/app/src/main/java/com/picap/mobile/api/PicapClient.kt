package com.picap.mobile.api

import com.picap.mobile.data.CaptureState
import com.picap.mobile.data.ConnectionState
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
        fun onCaptureStateUpdated(state: CaptureState)
        fun onConfigUpdated(config: PicapConfig?)
        fun onError(message: String)
        fun onHttpLinkStateChanged(linking: Boolean, linked: Boolean, host: String?) {}
    }

    fun disconnect()
    fun refreshStatus()
    fun refreshLatest()
    fun refreshHistory(limit: Int = 20, offset: Int = 0)
    fun refreshConfig()
    fun updateConfig(patchJson: String)
    fun triggerCapture()
}
