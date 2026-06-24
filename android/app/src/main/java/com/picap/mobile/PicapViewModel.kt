package com.picap.mobile

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import com.picap.mobile.api.PicapClient
import com.picap.mobile.api.PicapHttpClient
import com.picap.mobile.ble.PicapBleClient
import com.picap.mobile.data.CaptureState
import com.picap.mobile.data.ConnectionState
import com.picap.mobile.data.CaptureRegion
import com.picap.mobile.data.ConnectionTransport
import com.picap.mobile.data.DeviceStatus
import com.picap.mobile.data.OcrConfig
import com.picap.mobile.data.PicapConfig
import com.picap.mobile.data.Reading
import com.picap.mobile.data.ScannedDevice
import com.picap.mobile.data.regionsConfigPatch
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
    val draftRegions: List<CaptureRegion> = emptyList(),
    val selectedRegionIndex: Int = 0,
    val calibrationImageWidth: Int = 0,
    val calibrationImageHeight: Int = 0,
    val regionsSaving: Boolean = false,
    val regionsDirty: Boolean = false,
    val configSaving: Boolean = false,
    val selectedTab: AppTab = AppTab.DASHBOARD,
    val livePreviewEnabled: Boolean = true,
    val previewTick: Long = 0L,
    val calibrationImageTick: Long = 0L,
    val httpLinked: Boolean = false,
    val httpLinking: Boolean = false,
    val errorMessage: String? = null,
)

enum class AppTab {
    DASHBOARD,
    PREVIEW,
    REGIONS,
    SETTINGS,
}

class PicapViewModel(application: Application) : AndroidViewModel(application), PicapClient.Listener {
    private val _uiState = MutableStateFlow(PicapUiState())
    val uiState: StateFlow<PicapUiState> = _uiState.asStateFlow()

    private val bleClient = PicapBleClient(application, this)
    private val httpClient = PicapHttpClient(this)
    private var activeClient: PicapClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var configSaveTimeout: Runnable? = null

    private fun clientForConfig(): PicapClient? {
        val state = _uiState.value
        if (state.httpLinked) {
            return httpClient
        }
        return activeClient
    }

    private fun beginConfigSave(regions: Boolean) {
        clearConfigSaveTimeout()
        _uiState.update {
            if (regions) {
                it.copy(regionsSaving = true, errorMessage = null)
            } else {
                it.copy(configSaving = true, errorMessage = null)
            }
        }
        val timeout = Runnable {
            _uiState.update { state ->
                val timedOut = if (regions) state.regionsSaving else state.configSaving
                if (!timedOut) {
                    state
                } else {
                    state.copy(
                        regionsSaving = false,
                        configSaving = false,
                        errorMessage = "Save timed out. Check Pi connection and try again.",
                    )
                }
            }
        }
        configSaveTimeout = timeout
        mainHandler.postDelayed(timeout, 15_000)
    }

