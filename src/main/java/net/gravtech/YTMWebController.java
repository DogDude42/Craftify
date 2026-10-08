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
        public final long duration;
        public final long position;
        public final String videoId;

        public YTMState(boolean playing, String title, String artist, String album,
                         long duration, long position, String videoId) {
            this.playing = playing;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.duration = duration;
            this.position = position;
            this.videoId = videoId;
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
                reconnectAttempts = 0;
                log.info("Connected to Chrome/Thorium YTM bridge at {}", bridgeUrl);
                notifyConnected();
                ws.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                parseState(data.toString());
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
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            log.error("Max reconnect attempts reached - YTM bridge unavailable");
            return;
        }
        reconnectAttempts++;
        long delay = RECONNECT_DELAY_MS * reconnectAttempts;
        log.info("Reconnecting in {}ms (attempt {}/{})", delay, reconnectAttempts, MAX_RECONNECT_ATTEMPTS);
        scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    private void parseState(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            YTMState state = new YTMState(
                    getAsBool(obj, "playing", false),
                    getAsString(obj, "title", "Unknown"),
                    getAsString(obj, "artist", "Unknown"),
                    getAsString(obj, "album", "Unknown"),
                    getAsLong(obj, "duration", 0L),
                    getAsLong(obj, "position", 0L),
                    getAsString(obj, "videoId", ""));
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
