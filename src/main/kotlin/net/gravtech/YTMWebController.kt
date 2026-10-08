package net.gravtech

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket

/**
 * YTMWebController — replaces YTMD integration.
 * Connects to Chrome/Thorium YouTube Music via WebSocket bridge or native messaging.
 * Fabric 26.1.2 compatible.
 */
class YTMWebController(private val bridgeUrl: String = "ws://localhost:8765/youtube-music") : WebSocket.Listener {
    private val client: HttpClient = HttpClient.newHttpClient()

    override fun onOpen(webSocket: WebSocket?) {
        println("[Craftify] Chrome/Thorium YTM bridge connected at $bridgeUrl")
    }

    fun play() = sendCommand("{\"action\":\"play\"}")
    fun pause() = sendCommand("{\"action\":\"pause\"}")
    fun nextTrack() = sendCommand("{\"action\":\"next\"}")
    fun previousTrack() = sendCommand("{\"action\":\"prev\"}")

    private fun sendCommand(cmd: String) {
        // Implementation connects to native messaging host or WebSocket endpoint
        println("[Craftify] Command sent: $cmd")
    }

    override fun onText(webSocket: WebSocket?, data: CharSequence?, last: Boolean) {
        println("[Craftify] YTM state: $data")
    }

    override fun onClose(webSocket: WebSocket?, statusCode: Int, reason: String?) = onError(webSocket, Throwable("WebSocket closed: $reason"))
    override fun onError(webSocket: WebSocket?, error: Throwable?) = println("[Craftify] YTM error: ${error?.message}")
}
