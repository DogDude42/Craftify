package net.gravtech.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.gravtech.Craftify;
import net.gravtech.CraftifyClient;
import net.gravtech.CraftifyConfig;
import net.gravtech.YTMWebController;
import net.gravtech.YTMWebController.YTMState;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * Craftify player HUD for MC 26.1.2.
 *
 *  - Album art via the player-bar <img> URL -> center-cropped square ->
 *    DynamicTexture -> innerBlit with CORRECT UVs (regionW/regionH = texW/texH)
 *  - Sub-second timekeeping: position comes from the extension in
 *    milliseconds, so the clock doesn't skip when updates arrive late
 *  - Scalable text: everything inside the panel renders under a pose scale
 *    (auto-fit factor from widget size), so a small widget still fits
 *  - Layout: [art | icon+title / artist / time ... bar] - time sits directly
 *    above the seek bar, right-aligned
 *  - Draggable + resizable (persisted), hover controls with correct
 *    play/pause action glyphs, Catppuccin Mocha + configurable accent
 */
public final class YtmHud {

    private static final Identifier ELEMENT_ID =
            Identifier.fromNamespaceAndPath(Craftify.MOD_ID, "ytm_player");
    private static final Identifier ALBUM_TEX_ID =
            Identifier.fromNamespaceAndPath(Craftify.MOD_ID, "ytm_album_art");

    // ---- Catppuccin Mocha base palette (accent comes from config) ----
    private static final int COLOR_BG            = 0xF01E1E2E;
    private static final int COLOR_BG_HOVER     = 0xF0181825;
    private static final int COLOR_TITLE        = 0xFFCDD6F4;
    private static final int COLOR_ARTIST       = 0xFFA6ADC8;
    private static final int COLOR_BUTTON       = 0xFF45475A;
    private static final int COLOR_BUTTON_HOVER = 0xFF585B70;
    private static final int COLOR_BUTTON_TEXT  = 0xFFCDD6F4;

    // interaction state
    private static boolean hovered;
    private static boolean dragging;
    private static boolean resizing;
    private static double dragOffX, dragOffY;
    /** Clicks are consumed ONLY on frames where the widget actually drew. */
    private static volatile boolean drewLastFrame;

    /** Session-only hide toggle (H keybind). */
    private static boolean hiddenBySession = false;

    // album art
    private static volatile String loadedArtUrl = "";
    private static volatile boolean textureRegistered;
    private static volatile int artTexW = 1;
    private static volatile int artTexH = 1;
    private static final HttpClient ART_HTTP = HttpClient.newHttpClient();

    private YtmHud() {}

    // ---------------------------------------------------------------- setup

    public static void register() {
        HudElementRegistry.addLast(ELEMENT_ID, YtmHud::render);
    }

    public static void toggleVisible() {
        hiddenBySession = !hiddenBySession;
    }

    // ---------------------------------------------------------------- render

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return;

        CraftifyClient.handleKeybinds();

        CraftifyConfig cfg = CraftifyConfig.get();
        drewLastFrame = false;
        if (!cfg.enabled || hiddenBySession) return;
        if (cfg.displayModeEnum() == CraftifyConfig.DisplayMode.NEVER) return;

        YTMWebController controller = Craftify.getYtmController();
        YTMState raw = controller == null ? null : controller.lastState();
        if (raw == null) {
            if (controller != null && controller.isConnected()) {
                g.fill(cfg.widgetX, cfg.widgetY, cfg.widgetX + 110, cfg.widgetY + 14, 0xD01E1E2E);
                g.text(mc.font, "YTM: waiting for browser...",
                        cfg.widgetX + 4, cfg.widgetY + 3, COLOR_ARTIST);
            }
            return;
        }

        // Sub-second-smooth position: extrapolate in ms since last update
        YTMState state = advancePosition(raw);

        Window window = mc.getWindow();
        int mouseX = (int) mc.mouseHandler.getScaledXPos(window);
        int mouseY = (int) mc.mouseHandler.getScaledYPos(window);

        int x = cfg.widgetX, y = cfg.widgetY, w = cfg.widgetW, h = cfg.widgetH;
        int accent = cfg.accentRgb();

