package net.gravtech;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * YTMWebController - connects to a Chrome/Thorium YouTube Music bridge over WebSocket.
 * Pure-JDK implementation (java.net.http) - no external runtime dependencies.
 * Gson is provided by Minecraft itself.
 */
public final class YTMWebController {
    private static final Logger log = LoggerFactory.getLogger("Craftify/YTMWebController");
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long RECONNECT_DELAY_MS = 5000L;

    public interface YTMStateListener {
        void onStateChanged(YTMState state);
        void onConnected();
        void onDisconnected(String reason);
    }

    public static final class YTMState {
        public final boolean playing;
        public final String title;
        public final String artist;
        public final String album;
        /** Track length in MILLISECONDS (sub-second smooth clock). */
        public final long durationMs;
        /** Playhead in MILLISECONDS. */
        public final long positionMs;
        public final String videoId;
        public final String albumArt;
        /** Canvas-re-encoded PNG data URL (webp-proof album art). */
        public final String albumArtPng;
        /** Repeat state: "NONE", "ALL" (repeat queue), or "ONE" (repeat song). */
        public final String loopMode;
        /** Shuffle toggle state. */
        public final boolean shuffle;

        public YTMState(boolean playing, String title, String artist, String album,
                         long durationMs, long positionMs, String videoId,
                         String albumArt, String albumArtPng,
                         String loopMode, boolean shuffle) {
            this.playing = playing;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.durationMs = durationMs;
            this.positionMs = positionMs;
            this.videoId = videoId;
            this.albumArt = albumArt == null ? "" : albumArt;
            this.albumArtPng = albumArtPng == null ? "" : albumArtPng;
            this.loopMode = loopMode == null || loopMode.isEmpty() ? "NONE" : loopMode;
            this.shuffle = shuffle;
        }
    }

    private final String bridgeUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<YTMStateListener> listeners =
            Collections.synchronizedList(new ArrayList<>());
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
    private final Gson gson = new Gson();

    private volatile WebSocket webSocket;
    private int reconnectAttempts = 0;
    private volatile YTMState lastState;
    private volatile boolean firstStateLogged = false;

    private YTMWebController(String bridgeUrl) {
        this.bridgeUrl = bridgeUrl;
        connect();
    }

    public static YTMWebController getInstance(String bridgeUrl) {
        return Holder.INSTANCE != null ? Holder.INSTANCE : create(bridgeUrl);
    }

    private static synchronized YTMWebController create(String bridgeUrl) {
        if (Holder.INSTANCE == null) {
            Holder.INSTANCE = new YTMWebController(bridgeUrl);
        }
        return Holder.INSTANCE;
    }

    private static final class Holder {
        static volatile YTMWebController INSTANCE;
    }

    private void connect() {
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket ws) {
                webSocket = ws;
                partial = null;
                reconnectAttempts = 0;
                log.info("Connected to Chrome/Thorium YTM bridge at {}", bridgeUrl);
                notifyConnected();
                requestState();
                ws.request(1);
            }

