package com.picap.mobile.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.picap.mobile.AppTab
import com.picap.mobile.PicapViewModel
import com.picap.mobile.data.CameraControl
import com.picap.mobile.data.CameraControlsState
import com.picap.mobile.data.ConnectionState
import com.picap.mobile.data.ConnectionTransport
import com.picap.mobile.data.OcrConfig
import com.picap.mobile.data.Reading
import com.picap.mobile.data.RegionReading
import com.picap.mobile.data.ScannedDevice
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiCapApp(viewModel: PicapViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val permissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) {
            viewModel.startScan()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        val message = uiState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearError()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("PiCap")
                        Text(
                            connectionLabel(
                                uiState.connectionState,
                                uiState.connectionTransport,
                                uiState.connectedAddress,
                                uiState.httpHost,
                                uiState.httpLinked,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
                actions = {
                    if (uiState.connectionState == ConnectionState.CONNECTED) {
                        OutlinedButton(
                            onClick = viewModel::refreshAll,
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Text("Refresh", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        when (uiState.connectionState) {
            ConnectionState.CONNECTED -> Column(modifier = Modifier.padding(padding)) {
                TabRow(selectedTabIndex = uiState.selectedTab.ordinal) {
                    Tab(
                        selected = uiState.selectedTab == AppTab.DASHBOARD,
                        onClick = { viewModel.selectTab(AppTab.DASHBOARD) },
                        text = { Text("Dashboard") },
                    )
                    Tab(
                        selected = uiState.selectedTab == AppTab.PREVIEW,
                        onClick = { viewModel.selectTab(AppTab.PREVIEW) },
                        text = { Text("Preview") },
                        icon = { Icon(Icons.Default.Visibility, contentDescription = null) },
                    )
                    Tab(
                        selected = uiState.selectedTab == AppTab.REGIONS,
                        onClick = { viewModel.selectTab(AppTab.REGIONS) },
                        text = { Text("Regions") },
                        icon = { Icon(Icons.Default.CropFree, contentDescription = null) },
                    )
                    Tab(
                        selected = uiState.selectedTab == AppTab.CAMERA,
                        onClick = { viewModel.selectTab(AppTab.CAMERA) },
                        text = { Text("Camera") },
                        icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                    )
                    Tab(
                        selected = uiState.selectedTab == AppTab.SETTINGS,
                        onClick = { viewModel.selectTab(AppTab.SETTINGS) },
                        text = { Text("Settings") },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    )
                }
                when (uiState.selectedTab) {
                    AppTab.DASHBOARD -> DashboardScreen(
                        latestReading = uiState.latestReading,
                        history = uiState.history,
                        statusReady = uiState.status?.ready == true,
                        lastError = uiState.status?.lastError,
                        cameraSource = uiState.status?.cameraSource,
                        ocrMode = uiState.status?.ocrMode,
                        httpUrl = uiState.status?.httpUrl,
                        connectionTransport = uiState.connectionTransport,
                        httpHost = uiState.httpHost,
                        httpLinked = uiState.httpLinked,
                        httpLinking = uiState.httpLinking,
                        captureBusy = uiState.captureState.isBusy,
                        onCapture = viewModel::triggerCapture,
                        onDisconnect = viewModel::disconnect,
                        onHttpHostChange = viewModel::updateHttpHost,
                        onLinkHttp = viewModel::linkHttp,
                        onUnlinkHttp = viewModel::unlinkHttp,
                        onRefreshStatus = { viewModel.refreshAll() },
                    )
                    AppTab.PREVIEW -> PreviewScreen(
                        previewUrl = viewModel.previewUrl(),
                        previewAvailable = viewModel.previewBaseUrl().isNotBlank(),
                        httpHost = uiState.httpHost,
                        httpLinked = uiState.httpLinked,
                        connectionTransport = uiState.connectionTransport,
                        cameraReady = uiState.status?.ready == true,
                        livePreviewEnabled = uiState.livePreviewEnabled,
                        lastCaptureImageUrl = viewModel.captureImageUrl(uiState.latestReading?.imagePath),
                        onLivePreviewChange = viewModel::setLivePreviewEnabled,
                        onRefreshFrame = viewModel::refreshPreviewFrame,
                    )
                    AppTab.REGIONS -> {
                        LaunchedEffect(uiState.selectedTab) {
                            if (uiState.selectedTab == AppTab.REGIONS) {
                                viewModel.loadCalibrationMetadata()
                            }
                        }
                        RegionCalibrationScreen(
                            captureImageUrl = viewModel.calibrationCaptureUrl(),
                            calibrationBitmap = uiState.bleCalibrationBitmap,
                            imageAvailable = viewModel.calibrationImageAvailable(),
                            bleImageLoading = uiState.bleCalibrationLoading,
                            bleImageProgress = uiState.bleCalibrationProgress,
                            httpHost = uiState.httpHost,
                            httpLinked = uiState.httpLinked,
                            connectionTransport = uiState.connectionTransport,
                            regions = uiState.draftRegions,
                            regionsLocked = uiState.regionsDirty,
                            selectedRegionIndex = uiState.selectedRegionIndex,
                            imageWidth = uiState.calibrationImageWidth,
                            imageHeight = uiState.calibrationImageHeight,
                            saving = uiState.regionsSaving,
                            captureBusy = uiState.captureState.isBusy || uiState.bleCalibrationLoading,
                            autoCalibrating = uiState.autoCalibrating,
                            captureState = uiState.captureState,
                            testCaptureImageUrl = viewModel.testCaptureImageUrl(
                                uiState.captureState.result?.imagePath,
                            ),
                            onImageLoaded = viewModel::onCalibrationImageLoaded,
                            onSelectRegion = viewModel::selectRegion,
                            onBeginRegionEdit = viewModel::beginRegionEdit,
                            onRegionsChange = viewModel::updateDraftRegions,
                            onResetDefaults = viewModel::resetRegionDefaults,
                            onRefreshImage = viewModel::refreshCalibrationImage,
                            onNewCapture = viewModel::triggerCalibrationCapture,
                            onSave = viewModel::saveRegions,
                            onTestCapture = viewModel::testRegionsCapture,
                            onAutoCalibrate = viewModel::autoCalibrateRegions,
                        )
                    }
                    AppTab.CAMERA -> {
                        LaunchedEffect(uiState.selectedTab) {
                            if (uiState.selectedTab == AppTab.CAMERA) {
                                viewModel.refreshCameraControls()
                            }
                        }
                        CameraScreen(
                            controls = uiState.cameraControls,
                            draftValues = uiState.draftV4l2Controls,
                            draftPixelFormat = uiState.draftPixelFormat,
                            draftDevicePath = uiState.draftCameraDevicePath,
                            loading = uiState.cameraControlsLoading,
                            saving = uiState.cameraSaving,
                            cameraSource = uiState.status?.cameraSource ?: uiState.config?.cameraSource,
                            onDraftValueChange = viewModel::updateDraftV4l2Control,
                            onPixelFormatChange = viewModel::updateDraftPixelFormat,
                            onDeviceSelected = viewModel::updateDraftCameraDevice,
                            onReload = viewModel::refreshCameraControls,
                            onSave = viewModel::saveCameraControls,
                        )
                    }
                    AppTab.SETTINGS -> SettingsScreen(
                        draft = uiState.draftOcrConfig,
                        saving = uiState.configSaving,
                        onDraftChange = viewModel::updateDraftOcrConfig,
                        onReload = viewModel::refreshConfig,
                        onSave = viewModel::saveOcrConfig,
                    )
                }
            }

            else -> ScanScreen(
                modifier = Modifier.padding(padding),
                connectionState = uiState.connectionState,
                devices = uiState.scannedDevices,
                httpHost = uiState.httpHost,
                onHttpHostChange = viewModel::updateHttpHost,
                onConnectHttp = viewModel::connectHttp,
                onRequestPermissionsAndScan = { permissionLauncher.launch(permissions) },
                onConnect = viewModel::connect,
                onStopScan = viewModel::stopScan,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanScreen(
    modifier: Modifier = Modifier,
    connectionState: ConnectionState,
    devices: List<ScannedDevice>,
    httpHost: String,
    onHttpHostChange: (String) -> Unit,
    onConnectHttp: () -> Unit,
    onRequestPermissionsAndScan: () -> Unit,
    onConnect: (ScannedDevice) -> Unit,
    onStopScan: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Connect to Raspberry Pi",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Use Scan in this app to connect. Do not pair PiCap in Android Bluetooth settings — that causes PIN prompts and pairing failures.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onRequestPermissionsAndScan) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null)
                        Text("Scan", modifier = Modifier.padding(start = 8.dp))
                    }
                    if (connectionState == ConnectionState.SCANNING) {
                        OutlinedButton(onClick = onStopScan) {
                            Text("Stop")
                        }
                    }
                }
                if (connectionState == ConnectionState.SCANNING) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Text("Scanning for PiCap devices...")
                    }
                }
                if (connectionState == ConnectionState.CONNECTING) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Text("Connecting...")
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Connect via WiFi",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Connect via Scan — the Pi WiFi address is filled in automatically over BLE for Preview and Regions.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = httpHost,
                    onValueChange = onHttpHostChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Pi address") },
                    placeholder = { Text("10.0.0.17:8080") },
                    singleLine = true,
                )
                Button(
                    onClick = onConnectHttp,
                    enabled = connectionState != ConnectionState.CONNECTING,
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null)
                    Text("Connect via HTTP", modifier = Modifier.padding(start = 8.dp))
                }
                if (connectionState == ConnectionState.CONNECTING) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Text("Connecting...")
                    }
                }
            }
        }

        if (devices.isNotEmpty()) {
            Text(
                text = "Nearby devices",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(devices, key = { it.address }) { device ->
                    Card(onClick = { onConnect(device) }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(device.name, fontWeight = FontWeight.Medium)
                                Text(device.address, style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Default.BluetoothConnected, contentDescription = null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    latestReading: Reading?,
    history: List<Reading>,
    statusReady: Boolean,
    lastError: String?,
    cameraSource: String?,
    ocrMode: String?,
    httpUrl: String?,
    connectionTransport: ConnectionTransport?,
    httpHost: String,
    httpLinked: Boolean,
    httpLinking: Boolean,
    captureBusy: Boolean,
    onCapture: () -> Unit,
    onDisconnect: () -> Unit,
    onHttpHostChange: (String) -> Unit,
    onLinkHttp: () -> Unit,
    onUnlinkHttp: () -> Unit,
    onRefreshStatus: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Device status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Ready: ${if (statusReady) "Yes" else "No"}")
                    cameraSource?.let { Text("Camera: $it") }
                    ocrMode?.let { Text("OCR mode: $it") }
                    httpUrl?.let { Text("WiFi URL: $it") }
                    lastError?.let {
                        Text("Last error: $it", color = MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onCapture, enabled = !captureBusy) {
                            if (captureBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .height(18.dp)
                                        .padding(end = 8.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Default.CameraAlt, contentDescription = null)
                            }
                            Text(
                                if (captureBusy) "Capturing..." else "Capture",
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        OutlinedButton(onClick = onDisconnect) {
                            Text("Disconnect")
                        }
                    }
                }
            }
        }

        if (connectionTransport == ConnectionTransport.BLE) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "WiFi connection",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (httpLinked) {
                                "WiFi is connected for Preview and Regions. BLE still handles capture and settings."
                            } else {
                                "Connect WiFi in addition to Bluetooth for camera preview and region calibration."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        httpUrl?.let {
                            Text("From Pi: $it", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(
                            value = httpHost,
                            onValueChange = onHttpHostChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Pi WiFi address") },
                            placeholder = { Text("10.0.0.17:8080") },
                            singleLine = true,
                            enabled = !httpLinked && !httpLinking,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (httpLinked) {
                                OutlinedButton(onClick = onUnlinkHttp) {
                                    Text("Disconnect WiFi")
                                }
                            } else {
                                Button(
                                    onClick = onLinkHttp,
                                    enabled = !httpLinking && httpHost.isNotBlank(),
                                ) {
                                    if (httpLinking) {
                                        CircularProgressIndicator(
                                            modifier = Modifier
                                                .height(18.dp)
                                                .padding(end = 8.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(Icons.Default.Wifi, contentDescription = null)
                                    }
                                    Text(
                                        if (httpLinking) "Connecting..." else "Connect WiFi",
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                            OutlinedButton(onClick = onRefreshStatus, enabled = !httpLinking) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Text("Refresh", modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                }
            }
        }

        item {
            ReadingCard(title = "Latest reading", reading = latestReading)
        }

        item {
            Text("History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }

        if (history.isEmpty()) {
            item {
                Text("No stored readings yet.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            items(history, key = { "${it.id}-${it.capturedAt}" }) { reading ->
                ReadingCard(
                    title = formatCaptureTimestamp(reading.capturedAt),
                    reading = reading,
                    showCapturedAt = false,
                )
            }
        }
    }
}

@Composable
private fun PreviewScreen(
    previewUrl: String,
    previewAvailable: Boolean,
    httpHost: String,
    httpLinked: Boolean,
    connectionTransport: ConnectionTransport?,
    cameraReady: Boolean,
    livePreviewEnabled: Boolean,
    lastCaptureImageUrl: String?,
    onLivePreviewChange: (Boolean) -> Unit,
    onRefreshFrame: () -> Unit,
) {
    val context = LocalContext.current

    LaunchedEffect(livePreviewEnabled, previewAvailable) {
        if (!livePreviewEnabled || !previewAvailable) return@LaunchedEffect
        onRefreshFrame()
        while (true) {
            delay(1000)
            onRefreshFrame()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Camera preview",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!previewAvailable) {
                        Text(
                            text = if (connectionTransport == ConnectionTransport.BLE && !httpLinked) {
                                "On Dashboard, tap Connect WiFi to enable preview (address: ${httpHost.ifBlank { "not set" }})."
                            } else {
                                "WiFi preview is not available."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        if (connectionTransport == ConnectionTransport.BLE && httpLinked) {
                            Text(
                                text = "Preview over WiFi at $httpHost · control via BLE.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            text = if (cameraReady) "Camera ready" else "Camera may be unavailable on the Pi",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = livePreviewEnabled,
                                onClick = { onLivePreviewChange(!livePreviewEnabled) },
                                label = { Text("Live refresh") },
                            )
                            OutlinedButton(onClick = onRefreshFrame) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Text("Refresh", modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                }
            }
        }

        if (previewAvailable && previewUrl.isNotBlank()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(previewUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Pi camera preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }

        if (!lastCaptureImageUrl.isNullOrBlank()) {
            item {
                Text(
                    text = "Last capture",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(lastCaptureImageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Last captured image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    draft: OcrConfig,
    saving: Boolean,
    onDraftChange: ((OcrConfig) -> OcrConfig) -> Unit,
    onReload: () -> Unit,
    onSave: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("OCR configuration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Tune sharpening and contrast if MM:SS times are missed. Fixed regions mode uses the Regions tab boxes.",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Text("Detection mode", fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = draft.mode == "auto",
                            onClick = { onDraftChange { it.copy(mode = "auto") } },
                            label = { Text("Auto") },
                        )
                        FilterChip(
                            selected = draft.mode == "regions",
                            onClick = { onDraftChange { it.copy(mode = "regions") } },
                            label = { Text("Fixed regions") },
                        )
                    }

                    Text("Min confidence: ${draft.minConfidence}%", fontWeight = FontWeight.Medium)
                    Slider(
                        value = draft.minConfidence.toFloat(),
                        onValueChange = { value ->
                            onDraftChange { it.copy(minConfidence = value.roundToInt()) }
                        },
                        valueRange = 30f..95f,
                        steps = 12,
                    )

                    Text("Min digits: ${draft.minDigits}", fontWeight = FontWeight.Medium)
                    Slider(
                        value = draft.minDigits.toFloat(),
                        onValueChange = { value ->
                            onDraftChange { it.copy(minDigits = value.roundToInt()) }
                        },
                        valueRange = 1f..6f,
                        steps = 4,
                    )

                    Text("Upscale factor: ${"%.1f".format(draft.upscaleFactor)}", fontWeight = FontWeight.Medium)
                    Slider(
                        value = draft.upscaleFactor.toFloat(),
                        onValueChange = { value ->
                            onDraftChange { it.copy(upscaleFactor = value.toDouble()) }
                        },
                        valueRange = 1f..5f,
                        steps = 7,
                    )

                    Text("Sharpen: ${"%.1f".format(draft.sharpen)}", fontWeight = FontWeight.Medium)
                    Slider(
                        value = draft.sharpen.toFloat(),
                        onValueChange = { value ->
                            onDraftChange { it.copy(sharpen = value.toDouble()) }
                        },
                        valueRange = 0f..2.5f,
                        steps = 5,
                    )

                    Text("Contrast: ${"%.1f".format(draft.contrast)}", fontWeight = FontWeight.Medium)
                    Slider(
                        value = draft.contrast.toFloat(),
                        onValueChange = { value ->
                            onDraftChange { it.copy(contrast = value.toDouble()) }
                        },
                        valueRange = 1f..4f,
                        steps = 6,
                    )

                    Text("Threshold", fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("otsu", "adaptive", "none").forEach { mode ->
                            FilterChip(
                                selected = draft.threshold == mode,
                                onClick = { onDraftChange { it.copy(threshold = mode) } },
                                label = { Text(mode.replaceFirstChar { it.titlecase() }) },
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onSave, enabled = !saving) {
                            if (saving) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .height(18.dp)
                                        .padding(end = 8.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            Text(if (saving) "Saving..." else "Save to Pi")
                        }
                        OutlinedButton(onClick = onReload, enabled = !saving) {
                            Text("Reload")
                        }
                    }
                }
            }
        }
    }
}

private val CAMERA_CONTROL_ORDER = listOf(
    "brightness",
    "contrast",
    "saturation",
    "sharpness",
    "exposure_auto",
    "exposure_absolute",
    "focus_auto",
    "focus_absolute",
    "white_balance_temperature_auto",
    "white_balance_temperature",
    "gain",
    "backlight_compensation",
)

@Composable
private fun CameraScreen(
    controls: CameraControlsState?,
    draftValues: Map<String, Int>,
    draftPixelFormat: String?,
    draftDevicePath: String?,
    loading: Boolean,
    saving: Boolean,
    cameraSource: String?,
    onDraftValueChange: (String, Int) -> Unit,
    onPixelFormatChange: (String?) -> Unit,
    onDeviceSelected: (String, Int) -> Unit,
    onReload: () -> Unit,
    onSave: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "USB webcam controls",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Choose which camera the Pi should use, then tune exposure/focus. " +
                            "Settings are applied when the camera reopens after Save.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    controls?.device?.let { device ->
                        Text(
                            text = "Active device: $device",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    controls?.reason?.let { reason ->
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (loading) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Text("Loading camera controls...")
                        }
                    }
                    if (controls?.supported == false || cameraSource == "picamera2") {
                        Text(
                            text = "Camera controls are only available when the Pi uses camera.source: opencv.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        if (controls?.supported == true || cameraSource == "opencv") {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Camera device", fontWeight = FontWeight.Medium)
                        val devices = controls?.devices.orEmpty()
                        if (devices.isEmpty()) {
                            Text(
                                text = "Connect WiFi to list cameras plugged into the Pi.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            devices.forEach { device ->
                                FilterChip(
                                    selected = draftDevicePath == device.path,
                                    onClick = { onDeviceSelected(device.path, device.index) },
                                    label = { Text(device.label) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        Text("Pixel format", fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("MJPG", "YUYV").forEach { format ->
                                FilterChip(
                                    selected = draftPixelFormat?.equals(format, ignoreCase = true) == true,
                                    onClick = { onPixelFormatChange(format) },
                                    label = { Text(format) },
                                )
                            }
                            FilterChip(
                                selected = draftPixelFormat.isNullOrBlank(),
                                onClick = { onPixelFormatChange(null) },
                                label = { Text("Default") },
                            )
                        }
                    }
                }
            }

            val sortedControls = (controls?.controls.orEmpty()).sortedWith(
                compareBy(
                    { control -> CAMERA_CONTROL_ORDER.indexOf(control.name).takeIf { it >= 0 } ?: CAMERA_CONTROL_ORDER.size },
                    { it.name },
                ),
            )

            items(sortedControls, key = { it.name }) { control ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val currentValue = draftValues[control.name] ?: control.value
                        if (control.isToggle) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(control.label, fontWeight = FontWeight.Medium)
                                    Text(
                                        toggleHint(control.name),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = toggleChecked(control.name, currentValue),
                                    onCheckedChange = { enabled ->
                                        onDraftValueChange(control.name, toggleValue(control.name, enabled))
                                    },
                                )
                            }
                        } else {
                            Text("${control.label}: $currentValue", fontWeight = FontWeight.Medium)
                            Slider(
                                value = currentValue.toFloat(),
                                onValueChange = { value ->
                                    onDraftValueChange(control.name, value.roundToInt())
                                },
                                valueRange = control.min.toFloat()..control.max.toFloat(),
                                steps = ((control.max - control.min) / control.step).coerceAtMost(100),
                            )
                            Text(
                                text = "Range ${control.min}–${control.max}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onSave, enabled = !saving && !loading) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .height(18.dp)
                                    .padding(end = 8.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text(if (saving) "Saving..." else "Save to Pi")
                    }
                    OutlinedButton(onClick = onReload, enabled = !saving && !loading) {
                        Text("Reload")
                    }
                }
            }
        }
    }
}

private fun toggleChecked(name: String, value: Int): Boolean {
    return when (name) {
        "exposure_auto" -> value >= 3
        else -> value != 0
    }
}

private fun toggleValue(name: String, enabled: Boolean): Int {
    return when (name) {
        "exposure_auto" -> if (enabled) 3 else 1
        else -> if (enabled) 1 else 0
    }
}

private fun toggleHint(name: String): String {
    return when (name) {
        "exposure_auto" -> "Off = manual exposure (set exposure absolute below)"
        "focus_auto" -> "Off = fixed focus for a mounted camera"
        "white_balance_temperature_auto" -> "Off = manual white balance temperature"
        else -> "Enabled"
    }
}

@Composable
private fun ReadingCard(
    title: String,
    reading: Reading?,
    showCapturedAt: Boolean = true,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (reading == null) {
                Text("No reading available", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            if (showCapturedAt) {
                Text(
                    text = formatCaptureTimestamp(reading.capturedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val entries = if (reading.readings.isNotEmpty()) {
                reading.readings
            } else {
                reading.values.map { (name, value) ->
                    RegionReading(name = name, value = value, confidence = 0.0)
                }
            }

            entries.forEach { region ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(formatRegionName(region.name))
                        region.positionLabel?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(region.value ?: "—", fontWeight = FontWeight.Medium)
                        if (region.confidence > 0) {
                            Text(
                                "${region.confidence.roundToInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            reading.imagePath?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Image: $it", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatRegionName(name: String): String {
    return name.split('_')
        .joinToString(" ") { word ->
            word.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        }
}

private fun formatCaptureTimestamp(raw: String): String {
    if (raw.isBlank()) return raw
    return try {
        val instant = java.time.Instant.parse(raw)
        captureTimeFormatter.format(instant.atZone(java.time.ZoneId.systemDefault()))
    } catch (_: Exception) {
        try {
            val normalized = raw.substringBefore('+').substringBefore('Z').trim()
            val local = java.time.LocalDateTime.parse(normalized)
            captureTimeFormatter.format(local.atZone(java.time.ZoneId.systemDefault()))
        } catch (_: Exception) {
            raw
        }
    }
}

private val captureTimeFormatter = java.time.format.DateTimeFormatter.ofPattern(
    "MMM d, yyyy 'at' h:mm a",
    java.util.Locale.getDefault(),
)

private fun connectionLabel(
    state: ConnectionState,
    transport: ConnectionTransport?,
    address: String?,
    httpHost: String,
    httpLinked: Boolean,
): String {
    return when (state) {
        ConnectionState.DISCONNECTED -> "Not connected"
        ConnectionState.SCANNING -> "Scanning for BLE devices..."
        ConnectionState.CONNECTING -> "Connecting..."
        ConnectionState.CONNECTED -> when (transport) {
            ConnectionTransport.HTTP -> "HTTP: ${address ?: "connected"}"
            ConnectionTransport.BLE, null -> {
                val ble = address?.let { "BLE: $it" } ?: "Connected"
                when {
                    httpLinked && httpHost.isNotBlank() -> "$ble + WiFi $httpHost"
                    httpHost.isNotBlank() -> "$ble · WiFi ready $httpHost"
                    else -> ble
                }
            }
        }
    }
}
