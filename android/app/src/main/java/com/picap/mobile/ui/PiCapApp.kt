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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.picap.mobile.AppTab
import com.picap.mobile.PicapViewModel
import com.picap.mobile.data.ConnectionState
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
                title = { Text("PiCap") },
                subtitle = {
                    Text(connectionLabel(uiState.connectionState, uiState.connectedAddress))
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
                        captureBusy = uiState.captureState.isBusy,
                        onCapture = viewModel::triggerCapture,
                        onDisconnect = viewModel::disconnect,
                    )
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
                    text = "Make sure PiCap is running on the Pi and Bluetooth is enabled.",
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
    captureBusy: Boolean,
    onCapture: () -> Unit,
    onDisconnect: () -> Unit,
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
                ReadingCard(title = reading.capturedAt, reading = reading)
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
                        "Numbers are detected automatically. Adjust sensitivity if readings are missed or noisy.",
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
                        valueRange = 1f..4f,
                        steps = 5,
                    )

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

@Composable
private fun ReadingCard(
    title: String,
    reading: Reading?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (reading == null) {
                Text("No reading available", style = MaterialTheme.typography.bodyMedium)
                return@Column
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
                        Text(region.name)
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

private fun connectionLabel(state: ConnectionState, address: String?): String {
    return when (state) {
        ConnectionState.DISCONNECTED -> "Not connected"
        ConnectionState.SCANNING -> "Scanning..."
        ConnectionState.CONNECTING -> "Connecting..."
        ConnectionState.CONNECTED -> address?.let { "Connected to $it" } ?: "Connected"
    }
}
