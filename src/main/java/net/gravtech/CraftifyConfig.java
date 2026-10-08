package net.gravtech;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Craftify config - ported from upstream's Vigilance TOML config
 * (config/craftify.toml) to plain JSON (config/craftify-ytm-web.json),
 * plus new options for the YTM web bridge.
 *
 * Ported options (upstream equivalents in comments) - the upstream options
 * that only made sense for its multi-service architecture (musicService,
 * linkMode, allowedServers, marqueeSpeed...) are dropped or reworked.
 */
public final class CraftifyConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ---- General ----
    /** musicService -> always "youtube-web" now, but kept for future services. */
    public boolean enabled = true;                       // upstream: musicService != disabled
    public String bridgeUrl = "ws://localhost:8765/youtube-music"; // NEW: browser bridge endpoint

    // ---- Chat announcements (upstream: announcementEnabled/Message) ----
    public boolean announcementEnabled = false;
    public String announcementMessage = "\u00A7aCraftify > \u00A77Now Playing: \u00A7b${song} by ${artists}";

    // ---- HUD / Rendering (upstream: anchorPoint, xOffset, yOffset, displayMode...) ----
    /** Widget position+size - persisted here AND live-managed by YtmHud drag/resize. */
    public int widgetX = 5;
    public int widgetY = 5;
    public int widgetW = 240;
    public int widgetH = 68;

    /** upstream: displayMode (WHEN_SONG_FOUND / ALWAYS / NEVER). */
    public String displayMode = "SONG"; // SONG | ALWAYS | NEVER
    /** upstream: premiumControl -> controls on hover. */
    public boolean showControls = true;
    /** upstream: renderType (NON_INTRUSIVE / ALWAYS / NEVER). */
    public boolean showInGuis = true;   // NON_INTRUSIVE == false hides in GUIs
    /** upstream: anchorPoint relative positioning on top of raw x/y. */
    public boolean showAlbumArt = true;  // NEW
    /** NEW: accent color preset. */
    public String accentColor = "pink";  // pink | blue | green | mauve | red | yellow

    // ---- internals ----
    private static final Object IO_LOCK = new Object();

    private static CraftifyConfig instance;

    public static CraftifyConfig get() {
        if (instance == null) load();
        return instance;
    }

    public static Path path() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("craftify-ytm-web.json");
    }

    public static void load() {
        synchronized (IO_LOCK) {
            try {
                Path p = path();
                if (Files.exists(p)) {
                    instance = GSON.fromJson(
                            new String(Files.readAllBytes(p), StandardCharsets.UTF_8),
                            CraftifyConfig.class);
                }
            } catch (Exception e) {
                Craftify.LOGGER.warn("config load failed: {}", String.valueOf(e));
            }
            if (instance == null) instance = new CraftifyConfig();
        }
    }

    public static void save() {
        synchronized (IO_LOCK) {
            try {
                Path p = path();
                Files.createDirectories(p.getParent());
                Files.write(p, GSON.toJson(get()).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Craftify.LOGGER.warn("config save failed: {}", String.valueOf(e));
            }
        }
    }

    /** Apply a change and persist immediately (used by the config screen). */
    public static void update(java.util.function.Consumer<CraftifyConfig> mutator) {
        mutator.accept(get());
        save();
    }

    // ------------------------------------------------------------ helpers

    public int accentRgb() {
        // Catppuccin Mocha accents
        switch (accentColor == null ? "pink" : accentColor.toLowerCase()) {
            case "blue":   return 0xFF89B4FA;
            case "green":  return 0xFFA6E3A1;
            case "mauve":  return 0xFFCBA6F7;
            case "red":    return 0xFFF38BA8;
            case "yellow": return 0xFFF9E2AF;
            case "pink":
            default:       return 0xFFF5C2E7;
        }
    }

    public enum DisplayMode { SONG, ALWAYS, NEVER }

    public DisplayMode displayModeEnum() {
        try {
            return DisplayMode.valueOf(displayMode == null ? "SONG" : displayMode);
        } catch (IllegalArgumentException e) {
            return DisplayMode.SONG;
        }
    }
}
