package com.picap.mobile.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size as CoilSize
import com.picap.mobile.data.CaptureRegion
import com.picap.mobile.data.CaptureState
import com.picap.mobile.data.ConnectionTransport
import com.picap.mobile.data.Reading
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ViewportTransform(
    val fitOffsetX: Float,
    val fitOffsetY: Float,
    val scale: Float,
    val coordToImageScaleX: Float,
    val coordToImageScaleY: Float,
) {
    fun imageRectToDisplay(region: CaptureRegion): Rect {
        val imageX = region.x * coordToImageScaleX
        val imageY = region.y * coordToImageScaleY
        val imageW = region.width * coordToImageScaleX
        val imageH = region.height * coordToImageScaleY
        return Rect(
            left = fitOffsetX + imageX * scale,
            top = fitOffsetY + imageY * scale,
            right = fitOffsetX + (imageX + imageW) * scale,
            bottom = fitOffsetY + (imageY + imageH) * scale,
        )
    }
}

@Composable
fun RegionCalibrationScreen(
    captureImageUrl: String?,
    calibrationBitmap: Bitmap?,
    imageAvailable: Boolean,
    bleImageLoading: Boolean,
    bleImageProgress: String?,
    httpHost: String,
    httpLinked: Boolean,
    connectionTransport: ConnectionTransport?,
    regions: List<CaptureRegion>,
    regionsLocked: Boolean,
    selectedRegionIndex: Int,
    imageWidth: Int,
    imageHeight: Int,
    saving: Boolean,
    captureBusy: Boolean,
    autoCalibrating: Boolean,
    captureState: CaptureState,
    testCaptureImageUrl: String?,
    onImageLoaded: (Int, Int) -> Unit,
    onSelectRegion: (Int) -> Unit,
    onBeginRegionEdit: () -> Unit,
    onRegionsChange: (List<CaptureRegion>) -> Unit,
    onResetDefaults: () -> Unit,
    onRefreshImage: () -> Unit,
    onNewCapture: () -> Unit,
    onSave: () -> Unit,
    onTestCapture: () -> Unit,
    onAutoCalibrate: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val hasCalibrationImage = !captureImageUrl.isNullOrBlank() || calibrationBitmap != null

    if (expanded && hasCalibrationImage) {
        RegionCalibrationExpandedDialog(
            captureImageUrl = captureImageUrl,
            calibrationBitmap = calibrationBitmap,
            regions = regions,
            regionsLocked = regionsLocked,
            selectedRegionIndex = selectedRegionIndex,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            onImageLoaded = onImageLoaded,
            onSelectRegion = onSelectRegion,
            onBeginRegionEdit = onBeginRegionEdit,
            onRegionsChange = onRegionsChange,
            onDismiss = { expanded = false },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "15 Min Avg regions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Uses the latest capture image. Drag the large move handle (four arrows) " +
                            "to position each region, or tap Auto calibrate to find the 15 Mins AVG times.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!imageAvailable) {
                        Text(
                            text = "Connect to the Pi over Bluetooth to calibrate regions.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (connectionTransport == ConnectionTransport.BLE && !httpLinked) {
                        Text(
                            text = "Calibration images transfer over Bluetooth. Connect WiFi on the Dashboard for faster loading.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else if (connectionTransport == ConnectionTransport.BLE && httpLinked) {
                        Text(
                            text = "Images load over WiFi at $httpHost while BLE handles control.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (bleImageLoading) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = bleImageProgress ?: "Transferring image over Bluetooth...",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = onRefreshImage,
                                enabled = imageAvailable,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Text("Reload", modifier = Modifier.padding(start = 4.dp))
                            }
                            OutlinedButton(
                                onClick = onNewCapture,
                                enabled = !captureBusy && imageAvailable,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (captureBusy) "Capturing..." else "New capture")
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = onResetDefaults,
                                enabled = imageWidth > 0 && imageHeight > 0,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Reset positions")
                            }
                            Button(
                                onClick = onAutoCalibrate,
                                enabled = imageAvailable && !autoCalibrating && !saving,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (autoCalibrating) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .height(18.dp)
                                            .padding(end = 8.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                                Text(
                                    text = if (autoCalibrating) "Calibrating..." else "Auto calibrate",
                                    modifier = if (autoCalibrating) Modifier.padding(start = 4.dp) else Modifier,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                regions.forEachIndexed { index, region ->
                    FilterChip(
                        selected = selectedRegionIndex == index,
                        onClick = { onSelectRegion(index) },
                        label = { Text(formatRegionName(region.name)) },
                    )
                }
            }
        }

        item {
            if (!hasCalibrationImage) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "No capture image yet",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (connectionTransport == ConnectionTransport.BLE && !httpLinked) {
                                "Tap New capture to take a photo on the Pi and transfer it over Bluetooth."
                            } else {
                                "Take a capture from the Dashboard first, or tap New capture below."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = onNewCapture,
                            enabled = imageAvailable && !captureBusy,
                        ) {
                            Text(
                                if (captureBusy) {
                                    bleImageProgress ?: "Capturing..."
                                } else {
                                    "New capture"
                                },
                            )
                        }
                        Button(
                            onClick = onAutoCalibrate,
                            enabled = imageAvailable && !autoCalibrating && !saving,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (autoCalibrating) "Calibrating..." else "Auto calibrate")
                        }
                    }
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            OutlinedButton(onClick = { expanded = true }) {
                                Icon(Icons.Default.Fullscreen, contentDescription = null)
                                Text("Expand", modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                        RegionCalibrationEditor(
                            captureImageUrl = captureImageUrl,
                            calibrationBitmap = calibrationBitmap,
                            regions = regions,
                            regionsLocked = regionsLocked,
                            selectedRegionIndex = selectedRegionIndex,
                            imageWidth = imageWidth,
                            imageHeight = imageHeight,
                            onImageLoaded = onImageLoaded,
                            onBeginRegionEdit = onBeginRegionEdit,
                            onRegionsChange = onRegionsChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(400.dp),
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSave, enabled = !saving && regions.size >= 2) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .height(18.dp)
                                .padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(if (saving) "Saving..." else "Save regions to Pi")
                }
                OutlinedButton(onClick = onTestCapture, enabled = !saving && !captureBusy) {
                    Text(if (captureBusy) "Testing..." else "Test capture")
                }
            }
        }

        item {
            RegionTestCaptureResults(
                captureState = captureState,
                regions = regions,
                regionsDirty = regionsLocked,
                regionCoordWidth = captureState.result?.imageWidth?.takeIf { it > 0 } ?: imageWidth,
                regionCoordHeight = captureState.result?.imageHeight?.takeIf { it > 0 } ?: imageHeight,
                testCaptureImageUrl = testCaptureImageUrl,
            )
        }

        if (imageWidth > 0 && imageHeight > 0) {
            item {
                regions.forEach { region ->
                    Text(
                        text = "${formatRegionName(region.name)}: " +
                            "x=${region.x}, y=${region.y}, ${region.width}×${region.height}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun RegionCalibrationExpandedDialog(
    captureImageUrl: String?,
    calibrationBitmap: Bitmap?,
    regions: List<CaptureRegion>,
    regionsLocked: Boolean,
    selectedRegionIndex: Int,
    imageWidth: Int,
    imageHeight: Int,
    onImageLoaded: (Int, Int) -> Unit,
    onSelectRegion: (Int) -> Unit,
    onBeginRegionEdit: () -> Unit,
    onRegionsChange: (List<CaptureRegion>) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Drag the move handle to position the selected region",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    regions.forEachIndexed { index, region ->
                        FilterChip(
                            selected = selectedRegionIndex == index,
                            onClick = { onSelectRegion(index) },
                            label = { Text(formatRegionName(region.name)) },
                        )
                    }
                }
                RegionCalibrationEditor(
                    captureImageUrl = captureImageUrl,
                    calibrationBitmap = calibrationBitmap,
                    regions = regions,
                    regionsLocked = regionsLocked,
                    selectedRegionIndex = selectedRegionIndex,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    onImageLoaded = onImageLoaded,
                    onBeginRegionEdit = onBeginRegionEdit,
                    onRegionsChange = onRegionsChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun RegionCalibrationEditor(
    captureImageUrl: String?,
    calibrationBitmap: Bitmap?,
    regions: List<CaptureRegion>,
    regionsLocked: Boolean,
    selectedRegionIndex: Int,
    imageWidth: Int,
    imageHeight: Int,
    onImageLoaded: (Int, Int) -> Unit,
    onBeginRegionEdit: () -> Unit,
    onRegionsChange: (List<CaptureRegion>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var editorRegions by remember { mutableStateOf(regions) }

    LaunchedEffect(regions, regionsLocked) {
        if (!regionsLocked) {
            editorRegions = regions
        }
    }

    val latestEditorRegions by rememberUpdatedState(editorRegions)
    val latestOnRegionsChange by rememberUpdatedState(onRegionsChange)
    val latestOnBeginRegionEdit by rememberUpdatedState(onBeginRegionEdit)

    fun commitRegionUpdate(regionName: String, updated: CaptureRegion) {
        applyRegionUpdate(latestEditorRegions, regionName, updated) { next ->
            editorRegions = next
            latestOnRegionsChange(next)
        }
    }

    var reportedImageSize by remember(captureImageUrl, calibrationBitmap) { mutableStateOf<IntSize?>(null) }
    val regionCoordWidth = imageWidth.coerceAtLeast(1)
    val regionCoordHeight = imageHeight.coerceAtLeast(1)
    val intrinsicWidth = reportedImageSize?.width?.takeIf { it > 0 }
        ?: calibrationBitmap?.width?.takeIf { it > 0 }
        ?: regionCoordWidth
    val intrinsicHeight = reportedImageSize?.height?.takeIf { it > 0 }
        ?: calibrationBitmap?.height?.takeIf { it > 0 }
        ?: regionCoordHeight

    LaunchedEffect(calibrationBitmap) {
        val bitmap = calibrationBitmap ?: return@LaunchedEffect
        val size = IntSize(bitmap.width, bitmap.height)
        if (reportedImageSize != size) {
            reportedImageSize = size
            onImageLoaded(bitmap.width, bitmap.height)
        }
    }

    val transform = remember(
        containerSize,
        intrinsicWidth,
        intrinsicHeight,
        regionCoordWidth,
        regionCoordHeight,
    ) {
        if (containerSize.width == 0 || containerSize.height == 0) {
            null
        } else {
            buildViewportTransform(
                containerWidth = containerSize.width.toFloat(),
                containerHeight = containerSize.height.toFloat(),
                imageWidth = intrinsicWidth,
                imageHeight = intrinsicHeight,
                regionCoordWidth = regionCoordWidth,
                regionCoordHeight = regionCoordHeight,
            )
        }
    }

    val regionColors = listOf(
        Color(0xFF4CAF50),
        Color(0xFF2196F3),
    )
    val selectedRegion = editorRegions.getOrNull(selectedRegionIndex)
    val selectedRegionName = selectedRegion?.name
    val selectedColor = regionColors[selectedRegionIndex % regionColors.size]

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged { containerSize = it },
    ) {
        if (!captureImageUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(captureImageUrl)
                    .crossfade(false)
                    .build(),
                contentDescription = "Calibration capture",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                onSuccess = { state ->
                    val drawable = state.result.drawable
                    val width = drawable.intrinsicWidth
                    val height = drawable.intrinsicHeight
                    if (width > 0 && height > 0) {
                        val size = IntSize(width, height)
                        if (reportedImageSize != size) {
                            reportedImageSize = size
                            onImageLoaded(width, height)
                        }
                    }
                },
            )
        } else if (calibrationBitmap != null) {
            Image(
                bitmap = calibrationBitmap.asImageBitmap(),
                contentDescription = "Calibration capture",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }

        if (transform != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                    val gapPx = 8f * density.density
                    val moveHandleRadius = 28f * density.density
                    val resizeHandleRadius = 22f * density.density

                    editorRegions.forEachIndexed { index, region ->
                        val color = regionColors[index % regionColors.size]
                        val isSelected = index == selectedRegionIndex
                        val strokeWidth = if (isSelected) 4f else 2f
                        val displayRect = transform.imageRectToDisplay(region)
                        if (!isSelected) {
                            drawRect(
                                color = color.copy(alpha = 0.28f),
                                topLeft = Offset(displayRect.left, displayRect.top),
                                size = Size(displayRect.width, displayRect.height),
                            )
                        }
                        drawRect(
                            color = color,
                            topLeft = Offset(displayRect.left, displayRect.top),
                            size = Size(displayRect.width, displayRect.height),
                            style = Stroke(width = strokeWidth),
                        )

                        if (isSelected) {
                            val moveAnchor = Offset(displayRect.center.x, displayRect.top)
                            val moveHandleCenter = Offset(
                                displayRect.center.x,
                                displayRect.top - moveHandleRadius - gapPx,
                            )
                            val resizeAnchor = Offset(displayRect.right, displayRect.bottom)
                            val resizeHandleCenter = Offset(
                                displayRect.right + resizeHandleRadius + gapPx,
                                displayRect.bottom + resizeHandleRadius + gapPx,
                            )
                            drawLine(
                                color = color.copy(alpha = 0.7f),
                                start = moveHandleCenter,
                                end = moveAnchor,
                                strokeWidth = 2f,
                            )
                            drawLine(
                                color = color.copy(alpha = 0.7f),
                                start = resizeHandleCenter,
                                end = resizeAnchor,
                                strokeWidth = 2f,
                            )
                        }
                    }
                }

            if (selectedRegion != null) {
                    val displayRect = transform.imageRectToDisplay(selectedRegion)
                    val gapPx = with(density) { 8.dp.toPx() }
                    val moveHandleSizePx = with(density) { 56.dp.toPx() }
                    val resizeHandleSizePx = with(density) { 44.dp.toPx() }

                    RegionDragHandle(
                        centerX = displayRect.center.x,
                        centerY = displayRect.top - moveHandleSizePx / 2f - gapPx,
                        sizePx = moveHandleSizePx,
                        color = selectedColor,
                        icon = Icons.Default.OpenWith,
                        contentDescription = "move-handle-$selectedRegionName",
                        dragKey = selectedRegionName ?: "move",
                        dragSnapshot = {
                            selectedRegionName?.let { name ->
                                latestEditorRegions.find { it.name == name }
                            }
                        },
                        onDragBegin = { latestOnBeginRegionEdit() },
                    ) { cumulativeScreenOffset, dragStart ->
                        val refDx = (
                            cumulativeScreenOffset.x / transform.scale / transform.coordToImageScaleX
                        ).roundToInt()
                        val refDy = (
                            cumulativeScreenOffset.y / transform.scale / transform.coordToImageScaleY
                        ).roundToInt()
                        val updated = dragStart.copy(
                            x = clamp(
                                dragStart.x + refDx,
                                0,
                                regionCoordWidth - dragStart.width,
                            ),
                            y = clamp(
                                dragStart.y + refDy,
                                0,
                                regionCoordHeight - dragStart.height,
                            ),
                        )
                        commitRegionUpdate(dragStart.name, updated)
                    }

                    RegionDragHandle(
                        centerX = displayRect.right + resizeHandleSizePx / 2f + gapPx,
                        centerY = displayRect.bottom + resizeHandleSizePx / 2f + gapPx,
                        sizePx = resizeHandleSizePx,
                        color = selectedColor,
                        icon = Icons.Default.OpenInFull,
                        contentDescription = "resize-handle-$selectedRegionName",
                        dragKey = selectedRegionName ?: "resize",
                        dragSnapshot = {
                            selectedRegionName?.let { name ->
                                latestEditorRegions.find { it.name == name }
                            }
                        },
                        onDragBegin = { latestOnBeginRegionEdit() },
                    ) { cumulativeScreenOffset, dragStart ->
                        val refDx = (
                            cumulativeScreenOffset.x / transform.scale / transform.coordToImageScaleX
                        ).roundToInt()
                        val refDy = (
                            cumulativeScreenOffset.y / transform.scale / transform.coordToImageScaleY
                        ).roundToInt()
                        val updated = dragStart.copy(
                            width = clamp(
                                dragStart.width + refDx,
                                20,
                                regionCoordWidth - dragStart.x,
                            ),
                            height = clamp(
                                dragStart.height + refDy,
                                14,
                                regionCoordHeight - dragStart.y,
                            ),
                        )
                        commitRegionUpdate(dragStart.name, updated)
                    }
                }
            }
    }
}

@Composable
private fun RegionDragHandle(
    centerX: Float,
    centerY: Float,
    sizePx: Float,
    color: Color,
    icon: ImageVector,
    contentDescription: String,
    dragKey: Any,
    dragSnapshot: () -> CaptureRegion?,
    onDragBegin: () -> Unit,
    onDrag: (cumulativeScreenOffset: Offset, dragStart: CaptureRegion) -> Unit,
) {
    val half = sizePx / 2f
    var isDragging by remember { mutableStateOf(false) }
    var dragVisualOffset by remember { mutableStateOf(Offset.Zero) }
    var dragAnchor by remember { mutableStateOf(Offset.Zero) }
    var dragStartRegion by remember { mutableStateOf<CaptureRegion?>(null) }
    val displayCenterX = if (isDragging) dragAnchor.x + dragVisualOffset.x else centerX
    val displayCenterY = if (isDragging) dragAnchor.y + dragVisualOffset.y else centerY

    Surface(
        modifier = Modifier
            .offset {
                IntOffset(
                    (displayCenterX - half).roundToInt(),
                    (displayCenterY - half).roundToInt(),
                )
            }
            .size(with(LocalDensity.current) { sizePx.toDp() })
            .pointerInput(contentDescription, dragKey) {
                detectDragGestures(
                    onDragStart = {
                        onDragBegin()
                        val snapshot = dragSnapshot() ?: return@detectDragGestures
                        dragStartRegion = snapshot
                        isDragging = true
                        dragAnchor = Offset(centerX, centerY)
                        dragVisualOffset = Offset.Zero
                    },
                    onDrag = { change, dragAmount ->
                        val start = dragStartRegion ?: return@detectDragGestures
                        dragVisualOffset += dragAmount
                        onDrag(dragVisualOffset, start)
                        change.consume()
                    },
                    onDragEnd = {
                        isDragging = false
                        dragVisualOffset = Offset.Zero
                        dragStartRegion = null
                    },
                    onDragCancel = {
                        isDragging = false
                        dragVisualOffset = Offset.Zero
                        dragStartRegion = null
                    },
                )
            },
        shape = CircleShape,
        color = color,
        shadowElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

private fun buildViewportTransform(
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
    regionCoordWidth: Int,
    regionCoordHeight: Int,
): ViewportTransform {
    val fitScale = min(
        containerWidth / imageWidth,
        containerHeight / imageHeight,
    )
    val displayedWidth = imageWidth * fitScale
    val displayedHeight = imageHeight * fitScale
    return ViewportTransform(
        fitOffsetX = (containerWidth - displayedWidth) / 2f,
        fitOffsetY = (containerHeight - displayedHeight) / 2f,
        scale = fitScale,
        coordToImageScaleX = imageWidth.toFloat() / regionCoordWidth.coerceAtLeast(1),
        coordToImageScaleY = imageHeight.toFloat() / regionCoordHeight.coerceAtLeast(1),
    )
}

private fun clamp(value: Int, minValue: Int, maxValue: Int): Int {
    return max(minValue, min(value, maxValue))
}

private fun applyRegionUpdate(
    regions: List<CaptureRegion>,
    regionName: String,
    updated: CaptureRegion,
    onRegionsChange: (List<CaptureRegion>) -> Unit,
) {
    val existing = regions.find { it.name == regionName } ?: return
    if (existing == updated) {
        return
    }
    onRegionsChange(regions.map { if (it.name == regionName) updated else it })
}

private fun formatRegionName(name: String): String {
    return name.split('_')
        .joinToString(" ") { word ->
            word.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        }
}

@Composable
private fun RegionTestCaptureResults(
    captureState: CaptureState,
    regions: List<CaptureRegion>,
    regionsDirty: Boolean,
    regionCoordWidth: Int,
    regionCoordHeight: Int,
    testCaptureImageUrl: String?,
) {
    val otwRegionNames = listOf(
        CaptureRegion.ORDER_POINT_15MIN_AVG,
        CaptureRegion.CURRENT_OTW_15MIN_AVG,
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Test OCR results",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            when (captureState.status) {
                "capturing" -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = "Running OCR with the current region boxes…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                "error" -> {
                    Text(
                        text = captureState.message ?: "Capture failed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                "complete" -> {
                    val reading = captureState.result
                    if (reading == null) {
                        Text(
                            text = "Capture finished but no reading was returned.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        if (regionsDirty) {
                            Text(
                                text = "Unsaved box changes are shown in the editor, but OCR uses the regions saved on the Pi.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        if (testCaptureImageUrl.isNullOrBlank()) {
                            Text(
                                text = "Connect WiFi on the Dashboard to preview OCR crops.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        otwRegionNames.forEach { regionName ->
                            val ocrRegion = reading.readings
                                .find { it.name == regionName }
                                ?.toCropRegion()
                            val previewRegion = ocrRegion
                                ?: regions.find { it.name == regionName }
                            val value = readingValueForRegion(reading, regionName)
                            val confidence = reading.readings
                                .find { it.name == regionName }
                                ?.confidence
                                ?.takeIf { it > 0 }
                            RegionOcrResultRow(
                                regionName = regionName,
                                value = value,
                                confidence = confidence,
                                previewRegion = previewRegion,
                                imageUrl = testCaptureImageUrl,
                                regionCoordWidth = regionCoordWidth,
                                regionCoordHeight = regionCoordHeight,
                                useDirectPixels = ocrRegion != null,
                            )
                        }
                        Text(
                            text = "Crops show the exact regions the Pi used for OCR on this capture.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    Text(
                        text = "Tap Test capture to read the Order Point and Current OTW 15 Min Avg times using the boxes above.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun RegionOcrResultRow(
    regionName: String,
    value: String?,
    confidence: Double?,
    previewRegion: CaptureRegion?,
    imageUrl: String?,
    regionCoordWidth: Int,
    regionCoordHeight: Int,
    useDirectPixels: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = formatRegionName(regionName),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (previewRegion != null && !imageUrl.isNullOrBlank()) {
                RegionCropPreview(
                    imageUrl = imageUrl,
                    region = previewRegion,
                    regionCoordWidth = regionCoordWidth,
                    regionCoordHeight = regionCoordHeight,
                    useDirectPixels = useDirectPixels,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "OCR result",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value ?: "—",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (value.isNullOrBlank()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                confidence?.let {
                    Text(
                        text = "${it.roundToInt()}% confidence",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                previewRegion?.let { region ->
                    Text(
                        text = "${region.width}×${region.height} at (${region.x}, ${region.y})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RegionCropPreview(
    imageUrl: String,
    region: CaptureRegion,
    regionCoordWidth: Int,
    regionCoordHeight: Int,
    useDirectPixels: Boolean = false,
) {
    val context = LocalContext.current
    var crop by remember(imageUrl, region, regionCoordWidth, regionCoordHeight, useDirectPixels) {
        mutableStateOf<Bitmap?>(null)
    }
    var failed by remember(imageUrl, region, regionCoordWidth, regionCoordHeight, useDirectPixels) {
        mutableStateOf(false)
    }

    LaunchedEffect(imageUrl, region, regionCoordWidth, regionCoordHeight, useDirectPixels) {
        failed = false
        crop = null
        val bitmap = withContext(Dispatchers.IO) {
            loadRegionCropBitmap(
                context = context,
                imageUrl = imageUrl,
                region = region,
                regionCoordWidth = regionCoordWidth,
                regionCoordHeight = regionCoordHeight,
                useDirectPixels = useDirectPixels,
            )
        }
        if (bitmap == null) {
            failed = true
        } else {
            crop = bitmap
        }
    }

    when {
        crop != null -> {
            Image(
                bitmap = crop!!.asImageBitmap(),
                contentDescription = "OCR input crop for ${region.name}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .heightIn(max = 80.dp)
                    .widthIn(max = 160.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(6.dp),
                    ),
            )
        }
        failed -> {
            Box(
                modifier = Modifier
                    .size(width = 120.dp, height = 56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Preview failed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        else -> {
            Box(
                modifier = Modifier
                    .size(width = 120.dp, height = 56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

private suspend fun loadRegionCropBitmap(
    context: Context,
    imageUrl: String,
    region: CaptureRegion,
    regionCoordWidth: Int,
    regionCoordHeight: Int,
    useDirectPixels: Boolean = false,
): Bitmap? {
    val request = ImageRequest.Builder(context)
        .data(imageUrl)
        .allowHardware(false)
        .size(CoilSize.ORIGINAL)
        .build()
    val result = context.imageLoader.execute(request)
    if (result is ErrorResult) {
        return null
    }
    val drawable = (result as? SuccessResult)?.drawable ?: return null
    val source = drawable.toBitmap()
    val x1: Int
    val y1: Int
    val x2: Int
    val y2: Int
    if (useDirectPixels) {
        x1 = region.x.coerceIn(0, source.width - 1)
        y1 = region.y.coerceIn(0, source.height - 1)
        x2 = (region.x + region.width).coerceIn(x1 + 1, source.width)
        y2 = (region.y + region.height).coerceIn(y1 + 1, source.height)
    } else {
        val refWidth = regionCoordWidth.coerceAtLeast(1)
        val refHeight = regionCoordHeight.coerceAtLeast(1)
        val scaleX = source.width.toFloat() / refWidth
        val scaleY = source.height.toFloat() / refHeight
        x1 = (region.x * scaleX).roundToInt().coerceIn(0, source.width - 1)
        y1 = (region.y * scaleY).roundToInt().coerceIn(0, source.height - 1)
        x2 = ((region.x + region.width) * scaleX).roundToInt().coerceIn(x1 + 1, source.width)
        y2 = ((region.y + region.height) * scaleY).roundToInt().coerceIn(y1 + 1, source.height)
    }
    return Bitmap.createBitmap(source, x1, y1, x2 - x1, y2 - y1)
}

private fun readingValueForRegion(reading: Reading, regionName: String): String? {
    reading.values[regionName]?.let { return it }
    return reading.readings.find { it.name == regionName }?.value
}
