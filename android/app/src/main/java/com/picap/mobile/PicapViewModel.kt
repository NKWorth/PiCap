package com.picap.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.picap.mobile.ble.PicapBleClient
import com.picap.mobile.data.CaptureState
import com.picap.mobile.data.ConnectionState
import com.picap.mobile.data.DeviceStatus
import com.picap.mobile.data.OcrConfig
import com.picap.mobile.data.PicapConfig
import com.picap.mobile.data.Reading
import com.picap.mobile.data.ScannedDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PicapUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val scannedDevices: List<ScannedDevice> = emptyList(),
    val connectedAddress: String? = null,
    val status: DeviceStatus? = null,
    val latestReading: Reading? = null,
    val history: List<Reading> = emptyList(),
    val captureState: CaptureState = CaptureState(status = "idle"),
    val config: PicapConfig? = null,
    val draftOcrConfig: OcrConfig = OcrConfig(),
    val configSaving: Boolean = false,
    val selectedTab: AppTab = AppTab.DASHBOARD,
    val errorMessage: String? = null,
)

enum class AppTab {
    DASHBOARD,
    SETTINGS,
}

class PicapViewModel(application: Application) : AndroidViewModel(application), PicapBleClient.Listener {
    private val _uiState = MutableStateFlow(PicapUiState())
    val uiState: StateFlow<PicapUiState> = _uiState.asStateFlow()

    private val bleClient = PicapBleClient(application, this)

    fun startScan() {
        _uiState.update {
            it.copy(
                scannedDevices = emptyList(),
                errorMessage = null,
            )
        }
        bleClient.startScan()
    }

    fun stopScan() {
        bleClient.stopScan()
    }

    fun connect(device: ScannedDevice) {
        _uiState.update {
            it.copy(
                connectedAddress = device.address,
                errorMessage = null,
            )
        }
        bleClient.connect(device.address)
    }

    fun disconnect() {
        bleClient.disconnect()
        _uiState.value = PicapUiState()
    }

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun refreshAll() {
        bleClient.refreshStatus()
        bleClient.refreshLatest()
        bleClient.refreshHistory()
        bleClient.refreshConfig()
    }

    fun refreshConfig() {
        bleClient.refreshConfig()
    }

    fun updateDraftOcrConfig(transform: (OcrConfig) -> OcrConfig) {
        _uiState.update { it.copy(draftOcrConfig = transform(it.draftOcrConfig)) }
    }

    fun saveOcrConfig() {
        val patch = _uiState.value.draftOcrConfig.toPatchJson().toString()
        _uiState.update { it.copy(configSaving = true, errorMessage = null) }
        bleClient.updateConfig(patch)
    }

    fun triggerCapture() {
        _uiState.update {
            it.copy(
                captureState = CaptureState(status = "capturing"),
                errorMessage = null,
            )
        }
        bleClient.triggerCapture()
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onConnectionStateChanged(state: ConnectionState) {
        _uiState.update { it.copy(connectionState = state) }
    }

    override fun onScannedDevice(device: ScannedDevice) {
        _uiState.update { current ->
            if (current.scannedDevices.any { it.address == device.address }) {
                current
            } else {
                current.copy(scannedDevices = current.scannedDevices + device)
            }
        }
    }

    override fun onStatusUpdated(status: DeviceStatus?) {
        _uiState.update { it.copy(status = status) }
    }

    override fun onLatestReading(reading: Reading?) {
        _uiState.update { it.copy(latestReading = reading) }
    }

    override fun onHistoryUpdated(history: List<Reading>) {
        _uiState.update { it.copy(history = history) }
    }

    override fun onCaptureStateUpdated(state: CaptureState) {
        _uiState.update { current ->
            current.copy(
                captureState = state,
                latestReading = state.result ?: current.latestReading,
            )
        }
        if (state.status == "complete") {
            bleClient.refreshHistory()
        }
    }

    override fun onConfigUpdated(config: PicapConfig?) {
        _uiState.update {
            it.copy(
                config = config,
                draftOcrConfig = config?.ocr ?: it.draftOcrConfig,
                configSaving = false,
            )
        }
    }

    override fun onError(message: String) {
        _uiState.update { it.copy(errorMessage = message, configSaving = false) }
    }

    override fun onCleared() {
        bleClient.disconnect()
        super.onCleared()
    }
}