        // self-heal geometry to the current window
        w = clamp(w, MIN_W, Math.max(MIN_W + 1, window.getGuiScaledWidth() - 10));
        h = clamp(h, MIN_H, Math.max(MIN_H + 1, window.getGuiScaledHeight() - 10));
        cfg.widgetW = w;
        cfg.widgetH = h;
        cfg.widgetX = x = clamp(x, 0, Math.max(0, window.getGuiScaledWidth() - w));
        cfg.widgetY = y = clamp(y, 0, Math.max(0, window.getGuiScaledHeight() - h));

        hovered = in(mouseX, mouseY, x, y, w, h);
        drewLastFrame = true;

        // background + hover outline
        g.fill(x, y, x + w, y + h, hovered ? COLOR_BG_HOVER : COLOR_BG);
        if (hovered) {
            g.outline(x, y, w, h, accent);
        }

        // ---------------------------------------------------------------
        // Everything inside the panel renders under a uniform scale factor
        // so the text shrinks/grows with the widget and always fits.
        // Layout constants are defined for a 1.0-scale 12-line font.
        // ---------------------------------------------------------------
        float scale = computeScale(h);
        Font font = mc.font;

        g.pose().pushMatrix();
        g.pose().scale(scale, scale);
        // translate to the panel origin, in pre-scale coordinates
        int sw = Math.round(w / scale);
        int sh = Math.round(h / scale);
        g.pose().translate(x / scale, y / scale);

        int pad = 4;
        int showControlsH = (hovered && cfg.showControls) ? 20 : 0;
        int artSize = sh - pad * 2 - showControlsH;
        boolean showArt = cfg.showAlbumArt && artSize >= 12;

        // ---- album art ----
        if (showArt) {
            ensureAlbumArt(state);
            if (textureRegistered) {
                blitAlbumArt(g, x, y, pad, artSize, scale);
            } else {
                g.fill(pad, pad, pad + artSize, pad + artSize, COLOR_BUTTON);
                g.text(font, "\u266B", pad + artSize / 2 - 3, pad + artSize / 2 - 4, COLOR_ARTIST);
            }
        }

        // ---- text block ----
        int textX = pad + (showArt ? artSize + 5 : 0);
        int textRight = sw - pad;

        // line 1: state icon + title
        String icon = state.playing ? "\u25B6" : "\u23F8";
        int iconW = font.width(icon);
        g.text(font, icon, textX, pad, accent);
        String title = truncate(font, state.title, textRight - textX - iconW - 3);
        g.text(font, title, textX + iconW + 3, pad, COLOR_TITLE);

        // line 2: artist
        String artist = truncate(font, state.artist, textRight - textX);
        g.text(font, artist, textX, pad + 11, COLOR_ARTIST);

        // line 3: time - directly above the seek bar, right-aligned
        int barY = sh - showControlsH - 8; // 2px bar, 6px above it the time
        String time = fmtMs(state.positionMs) + " / " + fmtMs(state.durationMs);
        int timeW = font.width(time);
        g.text(font, time, textRight - timeW, barY - 11, accent);

        // ---- seek bar ----
        if (state.durationMs > 0) {
            int barX = pad;
            int barW = sw - pad * 2;
            g.fill(barX, barY, barX + barW, barY + 2, COLOR_BUTTON);
            double frac = (double) state.positionMs / (double) state.durationMs;
            int fillW = (int) (barW * Math.min(1.0, Math.max(0.0, frac)));
            g.fill(barX, barY, barX + Math.max(fillW, 1), barY + 2, accent);
        }