            private StringBuilder partial = null;

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                // Large messages (album art data URLs are 100s of KB) arrive
                // SPLIT across many frames. Only parse once the final frame
                // (last==true) lands; previously every fragment was parsed as
                // a complete message -> JsonSyntaxException on every state.
                if (last) {
                    if (partial == null) {
                        parseState(data.toString());
                    } else {
                        partial.append(data);
                        parseState(partial.toString());
                        partial = null;
                    }
                } else {
                    if (partial == null) partial = new StringBuilder();
                    partial.append(data);
                }
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                log.warn("WebSocket closed: {} - {}", statusCode, reason);
                notifyDisconnected(reason);
                scheduleReconnect();
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                log.error("WebSocket failure: {}", String.valueOf(error));
                notifyDisconnected(String.valueOf(error));
                scheduleReconnect();
            }
        };

        http.newWebSocketBuilder()
                .buildAsync(URI.create(bridgeUrl), listener)
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        log.error("Failed to connect to YTM bridge: {}", String.valueOf(err));
                        notifyDisconnected(String.valueOf(err));
                        scheduleReconnect();
                    }
                });
    }

    private synchronized void scheduleReconnect() {
        reconnectAttempts++;
        // Linear backoff capped at 30s; retry forever - the browser/bridge
        // may be closed for hours and the mod should reconnect when it's back.
        long delay = Math.min(RECONNECT_DELAY_MS * reconnectAttempts, 30_000L);
        log.info("Reconnecting in {}ms (attempt {})", delay, reconnectAttempts);
        scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    private void parseState(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            long durationMs = getAsLong(obj, "durationMs", 0L);
            long positionMs = getAsLong(obj, "positionMs", 0L);
            if (durationMs <= 0) durationMs = getAsLong(obj, "duration", 0L) * 1000L;
            if (positionMs <= 0) positionMs = getAsLong(obj, "position", 0L) * 1000L;
            YTMState state = new YTMState(
                    getAsBool(obj, "playing", false),
                    getAsString(obj, "title", "Unknown"),
                    getAsString(obj, "artist", "Unknown"),
                    getAsString(obj, "album", "Unknown"),
                    durationMs,
                    positionMs,
                    getAsString(obj, "videoId", ""),
                    getAsString(obj, "albumArt", ""),
                    getAsString(obj, "albumArtPng", ""),
                    getAsString(obj, "loopMode", "NONE"),
                    getAsBool(obj, "shuffle", false));
            this.lastState = state;
            if (firstStateLogged == false) {
                firstStateLogged = true;
                log.info("First YTM state received: {} - {} [{}]",
                        state.title, state.artist,
                        state.playing ? "playing" : "paused");
            }
            List<YTMStateListener> snapshot = snapshotListeners();
            for (YTMStateListener l : snapshot) {
                try { l.onStateChanged(state); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.warn("Failed to parse YTM state: {}", String.valueOf(e));
        }
    }

    private void notifyConnected() {
        for (YTMStateListener l : snapshotListeners()) {
            try { l.onConnected(); } catch (Exception ignored) {}
        }
    }

    private void notifyDisconnected(String reason) {
        for (YTMStateListener l : snapshotListeners()) {
            try { l.onDisconnected(reason); } catch (Exception ignored) {}
        }
    }

    private List<YTMStateListener> snapshotListeners() {
        synchronized (listeners) {
            return new ArrayList<>(listeners);
        }
    }

    /** Most recent state pushed by the extension (null if none yet). */
    public YTMState lastState() {
        return lastState;
    }

    /** True if the WebSocket to the bridge is currently open. */
    public boolean isConnected() {
        WebSocket ws = webSocket;
        return ws != null && !ws.isOutputClosed();
    }

    /** Ask the bridge/extension to push a fresh state right now.
     *  Sent on connect; the bridge relays it to the extension, which
     *  polls the YTM tab. */
    public void requestState() {
        JsonObject json = new JsonObject();
        json.addProperty("action", "request-state");
        WebSocket ws = webSocket;
        if (ws != null) {
            try {
                ws.sendText(gson.toJson(json), true);
            } catch (IllegalStateException ignored) {
            }
        }
    }

    public void addListener(YTMStateListener listener) {
        listeners.add(listener);
    }

    public void removeListener(YTMStateListener listener) {
        listeners.remove(listener);
    }

    public void play() { sendCommand("play", null); }
    public void pause() { sendCommand("pause", null); }
    public void nextTrack() { sendCommand("next", null); }
    public void previousTrack() { sendCommand("prev", null); }

    /** Generic command hook for HUD toggle buttons (loop / shuffle / ...). */
    public void sendCommand(String action) {
        sendCommand(action, null);
    }

    public void seek(long position) {
        JsonObject o = new JsonObject();
        o.addProperty("position", position);
        sendCommand("seek", o);
    }

    public void setVolume(float volume) {
        JsonObject o = new JsonObject();
        o.addProperty("volume", volume);
        sendCommand("volume", o);
    }

    private void sendCommand(String action, JsonObject extra) {
        JsonObject json = new JsonObject();
        json.addProperty("action", action);
        if (extra != null) {
            for (String key : extra.keySet()) {
                json.add(key, extra.get(key));
            }
        }
        WebSocket ws = webSocket;
        if (ws != null) {
            try {
                ws.sendText(gson.toJson(json), true);
            } catch (IllegalStateException e) {
                log.warn("WebSocket not connected, command dropped: {}", action);
            }
        } else {
            log.warn("WebSocket not connected, command dropped: {}", action);
        }
    }

    public void shutdown() {
        WebSocket ws = webSocket;
        if (ws != null) {
            try { ws.sendClose(1000, "Mod shutting down"); } catch (Exception ignored) {}
        }
        scheduler.shutdownNow();
    }

    private static boolean getAsBool(JsonObject obj, String key, boolean def) {
        try {
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) return obj.get(key).getAsBoolean();
        } catch (Exception ignored) {}
        return def;
    }

    private static String getAsString(JsonObject obj, String key, String def) {
        try {
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) return obj.get(key).getAsString();
        } catch (Exception ignored) {}
        return def;
    }

    private static long getAsLong(JsonObject obj, String key, long def) {
        try {
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) return obj.get(key).getAsLong();
        } catch (Exception ignored) {}
        return def;
    }
}
