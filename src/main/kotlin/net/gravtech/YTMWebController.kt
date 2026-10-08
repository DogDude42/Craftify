package net.gravtech

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.jetbrains.annotations.NotNull
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/**
 * YTMWebController - Connects to Chrome/Thorium YouTube Music via WebSocket bridge.
 * Fabric 1.21.11 (26.1.2) compatible. Uses OkHttp for WebSocket client.
 */
class YTMWebController(private val bridgeUrl: String = "ws://localhost:8765/youtube-music") {
    private val log = LoggerFactory.getLogger("Craftify/YTMWebController")
    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val listeners = mutableListOf<YTMStateListener>()
    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 10
    private val RECONNECT_DELAY_MS = 5000L
    private val gson = Gson()

    interface YTMStateListener {
        fun onStateChanged(state: YTMState)
        fun onConnected()
        fun onDisconnected(reason: String?)
    }

    data class YTMState(
        val playing: Boolean = false,
        val title: String = "Unknown",
        val artist: String = "Unknown",
        val album: String = "Unknown",
        val duration: Long = 0,
        val position: Long = 0,
        val videoId: String = ""
    )

    init {
        connect()
    }

    private fun connect() {
        val request = Request.Builder().url(bridgeUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(@NotNull webSocket: WebSocket, @NotNull response: Response) {
                log.info("Connected to Chrome/Thorium YTM bridge at $bridgeUrl")
                reconnectAttempts = 0
                listeners.forEach { it.onConnected() }
            }

            override fun onMessage(@NotNull webSocket: WebSocket, text: String) {
                parseState(text)
            }

            override fun onMessage(@NotNull webSocket: WebSocket, bytes: ByteString) {
                parseState(bytes.utf8())
            }

            override fun onClosing(@NotNull webSocket: WebSocket, code: Int, @NotNull reason: String) {
                webSocket.close(1000, "Client closing")
                log.info("WebSocket closing: $code - $reason")
            }

            override fun onClosed(@NotNull webSocket: WebSocket, code: Int, @NotNull reason: String) {
                log.warn("WebSocket closed: $code - $reason")
                listeners.forEach { it.onDisconnected(reason) }
                scheduleReconnect()
            }

            override fun onFailure(@NotNull webSocket: WebSocket, @NotNull t: Throwable, response: Response?) {
                log.error("WebSocket failure: ${t.message}")
                listeners.forEach { it.onDisconnected(t.message) }
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            val delayMs = RECONNECT_DELAY_MS * reconnectAttempts
            log.info("Reconnecting in ${delayMs}ms (attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)")
            scope.launch {
                delay(delayMs)
                connect()
            }
        } else {
            log.error("Max reconnect attempts reached")
        }
    }

    private fun parseState(json: String) {
        try {
            val jsonObj = JsonParser.parseString(json).asJsonObject
            val state = YTMState(
                playing = jsonObj.get("playing")?.asBoolean ?: false,
                title = jsonObj.get("title")?.asString ?: "Unknown",
                artist = jsonObj.get("artist")?.asString ?: "Unknown",
                album = jsonObj.get("album")?.asString ?: "Unknown",
                duration = jsonObj.get("duration")?.asLong ?: 0L,
                position = jsonObj.get("position")?.asLong ?: 0L,
                videoId = jsonObj.get("videoId")?.asString ?: ""
            )
            listeners.forEach { it.onStateChanged(state) }
        } catch (e: Exception) {
            log.warn("Failed to parse YTM state: $e")
        }
    }

    fun addListener(listener: YTMStateListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: YTMStateListener) {
        listeners.remove(listener)
    }

    fun play() = sendCommand("play")
    fun pause() = sendCommand("pause")
    fun nextTrack() = sendCommand("next")
    fun previousTrack() = sendCommand("prev")
    fun seek(position: Long) = sendCommand("seek", JsonObject().apply { addProperty("position", position) })
    fun setVolume(volume: Float) = sendCommand("volume", JsonObject().apply { addProperty("volume", volume) })

    private fun sendCommand(action: String, extra: JsonObject? = null) {
        val json = JsonObject().apply {
            addProperty("action", action)
            if (extra != null) {
                extra.asMap().forEach { (k, v) -> add(k, v) }
            }
        }
        webSocket?.send(gson.toJson(json)) ?: log.warn("WebSocket not connected, command dropped: $action")
    }

    fun shutdown() {
        webSocket?.close(1000, "Mod shutting down")
        // Use dispatcher property instead of deprecated dispatcher() function
        client.dispatcher.executorService.shutdown()
        job.cancel()
    }

    companion object {
        @Volatile
        private var INSTANCE: YTMWebController? = null
        
        fun getInstance(bridgeUrl: String = "ws://localhost:8765/youtube-music"): YTMWebController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: YTMWebController(bridgeUrl).also { INSTANCE = it }
            }
        }
    }
}
