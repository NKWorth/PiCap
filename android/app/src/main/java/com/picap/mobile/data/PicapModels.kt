package com.picap.mobile.data

import org.json.JSONArray
import org.json.JSONObject

data class ScannedDevice(
    val name: String,
    val address: String,
)

data class DeviceStatus(
    val ready: Boolean,
    val lastCaptureAt: String?,
    val lastError: String?,
    val cameraSource: String,
    val ocrMode: String,
    val httpActive: Boolean = false,
    val httpPort: Int? = null,
    val httpUrl: String? = null,
    val httpHostFromPi: String? = null,
) {
    fun httpHostPort(): String? {
        httpHostFromPi?.trim()?.removeSuffix("/")?.ifBlank { null }?.let { return it }
        val url = httpUrl?.trim()?.removeSuffix("/")?.ifBlank { null } ?: return null
        return url
            .removePrefix("http://")
            .removePrefix("https://")
            .ifBlank { null }
    }

    companion object {
        fun fromJson(json: JSONObject): DeviceStatus? {
            if (json.length() == 0) return null
            return DeviceStatus(
                ready = json.optBoolean("ready", false),
                lastCaptureAt = json.optString("last_capture_at").ifBlank { null },
                lastError = json.optString("last_error").ifBlank { null },
                cameraSource = json.optString("camera_source", "unknown"),
                ocrMode = json.optString("ocr_mode", "auto"),
                httpActive = json.optBoolean("http_active", false),
                httpPort = if (json.has("http_port") && !json.isNull("http_port")) {
                    json.optInt("http_port")
                } else {
                    null
                },
                httpUrl = json.optString("http_url").ifBlank { null },
                httpHostFromPi = json.optString("http_host").ifBlank { null },
            )
        }
    }
}

data class OcrConfig(
    val mode: String = "auto",
    val minConfidence: Int = 60,
    val minDigits: Int = 1,
    val upscaleFactor: Double = 2.0,
    val sharpen: Double = 1.0,
    val contrast: Double = 2.0,
    val threshold: String = "otsu",
    val autoPsm: Int = 11,
    val mergeLineTolerance: Int = 15,
    val mergeGapTolerance: Int = 30,
) {
    fun toPatchJson(): JSONObject {
        return JSONObject()
            .put(
                "ocr",
                JSONObject()
                    .put("mode", mode)
                    .put("min_confidence", minConfidence)
                    .put("min_digits", minDigits)
                    .put("upscale_factor", upscaleFactor)
                    .put("sharpen", sharpen)
                    .put("contrast", contrast)
                    .put("threshold", threshold)
                    .put("auto_psm", autoPsm)
                    .put("merge_line_tolerance", mergeLineTolerance)
                    .put("merge_gap_tolerance", mergeGapTolerance),
            )
    }

    fun toJsonObject(): JSONObject {
        return JSONObject()
            .put("mode", mode)
            .put("min_confidence", minConfidence)
            .put("min_digits", minDigits)
            .put("upscale_factor", upscaleFactor)
            .put("sharpen", sharpen)
            .put("contrast", contrast)
            .put("threshold", threshold)
            .put("auto_psm", autoPsm)
            .put("merge_line_tolerance", mergeLineTolerance)
            .put("merge_gap_tolerance", mergeGapTolerance)
    }

    companion object {
        fun fromJson(json: JSONObject?): OcrConfig {
            val ocr = json ?: JSONObject()
            return OcrConfig(
                mode = ocr.optString("mode", "auto"),
                minConfidence = ocr.optInt("min_confidence", 60),
                minDigits = ocr.optInt("min_digits", 1),
                upscaleFactor = ocr.optDouble("upscale_factor", 2.0),
                sharpen = ocr.optDouble("sharpen", 1.0),
                contrast = ocr.optDouble("contrast", 2.0),
                threshold = ocr.optString("threshold", "otsu"),
                autoPsm = ocr.optInt("auto_psm", 11),
                mergeLineTolerance = ocr.optInt("merge_line_tolerance", 15),
                mergeGapTolerance = ocr.optInt("merge_gap_tolerance", 30),
            )
        }
    }
}