        // ---- hover controls ----
        if (hovered && cfg.showControls) {
            int btnH = 14, btnW = 16, gap = 6;
            int totalW = btnW * 3 + gap * 2;
            int bx = (sw - totalW) / 2;
            int by = sh - btnH - pad;

            // convert mouse to pre-scale coords for hit tests
            int msX = round6((mouseX - x) / scale);
            int msY = round6((mouseY - y) / scale);

            drawBtn(g, font, bx, by, btnW, btnH, "\u23EE",
                    in(msX, msY, bx, by, btnW, btnH));
            int pbx = bx + btnW + gap;
            drawBtn(g, font, pbx, by, btnW, btnH,
                    state.playing ? "\u23F8" : "\u25B6",
                    in(msX, msY, pbx, by, btnW, btnH));
            int nbx = bx + (btnW + gap) * 2;
            drawBtn(g, font, pbx + 0 * gap, by, btnW, btnH, "",
                    false); // keep parity - no-op
            drawBtn(g, font, nbx, by, btnW, btnH, "\u23ED",
                    in(msX, msY, nbx, by, btnW, btnH));

            // resize grip (drawn in pre-scale space near bottom-right)
            g.fill(sw - 9, sh - 9, sw - 7, sh - 7, 0x90FFFFFF);
            g.fill(sw - 9, sh - 6, sw - 4, sh - 4, 0x90FFFFFF);
            g.fill(sw - -controlsGripW(), sh - 3, sw - 2, sh - 2, 0x90FFFFFF);
        }

        g.pose().popMatrix();

