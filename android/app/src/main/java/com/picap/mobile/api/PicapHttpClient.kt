package com.picap.mobile.api

import com.picap.mobile.data.AutoCalibrateResult
import com.picap.mobile.data.CaptureState
import com.picap.mobile.data.ConnectionState
import com.picap.mobile.data.DeviceStatus
import com.picap.mobile.data.PicapConfig
import com.picap.mobile.data.Reading
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors

class PicapHttpClient(
    private val listener: PicapClient.Listener,
) : PicapClient {
    private val executor = Executors.newSingleThreadExecutor()
    private var baseUrl: String? = null
    private var apiKey: String? = null
    private var linkOnly = false

    fun connect(hostPort: String, apiKey: String? = null) {
        linkOnly = false
        beginConnection(hostPort, apiKey, primary = true)
    }

    fun linkHttp(hostPort: String, apiKey: String? = null) {
        linkOnly = true
        beginConnection(hostPort, apiKey, primary = false)
    }

    fun unlinkHttp() {
        if (!linkOnly) {
            return
        }
        baseUrl = null
        linkOnly = false
        listener.onHttpLinkStateChanged(linking = false, linked = false, host = null)
    }

    private fun beginConnection(hostPort: String, apiKey: String?, primary: Boolean) {
        val normalized = normalizeBaseUrl(hostPort)
        baseUrl = normalized
        this.apiKey = apiKey?.trim()?.ifBlank { null }
        if (primary) {
            listener.onConnectionStateChanged(ConnectionState.CONNECTING)
        } else {
            listener.onHttpLinkStateChanged(linking = true, linked = false, host = hostDisplay(hostPort))
        }
        executor.execute {
            try {
                val status = requestJson("GET", "/api/status")
                listener.onStatusUpdated(DeviceStatus.fromJson(status))
                if (primary) {
                    listener.onConnectionStateChanged(ConnectionState.CONNECTED)
                    refreshAll()
                } else {
                    listener.onHttpLinkStateChanged(
                        linking = false,
                        linked = true,
                        host = hostDisplay(hostPort),
                    )
                }
            } catch (exc: Exception) {
                baseUrl = null
                linkOnly = false
                if (primary) {
                    listener.onConnectionStateChanged(ConnectionState.DISCONNECTED)
                    listener.onError(connectionErrorMessage(hostPort, exc))
                } else {
                    listener.onHttpLinkStateChanged(linking = false, linked = false, host = null)
                    listener.onError(connectionErrorMessage(hostPort, exc))
                }
            }
        }
    }

    override fun disconnect() {
        val wasLinkOnly = linkOnly
        baseUrl = null
        linkOnly = false
        if (wasLinkOnly) {
            listener.onHttpLinkStateChanged(linking = false, linked = false, host = null)
        } else {
            listener.onConnectionStateChanged(ConnectionState.DISCONNECTED)
        }
    }

    override fun refreshStatus() {
        enqueue {
            listener.onStatusUpdated(DeviceStatus.fromJson(requestJson("GET", "/api/status")))
        }
    }

    override fun refreshLatest() {
        enqueue {
            listener.onLatestReading(Reading.fromJson(requestJson("GET", "/api/latest")))
        }
    }

    override fun refreshHistory(limit: Int, offset: Int) {
        enqueue {
            val response = request("GET", "/api/history?limit=$limit&offset=$offset")
            val array = JSONArray(response)
            listener.onHistoryUpdated(Reading.listFromJsonArray(array))
        }
    }

    override fun refreshConfig() {
        enqueue {
            listener.onConfigUpdated(PicapConfig.fromJson(requestJson("GET", "/api/config")))
        }
    }

    override fun updateConfig(patchJson: String) {
        enqueue {
            val json = requestJson("PATCH", "/api/config", patchJson)
            if (json.has("error")) {
                listener.onError(json.optString("error"))
            } else {
                listener.onConfigUpdated(PicapConfig.fromJson(json))
            }
        }
    }

    override fun triggerCapture() {
        enqueue {
            listener.onCaptureStateUpdated(CaptureState(status = "capturing"))
            try {
                val json = requestJson("POST", "/api/capture")
                if (json.has("error")) {
                    listener.onCaptureStateUpdated(
                        CaptureState(status = "error", message = json.optString("error")),
                    )
                    return@enqueue
                }
                val reading = Reading.fromJson(json)
                listener.onCaptureStateUpdated(CaptureState(status = "complete", result = reading))
                listener.onLatestReading(reading)
                refreshHistory()
            } catch (exc: Exception) {
                listener.onCaptureStateUpdated(
                    CaptureState(status = "error", message = exc.message),
                )
            }
        }
    }

    override fun autoCalibrateRegions(source: String) {
        enqueue {
            try {
                val body = JSONObject().put("source", source).toString()
                val json = requestJson("POST", "/api/regions/auto-calibrate", body)
                if (json.has("error")) {
                    listener.onAutoCalibrateFailed(json.optString("error"))
                    return@enqueue
                }
                val result = AutoCalibrateResult.fromJson(json)
                    ?: run {
                        listener.onAutoCalibrateFailed("Auto-calibrate returned no regions")
                        return@enqueue
                    }
                listener.onAutoCalibrateComplete(result)
            } catch (exc: Exception) {
                listener.onAutoCalibrateFailed(exc.message ?: "Auto-calibrate failed")
                return@enqueue
            }
        }
    }

    private fun refreshAll() {
        refreshStatus()
        refreshLatest()
        refreshHistory()
        refreshConfig()
    }

    private fun enqueue(block: () -> Unit) {
        if (baseUrl == null) {
            listener.onError("Not connected")
            return
        }
        executor.execute {
            try {
                block()
            } catch (exc: Exception) {
                listener.onError(exc.message ?: "Request failed")
            }
        }
    }

    private fun requestJson(method: String, path: String, body: String? = null): JSONObject {
        val response = request(method, path, body)
        return if (response.isBlank()) JSONObject() else JSONObject(response)
    }

    private fun request(method: String, path: String, body: String? = null): String {
        val url = baseUrl ?: throw IllegalStateException("Not connected")
        val connection = (URL("$url$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            apiKey?.let { setRequestProperty("X-API-Key", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { stream -> stream.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }

        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }

        val text = stream?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        }.orEmpty()

        if (connection.responseCode !in 200..299) {
            val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
                ?.ifBlank { null }
                ?: "HTTP ${connection.responseCode}"
            throw IllegalStateException(message)
        }
        return text
    }

    private fun normalizeBaseUrl(hostPort: String): String {
        val normalized = hostPort.trim().removeSuffix("/")
        return when {
            normalized.startsWith("http://") || normalized.startsWith("https://") -> normalized
            else -> "http://$normalized"
        }
    }

    private fun hostDisplay(hostPort: String): String {
        return hostPort.trim()
            .removeSuffix("/")
            .removePrefix("http://")
            .removePrefix("https://")
    }

    private fun connectionErrorMessage(hostPort: String, exc: Exception): String {
        return when (exc) {
            is UnknownHostException ->
                "Cannot resolve $hostPort — check the Pi IP address."
            is ConnectException ->
                "Cannot reach $hostPort — wrong IP, Pi not running, or phone/Pi on different networks."
            is SocketTimeoutException ->
                "Timed out reaching $hostPort — check WiFi and router client isolation settings."
            else -> exc.message ?: "HTTP connection failed"
        }
    }
}
