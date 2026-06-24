package com.picap.mobile.api

import com.picap.mobile.data.CaptureState
import com.picap.mobile.data.ConnectionState
import com.picap.mobile.data.DeviceStatus
import com.picap.mobile.data.PicapConfig
import com.picap.mobile.data.Reading
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class PicapHttpClient(
    private val listener: PicapClient.Listener,
) : PicapClient {
    private val executor = Executors.newSingleThreadExecutor()
    private var baseUrl: String? = null
    private var apiKey: String? = null

    fun connect(hostPort: String, apiKey: String? = null) {
        val normalized = hostPort.trim().removeSuffix("/")
        baseUrl = when {
            normalized.startsWith("http://") || normalized.startsWith("https://") -> normalized
            else -> "http://$normalized"
        }
        this.apiKey = apiKey?.trim()?.ifBlank { null }
        listener.onConnectionStateChanged(ConnectionState.CONNECTING)
        executor.execute {
            try {
                val status = requestJson("GET", "/api/status")
                listener.onStatusUpdated(DeviceStatus.fromJson(status))
                listener.onConnectionStateChanged(ConnectionState.CONNECTED)
                refreshAll()
            } catch (exc: Exception) {
                baseUrl = null
                listener.onConnectionStateChanged(ConnectionState.DISCONNECTED)
                listener.onError(exc.message ?: "HTTP connection failed")
            }
        }
    }

    override fun disconnect() {
        baseUrl = null
        listener.onConnectionStateChanged(ConnectionState.DISCONNECTED)
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
}