data class CaptureRegion(
    val name: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val format: String = "time",
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("name", name)
            .put("x", x)
            .put("y", y)
            .put("width", width)
            .put("height", height)
            .put("format", format)
    }

    companion object {
        const val ORDER_POINT_15MIN_AVG = "order_point_15min_avg"
        const val CURRENT_OTW_15MIN_AVG = "current_otw_15min_avg"

        fun fromJson(json: JSONObject): CaptureRegion {
            return CaptureRegion(
                name = json.optString("name"),
                x = json.optInt("x"),
                y = json.optInt("y"),
                width = json.optInt("width"),
                height = json.optInt("height"),
                format = json.optString("format", "time"),
            )
        }

        fun listFromJsonArray(array: JSONArray): List<CaptureRegion> {
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(fromJson(item))
                }
            }
        }

        fun otwDefaults(imageWidth: Int, imageHeight: Int): List<CaptureRegion> {
            return listOf(
                CaptureRegion(
                    name = ORDER_POINT_15MIN_AVG,
                    x = (imageWidth * 0.06f).toInt(),
                    y = (imageHeight * 0.29f).toInt(),
                    width = (imageWidth * 0.07f).toInt().coerceAtLeast(80),
                    height = (imageHeight * 0.05f).toInt().coerceAtLeast(36),
                ),
                CaptureRegion(
                    name = CURRENT_OTW_15MIN_AVG,
                    x = (imageWidth * 0.42f).toInt(),
                    y = (imageHeight * 0.48f).toInt(),
                    width = (imageWidth * 0.06f).toInt().coerceAtLeast(70),
                    height = (imageHeight * 0.045f).toInt().coerceAtLeast(32),
                ),
            )
        }

        fun normalizeOtwRegions(
            regions: List<CaptureRegion>,
            imageWidth: Int,
            imageHeight: Int,
        ): List<CaptureRegion> {
            val defaults = otwDefaults(imageWidth, imageHeight)
            val order = regions.find { it.name == ORDER_POINT_15MIN_AVG } ?: defaults[0]
            val current = regions.find { it.name == CURRENT_OTW_15MIN_AVG } ?: defaults[1]
            return listOf(order, current)
        }
    }
}

fun regionsConfigPatch(
    regions: List<CaptureRegion>,
    ocr: OcrConfig,
    refWidth: Int,
    refHeight: Int,
): JSONObject {
    val regionsArray = JSONArray()
    regions.forEach { regionsArray.put(it.toJson()) }
    return JSONObject()
        .put("replace", true)
        .put("ocr", ocr.copy(mode = "regions").toJsonObject())
        .put("regions", regionsArray)
        .put("regions_ref", JSONArray().put(refWidth).put(refHeight))
}

data class AutoCalibrateResult(
    val regions: List<CaptureRegion>,
    val imageWidth: Int,
    val imageHeight: Int,
    val imagePath: String?,
) {
    companion object {
        fun fromJson(json: JSONObject): AutoCalibrateResult? {
            if (json.length() == 0 || json.has("error")) return null
            val regionsArray = json.optJSONArray("regions") ?: return null
            val regions = CaptureRegion.listFromJsonArray(regionsArray)
            if (regions.isEmpty()) return null
            return AutoCalibrateResult(
                regions = regions,
                imageWidth = json.optInt("image_width"),
                imageHeight = json.optInt("image_height"),
                imagePath = json.optString("image_path").ifBlank { null },
            )
        }
    }
}

data class CameraControl(
    val name: String,
    val type: String,
    val value: Int,
    val min: Int,
    val max: Int,
    val step: Int,
    val defaultValue: Int,
) {
    val label: String
        get() = formatControlLabel(name)

    val isToggle: Boolean
        get() = type == "bool" || (min == 0 && max == 1) || name.endsWith("_auto")

    companion object {
        fun fromJson(json: JSONObject): CameraControl {
            return CameraControl(
                name = json.optString("name"),
                type = json.optString("type", "int"),
                value = json.optInt("value"),
                min = json.optInt("min"),
                max = json.optInt("max"),
                step = json.optInt("step", 1).coerceAtLeast(1),
                defaultValue = json.optInt("default", json.optInt("value")),
            )
        }

        fun fallback(name: String, value: Int): CameraControl {
            val spec = FALLBACK_SPECS[name] ?: ControlSpec(0, 255, 1, value)
            return CameraControl(
                name = name,
                type = if (spec.max == 1) "bool" else "int",
                value = value.coerceIn(spec.min, spec.max),
                min = spec.min,
                max = spec.max,
                step = spec.step,
                defaultValue = spec.defaultValue,
            )
        }

        private data class ControlSpec(
            val min: Int,
            val max: Int,
            val step: Int,
            val defaultValue: Int,
        )

        private val FALLBACK_SPECS = mapOf(
            "brightness" to ControlSpec(0, 255, 1, 128),
            "contrast" to ControlSpec(0, 255, 1, 32),
            "saturation" to ControlSpec(0, 255, 1, 64),
            "sharpness" to ControlSpec(0, 255, 1, 128),
            "exposure_auto" to ControlSpec(0, 3, 1, 3),
            "exposure_absolute" to ControlSpec(3, 2047, 1, 200),
            "focus_auto" to ControlSpec(0, 1, 1, 1),
            "focus_absolute" to ControlSpec(0, 255, 1, 30),
            "white_balance_temperature_auto" to ControlSpec(0, 1, 1, 1),
            "white_balance_temperature" to ControlSpec(2800, 6500, 10, 4500),
            "gain" to ControlSpec(0, 255, 1, 0),
            "backlight_compensation" to ControlSpec(0, 1, 1, 0),
        )

        private fun formatControlLabel(name: String): String {
            return name.split('_')
                .joinToString(" ") { word ->
                    word.replaceFirstChar { char ->
                        if (char.isLowerCase()) char.titlecase() else char.toString()
                    }
                }
        }
    }
}