    private fun clearConfigSaveTimeout() {
        configSaveTimeout?.let { mainHandler.removeCallbacks(it) }
        configSaveTimeout = null
    }

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
        httpClient.unlinkHttp()
        activeClient = bleClient
        _uiState.update {
            it.copy(
                connectionTransport = ConnectionTransport.BLE,
                connectedAddress = device.address,
                httpLinked = false,
                httpLinking = false,
                errorMessage = null,
            )
        }
        bleClient.connect(device.address)
    }

    fun connectHttp(host: String = _uiState.value.httpHost) {
        val trimmed = host.trim()
        if (trimmed.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = "Enter the Pi IP address or connect via BLE first to discover it.")
            }
            return
        }
        stopScan()
        bleClient.disconnect()
        httpClient.unlinkHttp()
        activeClient = httpClient
        _uiState.update {
            it.copy(
                connectionTransport = ConnectionTransport.HTTP,
                httpHost = trimmed,
                connectedAddress = trimmed,
                httpLinked = false,
                httpLinking = false,
                errorMessage = null,
            )
        }
        httpClient.connect(trimmed)
    }

    fun linkHttp(host: String = _uiState.value.httpHost) {
        val state = _uiState.value
        if (state.connectionTransport != ConnectionTransport.BLE) {
            connectHttp(host)
            return
        }
        val trimmed = host.trim().ifBlank { state.status?.httpHostPort().orEmpty() }.trim()
        if (trimmed.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = "No WiFi address from the Pi yet. Tap Refresh on Dashboard or enter the IP.")
            }
            return
        }
        _uiState.update {
            it.copy(httpHost = trimmed, httpLinking = true, errorMessage = null)
        }
        httpClient.linkHttp(trimmed)
    }

    fun unlinkHttp() {
        httpClient.unlinkHttp()
        _uiState.update { it.copy(httpLinked = false, httpLinking = false) }
    }

    fun updateHttpHost(host: String) {
        _uiState.update { it.copy(httpHost = host) }
    }

    fun disconnect() {
        val httpPrimary = _uiState.value.connectionTransport == ConnectionTransport.HTTP
        bleClient.disconnect()
        if (httpPrimary) {
            httpClient.disconnect()
        } else {
            httpClient.unlinkHttp()
        }
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
        if (!state.httpLinked && state.connectionTransport != ConnectionTransport.HTTP) {
            return ""
        }
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

    fun loadCalibrationMetadata() {
        activeClient?.refreshLatest()
    }

    fun refreshCalibrationImage() {
        activeClient?.refreshLatest()
        _uiState.update { it.copy(calibrationImageTick = System.currentTimeMillis()) }
    }

    fun calibrationCaptureUrl(): String? {
        val state = _uiState.value
        val baseUrl = captureImageUrl(state.latestReading?.imagePath) ?: return null
        return "$baseUrl?t=${state.calibrationImageTick}"
    }

    fun calibrationImageAvailable(): Boolean = previewBaseUrl().isNotBlank()

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

    fun updateDraftRegions(regions: List<CaptureRegion>) {
        _uiState.update { it.copy(draftRegions = regions, regionsDirty = true) }
    }

    fun selectRegion(index: Int) {
        _uiState.update {
            it.copy(
                selectedRegionIndex = index.coerceIn(0, 1),
                regionsDirty = true,
            )
        }
    }

    fun beginRegionEdit() {
        _uiState.update { state ->
            if (state.regionsDirty) state else state.copy(regionsDirty = true)
        }
    }

    fun onCalibrationImageLoaded(width: Int, height: Int) {
        _uiState.update { state ->
            if (state.calibrationImageWidth == width && state.calibrationImageHeight == height) {
                return@update state
            }
            if (state.regionsDirty) {
                return@update state.copy(
                    calibrationImageWidth = width,
                    calibrationImageHeight = height,
                )
            }
            val regions = when {
                state.draftRegions.isEmpty() ->
                    CaptureRegion.normalizeOtwRegions(emptyList(), width, height)
                state.draftRegions.all { it.width <= 0 || it.height <= 0 } ->
                    CaptureRegion.normalizeOtwRegions(emptyList(), width, height)
                else ->
                    CaptureRegion.normalizeOtwRegions(state.draftRegions, width, height)
            }
            state.copy(
                calibrationImageWidth = width,
                calibrationImageHeight = height,
                draftRegions = regions,
            )
        }
    }

    fun resetRegionDefaults() {
        val state = _uiState.value
        val width = state.calibrationImageWidth.takeIf { it > 0 } ?: state.config?.cameraWidth ?: 1920
        val height = state.calibrationImageHeight.takeIf { it > 0 } ?: state.config?.cameraHeight ?: 1080
        _uiState.update {
            it.copy(
                draftRegions = CaptureRegion.otwDefaults(width, height),
                regionsDirty = true,
            )
        }
    }

    fun reloadRegionsFromPi() {
        val state = _uiState.value
        val config = state.config ?: return
        val width = state.calibrationImageWidth.takeIf { it > 0 } ?: config.cameraWidth ?: 1920
        val height = state.calibrationImageHeight.takeIf { it > 0 } ?: config.cameraHeight ?: 1080
        _uiState.update {
            it.copy(
                draftRegions = CaptureRegion.normalizeOtwRegions(config.regions, width, height),
                regionsDirty = false,
            )
        }
    }

    fun saveRegions() {
        val state = _uiState.value
        if (state.draftRegions.size < 2) {
            _uiState.update { it.copy(errorMessage = "Both 15 Min Avg regions must be configured") }
            return
        }
        val patch = regionsConfigPatch(state.draftRegions, state.draftOcrConfig).toString()
        beginConfigSave(regions = true)
        clientForConfig()?.updateConfig(patch)
            ?: run {
                clearConfigSaveTimeout()
                _uiState.update {
                    it.copy(
                        regionsSaving = false,
                        errorMessage = "Not connected to the Pi",
                    )
                }
            }
    }

    fun saveOcrConfig() {
        val patch = _uiState.value.draftOcrConfig.toPatchJson().toString()
        beginConfigSave(regions = false)
        clientForConfig()?.updateConfig(patch)
            ?: run {
                clearConfigSaveTimeout()
                _uiState.update {
                    it.copy(
                        configSaving = false,
                        errorMessage = "Not connected to the Pi",
                    )
                }
            }
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
        _uiState.update { state ->
            val discoveredHost = status?.httpHostPort()
            val httpHost = when {
                !discoveredHost.isNullOrBlank() -> discoveredHost
                else -> state.httpHost
            }
            state.copy(status = status, httpHost = httpHost)
        }
    }

    override fun onHttpLinkStateChanged(linking: Boolean, linked: Boolean, host: String?) {
        _uiState.update { state ->
            state.copy(
                httpLinking = linking,
                httpLinked = linked,
                httpHost = host?.takeIf { it.isNotBlank() } ?: state.httpHost,
            )
        }
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
            _uiState.update { it.copy(calibrationImageTick = System.currentTimeMillis()) }
        }
    }

    override fun onConfigUpdated(config: PicapConfig?) {
        clearConfigSaveTimeout()
        _uiState.update { state ->
            val width = state.calibrationImageWidth.takeIf { it > 0 }
                ?: config?.cameraWidth
                ?: 1920
            val height = state.calibrationImageHeight.takeIf { it > 0 }
                ?: config?.cameraHeight
                ?: 1080
            val regions = when {
                state.regionsDirty -> state.draftRegions
                config?.regions?.isNotEmpty() == true ->
                    CaptureRegion.normalizeOtwRegions(config.regions, width, height)
                state.draftRegions.isNotEmpty() -> state.draftRegions
                else -> CaptureRegion.normalizeOtwRegions(emptyList(), width, height)
            }
            state.copy(
                config = config,
                draftOcrConfig = config?.ocr ?: state.draftOcrConfig,
                draftRegions = regions,
                configSaving = false,
                regionsSaving = false,
                regionsDirty = if (state.regionsSaving) false else state.regionsDirty,
            )
        }
    }

    override fun onError(message: String) {
        clearConfigSaveTimeout()
        _uiState.update {
            it.copy(
                errorMessage = message,
                configSaving = false,
                regionsSaving = false,
                httpLinking = false,
            )
        }
    }

    override fun onCleared() {
        clearConfigSaveTimeout()
        disconnect()
        super.onCleared()
    }
}
