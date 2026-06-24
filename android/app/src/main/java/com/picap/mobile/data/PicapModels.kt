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
) {
    companion object {
        fun fromJson(json: JSONObject): DeviceStatus? {
            if (json.length() == 0) return null
            return DeviceStatus(
                ready = json.optBoolean("ready", false),
                lastCaptureAt = json.optString("last_capture_at").ifBlank { null },
                lastError = json.optString("last_error").ifBlank { null },
                cameraSource = json.optString("camera_source", "unknown"),
                ocrMode = json.optString("ocr_mode", "auto"),
            )
        }
    }
}

data class OcrConfig(
    val mode: String = "auto",
    val minConfidence: Int = 60,
    val minDigits: Int = 1,
    val upscaleFactor: Double = 2.0,
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
                    .put("auto_psm", autoPsm)
                    .put("merge_line_tolerance", mergeLineTolerance)
                    .put("merge_gap_tolerance", mergeGapTolerance),
            )
    }

    companion object {
        fun fromJson(json: JSONObject?): OcrConfig {
            val ocr = json ?: JSONObject()
            return OcrConfig(
                mode = ocr.optString("mode", "auto"),
                minConfidence = ocr.optInt("min_confidence", 60),
                minDigits = ocr.optInt("min_digits", 1),
                upscaleFactor = ocr.optDouble("upscale_factor", 2.0),
                autoPsm = ocr.optInt("auto_psm", 11),
                mergeLineTolerance = ocr.optInt("merge_line_tolerance", 15),
                mergeGapTolerance = ocr.optInt("merge_gap_tolerance", 30),
            )
        }
    }
}

data class PicapConfig(
    val ocr: OcrConfig,
    val rawJson: String,
) {
    companion object {
        fun fromJson(json: JSONObject): PicapConfig? {
            if (json.length() == 0 || json.has("error")) return null
            return PicapConfig(
                ocr = OcrConfig.fromJson(json.optJSONObject("ocr")),
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
}

data class Reading(
    val id: Int?,
    val capturedAt: String,
    val imagePath: String?,
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

fun parseJsonArray(bytes: ByteArray): JSONArray {
    val text = bytes.toString(Charsets.UTF_8).trim()
    return if (text.isEmpty()) JSONArray() else JSONArray(text)
}