data class CameraControlsState(
    val supported: Boolean,
    val device: String?,
    val controls: List<CameraControl>,
    val configured: Map<String, Int>,
    val pixelFormat: String?,
    val reason: String?,
) {
    fun draftValues(): Map<String, Int> {
        if (controls.isEmpty()) {
            return configured
        }
        return controls.associate { control ->
            val configuredValue = configured[control.name]
            control.name to (configuredValue ?: control.value)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): CameraControlsState? {
            if (json.length() == 0 || json.has("error")) return null
            val controlsArray = json.optJSONArray("controls")
            val controls = buildList {
                if (controlsArray != null) {
                    for (index in 0 until controlsArray.length()) {
                        val item = controlsArray.optJSONObject(index) ?: continue
                        add(CameraControl.fromJson(item))
                    }
                }
            }
            val configuredObject = json.optJSONObject("configured")
            val configured = buildMap {
                configuredObject?.keys()?.forEach { key ->
                    put(key, configuredObject.optInt(key))
                }
            }
            return CameraControlsState(
                supported = json.optBoolean("supported", false),
                device = json.optString("device").ifBlank { null },
                controls = controls,
                configured = configured,
                pixelFormat = json.optString("pixel_format").ifBlank { null },
                reason = json.optString("reason").ifBlank { null },
            )
        }

        fun fromConfig(cameraSource: String?, configured: Map<String, Int>, pixelFormat: String?): CameraControlsState {
            val supported = cameraSource == "opencv"
            val names = if (configured.isNotEmpty()) {
                configured.keys.toList()
            } else {
                DEFAULT_EDITABLE_NAMES
            }
            val controls = names.map { name ->
                val seed = configured[name] ?: CameraControl.fallback(name, 0).defaultValue
                CameraControl.fallback(name, seed)
            }
            return CameraControlsState(
                supported = supported,
                device = null,
                controls = controls,
                configured = configured,
                pixelFormat = pixelFormat,
                reason = if (supported) {
                    "Connect WiFi to read live control ranges from the webcam."
                } else {
                    "V4L2 controls are only available for USB webcams (camera.source: opencv)."
                },
            )
        }

        private val DEFAULT_EDITABLE_NAMES = listOf(
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
        )
    }
}

fun v4l2ControlsPatch(values: Map<String, Int>, pixelFormat: String? = null): JSONObject {
    val controlsObject = JSONObject()
    values.forEach { (name, value) -> controlsObject.put(name, value) }
    val cameraObject = JSONObject().put("v4l2_controls", controlsObject)
    if (!pixelFormat.isNullOrBlank()) {
        cameraObject.put("pixel_format", pixelFormat)
    }
    return JSONObject().put("camera", cameraObject)
}

data class PicapConfig(
    val ocr: OcrConfig,
    val regions: List<CaptureRegion>,
    val cameraSource: String?,
    val cameraWidth: Int?,
    val cameraHeight: Int?,
    val regionsRefWidth: Int?,
    val regionsRefHeight: Int?,
    val v4l2Controls: Map<String, Int>,
    val pixelFormat: String?,
    val rawJson: String,
) {
    companion object {
        fun fromJson(json: JSONObject): PicapConfig? {
            if (json.length() == 0 || json.has("error")) return null

            val regionsArray = json.optJSONArray("regions")
            val regions = buildList {
                if (regionsArray != null) {
                    for (index in 0 until regionsArray.length()) {
                        val item = regionsArray.optJSONObject(index) ?: continue
                        add(CaptureRegion.fromJson(item))
                    }
                }
            }

            val camera = json.optJSONObject("camera")
            val resolution = camera?.optJSONArray("resolution")
            val cameraWidth = resolution?.optInt(0)?.takeIf { it > 0 }
            val cameraHeight = resolution?.optInt(1)?.takeIf { it > 0 }
            val regionsRef = json.optJSONArray("regions_ref")
            val regionsRefWidth = regionsRef?.optInt(0)?.takeIf { it > 0 }
            val regionsRefHeight = regionsRef?.optInt(1)?.takeIf { it > 0 }
            val v4l2ControlsObject = camera?.optJSONObject("v4l2_controls")
            val v4l2Controls = buildMap {
                v4l2ControlsObject?.keys()?.forEach { key ->
                    put(key, v4l2ControlsObject.optInt(key))
                }
            }

            return PicapConfig(
                ocr = OcrConfig.fromJson(json.optJSONObject("ocr")),
                regions = regions,
                cameraSource = camera?.optString("source").ifBlank { null },
                cameraWidth = cameraWidth,
                cameraHeight = cameraHeight,
                regionsRefWidth = regionsRefWidth,
                regionsRefHeight = regionsRefHeight,
                v4l2Controls = v4l2Controls,
                pixelFormat = camera?.optString("pixel_format").ifBlank { null },
                rawJson = json.toString(2),
            )
        }
    }
}

