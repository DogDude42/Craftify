package net.gravtech

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
import kotlinx.coroutines.launch

/**
 * YTMWebController - Connects to Chrome/Thorium YouTube Music via WebSocket bridge.
 * Fabric 26.1.2 compatible. Uses OkHttp for WebSocket client.
 */
class YTMWebController(private val bridgeUrl: String = "ws://localhost:8765/youtube-music") {
    private val log = LoggerFactory.getLogger("Craftify/YTMWebController")
    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val listeners = mutableListOf<YTMStateListener>()
    private var reconnectAttempts = 0
    private const val MAX_RECONNECT_ATTEMPTS = 10
    private const val RECONNECT_DELAY_MS = 5000L

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
                parseState(bytes.decodeUtf8())
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
            val delay = RECONNECT_DELAY_MS * reconnectAttempts
            log.info("Reconnecting in ${delay}ms (attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)")
            scope.launch {
                kotlinx.coroutines.delay(delay)
                connect()
            }
        } else {
            log.error("Max reconnect attempts reached")
        }
    }

    private fun parseState(json: String) {
        try {
            // Simple JSON parsing - in production use kotlinx.serialization or Gson
            val state = YTMState(
                playing = json.contains(""playing":true"),
                title = extractJson(json, "title"),
                artist = extractJson(json, "artist"),
                album = extractJson(json, "album"),
                videoId = extractJson(json, "videoId")
            )
            listeners.forEach { it.onStateChanged(state) }
        } catch (e: Exception) {
            log.warn("Failed to parse YTM state: $e")
        }
    }

    private fun extractJson(json: String, key: String): String {
        val pattern = ""$key"\s*:\s*"([^"]*)"".toRegex()
        return pattern.find(json)?.groupValues?.get(1) ?: ""
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
    fun seek(position: Long) = sendCommand("seek", ""position":$position")
    fun setVolume(volume: Float) = sendCommand("volume", ""volume":$volume")

    private fun sendCommand(action: String, extra: String = "") {
        val json = "{\"action\":\"$action\"${if (extra.isNotEmpty()) ",$extra" else ""}}"
        webSocket?.send(json) ?: log.warn("WebSocket not connected, command dropped: $action")
    }

    fun shutdown() {
        webSocket?.close(1000, "Mod shutting down")
        client.dispatcher().executorService.shutdown()
        scope.coroutineContext.cancel()
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