        // live drag / resize (screen coords)
        if (dragging) {
            cfg.widgetX = clamp((int) (mouseX - dragOffX), 0,
                    window.getGuiScaledWidth() - w);
            cfg.widgetY = clamp((int) (mouseY - dragOffY), 0,
                    window.getGuiScaledHeight() - h);
        }
        if (resizing) {
            cfg.widgetW = clamp(mouseX - x + 6, MIN_W, 460);
            cfg.widgetH = clamp(mouseY - y + 6, MIN_H, 220);
        }
        if (dragging || resizing) CraftifyConfig.save();
    }

    private static final int MIN_W = 150;
    private static final int MIN_H = 48;

    private static int controlsGripW() {
        return 7;
    }

    /** Auto-fit scale: h=68 -> 1.0, taller -> up to 1.5x, shorter -> down to 0.5x. */
    private static float computeScale(int h) {
        float s = h / 68.0f;
        return clampF(s, 0.5f, 1.5f);
    }

    private static float clampF(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int round6(double v) {
        return (int) Math.round(v);
    }

    /**
     * Draw the album art with correct UV mapping: blit the full texture into
     * a square region. Uses the 13-arg inner overload via the public API:
     * blit(pipeline, id, x, y, u, v, w, h, texW, texH, regionW, regionH)
     * where region = full texture and w,h = drawn size, or the simple
     * 9-arg blit when the texture is exactly the drawn size.
     */
    private static void blitAlbumArt(GuiGraphicsExtractor g, int x, int y,
                                     int pad, int artSize, float scale) {
        // we are inside the scaled pose; drawn region is (pad,pad,artSize,artSize)
        g.blit(RenderPipelines.GUI_TEXTURED, ALBUM_TEX_ID,
                pad, pad,                 // screen x,y (scaled space)
                0.0f, 0.0f,               // u,v offset
                artTexW, artTexH,          // w,h of texture region (full)
                artTexW, artTexH,          // texture dims
                artSize, artSize);         // drawn size (scales down)
        // NOTE: verified overload blit(RenderPipeline, Identifier, int x,
        // int y, float u, float v, int uWidth, int uHeight, int texW,
        // int texH, int regionW, int regionH) exists in 26.1.2
    }

    private static void drawBtn(GuiGraphicsExtractor g, Font font,
                               int bx, int by, int bw, int bh,
                               String glyph, boolean hover) {
        g.fill(bx, by, bx + bw, by + bh, hover ? COLOR_BUTTON_HOVER : COLOR_BUTTON);
        int gw = font.width(glyph);
        g.text(font, glyph, bx + (bw - gw) / 2, by + (bh - 8) / 2, COLOR_BUTTON_TEXT);
    }

    // ---------------------------------------------------------------- interaction

    public static boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (!drewLastFrame) return false;
        CraftifyConfig cfg = CraftifyConfig.get();
        if (!cfg.enabled || hiddenBySession) return false;
        if (cfg.displayModeEnum() == CraftifyConfig.DisplayMode.NEVER) return false;
        int x = cfg.widgetX, y = cfg.widgetY, w = cfg.widgetW, h = cfg.widgetH;
        if (!in((int) mouseX, (int) mouseY, x, y, w, h)) return false;

        // resize grip (screen coords)
        if (in((int) mouseX, (int) mouseY, x + w - 10, y + h - 10, 10, 10)) {
            resizing = true;
            return true;
        }

        YTMWebController controller = Craftify.getYtmController();
        if (controller != null && hovered && cfg.showControls) {
            float scale = computeScale(h);
            int sw = round6(w / scale);
            int sh = round6(h / scale);
            int msX = round6((mouseX - x) / scale);
            int msY = round6((mouseY - y) / scale);
            int btnH = 14, btnW = 16, gap = 6;
            int totalW = btnW * 3 + gap * 2;
            int bx = (sw - totalW) / 2;
            int by = sh - btnH - 4;
            if (in(msX, msY, bx, by, btnW, btnH)) {
                controller.previousTrack();
                return true;
            }
            int pbx = bx + btnW + gap;
            if (in(msX, msY, pbx, by, btnW, btnH)) {
                YTMState s = controller.lastState();
                if (s != null && s.playing) controller.pause(); else controller.play();
                return true;
            }
            int nbx = bx + (btnW + gap) * 2;
            if (in(msX, msY, nbx, by, btnW, btnH)) {
                controller.nextTrack();
                return true;
            }
        }

        // drag anywhere else on the panel
        dragging = true;
        dragOffX = mouseX - x;
        dragOffY = mouseY - y;
        return true;
    }

    public static void onMouseReleased() {
        if (dragging || resizing) CraftifyConfig.save();
        dragging = false;
        resizing = false;
    }

    // ---------------------------------------------------------------- album art

    private static void ensureAlbumArt(YTMState state) {
        String url = state.albumArt;
        if (url.isEmpty() || url.equals(loadedArtUrl)) return;
        loadedArtUrl = url;
        textureRegistered = false;

        CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<byte[]> resp = ART_HTTP.send(
                        HttpRequest.newBuilder(URI.create(url)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() / 100 != 2) return;
                NativeImage img = NativeImage.read(resp.body());
                NativeImage square = centerCropSquare(img);
                Minecraft.getInstance().execute(() -> {
                    try {
                        Minecraft.getInstance().getTextureManager().release(ALBUM_TEX_ID);
                        Minecraft.getInstance().getTextureManager()
                                .register(ALBUM_TEX_ID,
                                        new DynamicTexture(() -> "craftify-album-art", square));
                        artTexW = square.getWidth();
                        artTexH = square.getHeight();
                        textureRegistered = true;
                    } catch (Exception e) {
                        Craftify.LOGGER.warn("album art register failed: {}",
                                String.valueOf(e));
                    }
                });
            } catch (Exception e) {
                Craftify.LOGGER.warn("album art fetch failed: {}", String.valueOf(e));
            }
        });
    }

    /** Crop an image to a centered square (youtube thumbs are 16:9 letterboxed). */
    private static NativeImage centerCropSquare(NativeImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (w == h) return img;
        int side = Math.min(w, h);
        int x0 = (w - side) / 2;
        int y0 = (h - side) / 2;
        NativeImage out = new NativeImage(side, side, false);
        out.copyRect(img, x0, y0, 0, 0, side, side, false, false);
        img.close();
        return out;
    }

    // ------------------------------------------------- position interpolation

    private static long lastStateTime;
    private static double lastPosMs = -1;

    private static YTMState advancePosition(YTMState s) {
        long now = System.currentTimeMillis();
        if (s.positionMs != lastPosMs) {
            lastStateTime = now;
            lastPosMs = s.positionMs;
        }
        if (!s.playing) return s;
        double elapsedMs = now - lastStateTime;
        double advanced = s.positionMs + elapsedMs;
        if (s.durationMs > 0 && advanced > s.durationMs) advanced = s.durationMs;
        return new YTMState(s.playing, s.title, s.artist, s.album,
                s.durationMs, (long) advanced, s.videoId, s.albumArt);
    }

    // ---------------------------------------------------------------- helpers

    private static boolean in(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String truncate(Font font, String s, int maxWidth) {
        if (s == null) s = "";
        if (font.width(s) <= maxWidth) return s;
        String ellipsis = "...";
        while (s.length() > 0 && font.width(s + ellipsis) > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return s + ellipsis;
    }

    private static String fmtMs(long ms) {
        long totalSec = ms / 1000;
        long m = totalSec / 60;
        long s = totalSec % 60;
        return m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }
}
