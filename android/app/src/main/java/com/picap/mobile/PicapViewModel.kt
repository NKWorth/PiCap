package com.picap.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.picap.mobile.api.PicapClient
import com.picap.mobile.api.PicapHttpClient
import com.picap.mobile.ble.PicapBleClient
import com.picap.mobile.data.CaptureState
import com.picap.mobile.data.ConnectionState
import com.picap.mobile.data.ConnectionTransport
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
    val connectionTransport: ConnectionTransport? = null,
    val scannedDevices: List<ScannedDevice> = emptyList(),
    val connectedAddress: String? = null,
    val httpHost: String = "",
    val status: DeviceStatus? = null,
    val latestReading: Reading? = null,
    val history: List<Reading> = emptyList(),
    val captureState: CaptureState = CaptureState(status = "idle"),
    val config: PicapConfig? = null,
    val draftOcrConfig: OcrConfig = OcrConfig(),
    val configSaving: Boolean = false,
    val selectedTab: AppTab = AppTab.DASHBOARD,
    val livePreviewEnabled: Boolean = true,
    val previewTick: Long = 0L,
    val errorMessage: String? = null,
)

enum class AppTab {
    DASHBOARD,
    PREVIEW,
    SETTINGS,
}

class PicapViewModel(application: Application) : AndroidViewModel(application), PicapClient.Listener {
    private val _uiState = MutableStateFlow(PicapUiState())
    val uiState: StateFlow<PicapUiState> = _uiState.asStateFlow()

    private val bleClient = PicapBleClient(application, this)
    private val httpClient = PicapHttpClient(this)
    private var activeClient: PicapClient? = null

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
        httpClient.disconnect()
        activeClient = bleClient
        _uiState.update {
            it.copy(
                connectionTransport = ConnectionTransport.BLE,
                connectedAddress = device.address,
                errorMessage = null,
            )
        }
        bleClient.connect(device.address)
    }

    fun connectHttp(host: String = _uiState.value.httpHost) {
        val trimmed = host.trim()
        if (trimmed.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = "Enter the Pi IP address (run: bash scripts/start-picap.sh --status on the Pi)")
            }
            return
        }
        stopScan()
        bleClient.disconnect()
        activeClient = httpClient
        _uiState.update {
            it.copy(
                connectionTransport = ConnectionTransport.HTTP,
                httpHost = host,
                connectedAddress = host,
                errorMessage = null,
            )
        }
        httpClient.connect(host)
    }

    fun updateHttpHost(host: String) {
        _uiState.update { it.copy(httpHost = host) }
    }

    fun disconnect() {
        bleClient.disconnect()
        httpClient.disconnect()
        activeClient = null
        _uiState.value = PicapUiState()
    }

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun setLivePreviewEnabled(enabled: Boolean) {
        _uiState.update { it.copy(livePreviewEnabled = enabled) }
    }

    fun refreshPreviewFrame() {
        _uiState.update { it.copy(previewTick = System.currentTimeMillis()) }
    }

    fun previewBaseUrl(): String {
        val state = _uiState.value
        val host = when (state.connectionTransport) {
            ConnectionTransport.HTTP -> state.connectedAddress
            ConnectionTransport.BLE, null -> state.httpHost
        } ?: state.httpHost
        val trimmed = host.trim().removeSuffix("/")
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.isBlank() -> ""
            else -> "http://$trimmed"
        }
    }

    fun previewUrl(maxWidth: Int = 640): String {
        val base = previewBaseUrl()
        if (base.isBlank()) return ""
        val tick = _uiState.value.previewTick
        return "$base/api/preview?max_width=$maxWidth&quality=75&t=$tick"
    }

    fun captureImageUrl(imagePath: String?): String? {
        if (imagePath.isNullOrBlank()) return null
        val base = previewBaseUrl()
        if (base.isBlank()) return null
        val filename = imagePath.substringAfterLast('/')
        if (filename.isBlank()) return null
        return "$base/api/captures/$filename"
    }

    fun refreshAll() {
        activeClient?.refreshStatus()
        activeClient?.refreshLatest()
        activeClient?.refreshHistory()
        activeClient?.refreshConfig()
    }

    fun refreshConfig() {
        activeClient?.refreshConfig()
    }

    fun updateDraftOcrConfig(transform: (OcrConfig) -> OcrConfig) {
        _uiState.update { it.copy(draftOcrConfig = transform(it.draftOcrConfig)) }
    }

    fun saveOcrConfig() {
        val patch = _uiState.value.draftOcrConfig.toPatchJson().toString()
        _uiState.update { it.copy(configSaving = true, errorMessage = null) }
        activeClient?.updateConfig(patch)
    }

    fun triggerCapture() {
        _uiState.update {
            it.copy(
                captureState = CaptureState(status = "capturing"),
                errorMessage = null,
            )
        }
        activeClient?.triggerCapture()
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
            activeClient?.refreshHistory()
            refreshPreviewFrame()
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
        disconnect()
        super.onCleared()
    }
}
