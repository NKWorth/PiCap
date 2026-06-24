package com.picap.mobile.ui

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
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ZoomOutMap
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
import androidx.compose.runtime.mutableFloatStateOf
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.picap.mobile.data.CaptureRegion
import com.picap.mobile.data.ConnectionTransport
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private data class ViewportTransform(
    val fitScale: Float,
    val fitOffsetX: Float,
    val fitOffsetY: Float,
    val zoom: Float,
    val pan: Offset,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    val scale: Float
        get() = fitScale * zoom

    fun imageRectToDisplay(region: CaptureRegion): Rect {
        return Rect(
            left = fitOffsetX + pan.x + region.x * scale,
            top = fitOffsetY + pan.y + region.y * scale,
            right = fitOffsetX + pan.x + (region.x + region.width) * scale,
            bottom = fitOffsetY + pan.y + (region.y + region.height) * scale,
        )
    }
}

@Composable
fun RegionCalibrationScreen(
    captureImageUrl: String?,
    imageAvailable: Boolean,
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
    onImageLoaded: (Int, Int) -> Unit,
    onSelectRegion: (Int) -> Unit,
    onBeginRegionEdit: () -> Unit,
    onRegionsChange: (List<CaptureRegion>) -> Unit,
    onResetDefaults: () -> Unit,
    onRefreshImage: () -> Unit,
    onNewCapture: () -> Unit,
    onSave: () -> Unit,
    onTestCapture: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    if (expanded && !captureImageUrl.isNullOrBlank()) {
        RegionCalibrationExpandedDialog(
            captureImageUrl = captureImageUrl,
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
                            "to position each region. Use +/− to zoom, then Expand for more room.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!imageAvailable) {
                        Text(
                            text = if (connectionTransport == ConnectionTransport.BLE && !httpLinked) {
                                "On Dashboard, tap Connect WiFi first."
                            } else {
                                "Set the Pi IP on the connect screen to load capture images."
                            },
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (connectionTransport == ConnectionTransport.BLE && httpLinked) {
                        Text(
                            text = "Images load over WiFi at $httpHost while BLE handles control.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRefreshImage, enabled = imageAvailable) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Text("Reload image", modifier = Modifier.padding(start = 4.dp))
                        }
                        OutlinedButton(onClick = onNewCapture, enabled = !captureBusy && imageAvailable) {
                            Text(if (captureBusy) "Capturing..." else "New capture")
                        }
                        OutlinedButton(onClick = onResetDefaults, enabled = imageWidth > 0 && imageHeight > 0) {
                            Text("Reset positions")
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
            if (captureImageUrl.isNullOrBlank()) {
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
                            text = "Take a capture from the Dashboard first, or tap New capture below.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = onNewCapture, enabled = imageAvailable && !captureBusy) {
                            Text(if (captureBusy) "Capturing..." else "New capture")
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
                    Text("Test capture")
                }
            }
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
    captureImageUrl: String,
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
private fun ZoomToolbar(
    zoom: Float,
    panMode: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetView: () -> Unit,
    onPanModeChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onZoomOut) {
                Icon(Icons.Default.Remove, contentDescription = "Zoom out")
            }
            Text(
                text = "${(zoom * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            IconButton(onClick = onZoomIn) {
                Icon(Icons.Default.Add, contentDescription = "Zoom in")
            }
            OutlinedButton(onClick = onResetView) {
                Icon(Icons.Default.ZoomOutMap, contentDescription = null)
                Text("Reset", modifier = Modifier.padding(start = 4.dp))
            }
        }
        FilterChip(
            selected = panMode,
            onClick = { onPanModeChange(!panMode) },
            label = { Text("Pan") },
            leadingIcon = {
                Icon(Icons.Default.PanTool, contentDescription = null, modifier = Modifier.size(18.dp))
            },
        )
    }
}

@Composable
private fun RegionCalibrationEditor(
    captureImageUrl: String,
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
    var zoom by remember { mutableFloatStateOf(2f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var panMode by remember { mutableStateOf(false) }
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

    val effectiveImageWidth = imageWidth.coerceAtLeast(1)
    val effectiveImageHeight = imageHeight.coerceAtLeast(1)

    val transform = remember(containerSize, effectiveImageWidth, effectiveImageHeight, zoom, pan) {
        if (containerSize.width == 0 || containerSize.height == 0) {
            null
        } else {
            buildViewportTransform(
                containerWidth = containerSize.width.toFloat(),
                containerHeight = containerSize.height.toFloat(),
                imageWidth = effectiveImageWidth,
                imageHeight = effectiveImageHeight,
                zoom = zoom,
                pan = pan,
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
    var reportedImageSize by remember(captureImageUrl) { mutableStateOf<IntSize?>(null) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ZoomToolbar(
            zoom = zoom,
            panMode = panMode,
            onZoomIn = { zoom = (zoom * 1.35f).coerceAtMost(12f) },
            onZoomOut = { zoom = (zoom / 1.35f).coerceAtLeast(1f) },
            onResetView = {
                zoom = 2f
                pan = Offset.Zero
                panMode = false
            },
            onPanModeChange = { panMode = it },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .onSizeChanged { containerSize = it },
        ) {
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
                                color = color.copy(alpha = 0.15f),
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

                if (panMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(panMode) {
                                detectDragGestures { change, dragAmount ->
                                    pan = Offset(pan.x + dragAmount.x, pan.y + dragAmount.y)
                                    change.consume()
                                }
                            },
                    )
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
                        enabled = !panMode,
                        dragKey = selectedRegionName ?: "move",
                        dragSnapshot = {
                            selectedRegionName?.let { name ->
                                latestEditorRegions.find { it.name == name }
                            }
                        },
                        onDragBegin = { latestOnBeginRegionEdit() },
                    ) { cumulativeScreenOffset, dragStart ->
                        val imageDx = cumulativeScreenOffset.x / transform.scale
                        val imageDy = cumulativeScreenOffset.y / transform.scale
                        val updated = dragStart.copy(
                            x = clamp(
                                dragStart.x + imageDx.roundToInt(),
                                0,
                                effectiveImageWidth - dragStart.width,
                            ),
                            y = clamp(
                                dragStart.y + imageDy.roundToInt(),
                                0,
                                effectiveImageHeight - dragStart.height,
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
                        enabled = !panMode,
                        dragKey = selectedRegionName ?: "resize",
                        dragSnapshot = {
                            selectedRegionName?.let { name ->
                                latestEditorRegions.find { it.name == name }
                            }
                        },
                        onDragBegin = { latestOnBeginRegionEdit() },
                    ) { cumulativeScreenOffset, dragStart ->
                        val imageDx = cumulativeScreenOffset.x / transform.scale
                        val imageDy = cumulativeScreenOffset.y / transform.scale
                        val updated = dragStart.copy(
                            width = clamp(
                                dragStart.width + imageDx.roundToInt(),
                                40,
                                effectiveImageWidth - dragStart.x,
                            ),
                            height = clamp(
                                dragStart.height + imageDy.roundToInt(),
                                24,
                                effectiveImageHeight - dragStart.y,
                            ),
                        )
                        commitRegionUpdate(dragStart.name, updated)
                    }
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
    enabled: Boolean,
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
            .then(
                if (enabled) {
                    Modifier.pointerInput(contentDescription, enabled, dragKey) {
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
                    }
                } else {
                    Modifier
                },
            ),
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
    zoom: Float,
    pan: Offset,
): ViewportTransform {
    val fitScale = min(
        containerWidth / imageWidth,
        containerHeight / imageHeight,
    )
    val displayedWidth = imageWidth * fitScale
    val displayedHeight = imageHeight * fitScale
    return ViewportTransform(
        fitScale = fitScale,
        fitOffsetX = (containerWidth - displayedWidth) / 2f,
        fitOffsetY = (containerHeight - displayedHeight) / 2f,
        zoom = zoom,
        pan = pan,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
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