data class RegionReading(
    val name: String,
    val value: String?,
    val confidence: Double,
    val x: Int? = null,
    val y: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
) {
    val positionLabel: String?
        get() = if (x != null && y != null) "($x, $y)" else null

    fun toCropRegion(): CaptureRegion? {
        val regionX = x ?: return null
        val regionY = y ?: return null
        val regionWidth = width ?: return null
        val regionHeight = height ?: return null
        if (regionWidth <= 0 || regionHeight <= 0) {
            return null
        }
        return CaptureRegion(
            name = name,
            x = regionX,
            y = regionY,
            width = regionWidth,
            height = regionHeight,
            format = "time",
        )
    }
}

data class Reading(
    val id: Int?,
    val capturedAt: String,
    val imagePath: String?,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val values: Map<String, String?>,
    val readings: List<RegionReading>,
) {
    companion object {
        fun fromJson(json: JSONObject): Reading? {
            if (json.length() == 0) return null
            val capturedAt = json.optString("captured_at")
            if (capturedAt.isBlank()) return null

            val valuesObject = json.optJSONObject("values")
            val values = buildMap {
                valuesObject?.keys()?.forEach { key ->
                    val value = valuesObject.opt(key)
                    put(key, if (value == JSONObject.NULL) null else value?.toString())
                }
            }

            val readingsArray = json.optJSONArray("readings")
            val readings = readingsArray?.toRegionReadings().orEmpty()

            return Reading(
                id = json.optInt("id").takeIf { json.has("id") },
                capturedAt = capturedAt,
                imagePath = json.optString("image_path").ifBlank { null },
                imageWidth = json.optInt("image_width").takeIf { json.has("image_width") && it > 0 },
                imageHeight = json.optInt("image_height").takeIf { json.has("image_height") && it > 0 },
                values = values,
                readings = readings,
            )
        }

        fun listFromJsonArray(array: JSONArray): List<Reading> {
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    fromJson(item)?.let(::add)
                }
            }
        }
    }
}

data class CaptureState(
    val status: String,
    val message: String? = null,
    val result: Reading? = null,
) {
    val isBusy: Boolean
        get() = status == "capturing"

    companion object {
        fun fromJson(json: JSONObject): CaptureState {
            val result = json.optJSONObject("result")?.let(Reading::fromJson)
            return CaptureState(
                status = json.optString("status", "idle"),
                message = json.optString("message").ifBlank { null },
                result = result,
            )
        }
    }
}

enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
}

enum class ConnectionTransport {
    BLE,
    HTTP,
}

private fun JSONArray.toRegionReadings(): List<RegionReading> {
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(
                RegionReading(
                    name = item.optString("name"),
                    value = item.optString("value").ifBlank { null },
                    confidence = item.optDouble("confidence", 0.0),
                    x = item.optInt("x").takeIf { item.has("x") },
                    y = item.optInt("y").takeIf { item.has("y") },
                    width = item.optInt("width").takeIf { item.has("width") },
                    height = item.optInt("height").takeIf { item.has("height") },
                ),
            )
        }
    }
}

fun parseJsonObject(bytes: ByteArray): JSONObject {
    val text = bytes.toString(Charsets.UTF_8).trim()
    return if (text.isEmpty()) JSONObject() else JSONObject(text)
}

fun parseJsonObjectOrNull(bytes: ByteArray): JSONObject? {
    return try {
        parseJsonObject(bytes)
    } catch (_: Exception) {
        null
    }
}

fun parseJsonArray(bytes: ByteArray): JSONArray {
    val text = bytes.toString(Charsets.UTF_8).trim()
    return if (text.isEmpty()) JSONArray() else JSONArray(text)
}

fun parseJsonArrayOrNull(bytes: ByteArray): JSONArray? {
    return try {
        parseJsonArray(bytes)
    } catch (_: Exception) {
        null
    }
}
