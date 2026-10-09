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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Craftify player HUD for MC 26.1.2.
 *
 *  - Album art: extension re-encodes the thumbnail to PNG in-page (canvas),
 *    because ytimg serves webp to Chrome which stb_image can't decode.
 *    Mod falls back to direct URL fetch for jpeg thumbs.
 *  - Sub-second ms clock (true <video> currentTime, extrapolated in ms)
 *  - Scalable text: panel interior renders under pose scale (auto from h)
 *  - Layout: [art | icon+title(marquee) / info lines (wrap down) / time
 *    above bar]; seek bar spans the TEXT zone only - never overlaps art
 *  - Resizing freezes the layout (controls hidden) for a stable preview
 *  - Drag anywhere; resize grip bottom-right; geometry persisted + self-healed
 */
public final class YtmHud {

    private static final Identifier ELEMENT_ID =
            Identifier.fromNamespaceAndPath(Craftify.MOD_ID, "ytm_player");
    private static final Identifier ALBUM_TEX_ID =
            Identifier.fromNamespaceAndPath(Craftify.MOD_ID, "ytm_album_art");

    // ---- Catppuccin Mocha base palette (accent from config) ----
    private static final int COLOR_BG            = 0xF01E1E2E;
    private static final int COLOR_BG_HOVER     = 0xF0181825;
    private static final int COLOR_TITLE        = 0xFFCDD6F4;
    private static final int COLOR_ARTIST       = 0xFFA6ADC8;
    private static final int COLOR_BUTTON       = 0xFF45475A;
    private static final int COLOR_BUTTON_HOVER = 0xFF585B70;
    private static final int COLOR_BUTTON_TEXT  = 0xFFCDD6F4;

    private static final int MIN_W = 150;
    private static final int MIN_H = 48;
    private static final int LINE_H = 10;
    private static final int CONTROLS_H = 20;

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

        // background + hover outline (opacity from config)
        g.fill(x, y, x + w, y + h, cfg.backgroundArgb(hovered));
        if (hovered) {
            g.outline(x, y, w, h, accent);
        }

        // ---- everything inside renders under a uniform scale ----
        float scale = computeScale(h);
        Font font = mc.font;

        g.pose().pushMatrix();
        g.pose().scale(scale, scale);
        int sw = Math.round(w / scale);
        int sh = Math.round(h / scale);
        g.pose().translate(x / scale, y / scale);

        int pad = 4;
        // Resizing freezes the layout: controls hidden, art keeps full height
        boolean showCtrls = hovered && cfg.showControls && !resizing;
        int controlsH = showCtrls ? CONTROLS_H : 0;
        int artSize = sh - pad * 2 - controlsH;
        boolean showArt = cfg.showAlbumArt && artSize >= 12;

        // ---- album art ----
        if (showArt) {
            ensureAlbumArt(state);
            if (textureRegistered) {
                // blit(pipeline, id, x, y, u, v, drawW, drawH, regionW, regionH,
                //      texW, texH) - draw size comes FIRST among the int pairs,
                // texture dims LAST (UV normalizers). Previously swapped:
                // drew 256x256 GUI px (whole widget) and u2 = 256/52 ~ 4.9
                // -> art tiled ~5x across the screen. Now: draw artSize px,
                // sample the full square texture, normalize by real dims.
                g.blit(RenderPipelines.GUI_TEXTURED, ALBUM_TEX_ID,
                        pad, pad,
                        0.0f, 0.0f,
                        artSize, artSize,
                        artTexW, artTexH,
                        artTexW, artTexH);
            } else {
                g.fill(pad, pad, pad + artSize, pad + artSize, COLOR_BUTTON);
                g.text(font, "\u266B", pad + artSize / 2 - 3, pad + artSize / 2 - 4, COLOR_ARTIST);
            }
        }

        // ---- text zone geometry (declared BEFORE use) ----
        int textLeft = showArt ? pad + artSize + 5 : pad;
        int textRight = sw - pad;
        int textW = textRight - textLeft;

        // seek bar sits directly above the controls row (or the bottom pad)
        int barY = sh - controlsH - 8;
        int barX = textLeft;
        int barW = Math.max(0, sw - pad - textLeft);

        // ---- seek bar (spans TEXT zone only - never under the art) ----
        if (state.durationMs > 0) {
            g.fill(barX, barY, barX + barW, barY + 2, COLOR_BUTTON);
            double frac = (double) state.positionMs / (double) state.durationMs;
            int fillW = (int) (barW * Math.min(1.0, Math.max(0.0, frac)));
            g.fill(barX, barY, barX + Math.max(fillW, 1), barY + 2, accent);
        }

        // ---- time: directly above the bar, right-aligned ----
        String time = fmtMs(state.positionMs) + " / " + fmtMs(state.durationMs);
        int timeW = font.width(time);
        g.text(font, time, textRight - timeW, barY - 11, accent);

        // ---- line 1: title (marquee-scrolls when too long) ----
        drawScrollingText(g, font, state.title,
                textLeft, pad, textW, COLOR_TITLE);

        // ---- info lines: artist / album / extra parts, wrapping down ----
        List<String> infoLines = splitInfoLines(state.artist, state.album);
        int nextY = pad + LINE_H + 1;
        int maxY = barY - 11 - 1; // stay above the time line
        for (String line : infoLines) {
            if (line == null || line.isEmpty()) continue;
            String remaining = line;
            while (!remaining.isEmpty() && nextY + LINE_H <= maxY) {
                String chunk = fitText(font, remaining, textW);
                g.text(font, chunk, textLeft, nextY, COLOR_ARTIST);
                remaining = chunk.endsWith("...")
                        ? "" // truncated: don't keep wrapping the ellipsis
                        : remaining.substring(chunk.length()).trim();
                nextY += LINE_H;
                if (remaining.isEmpty()) break;
            }
            if (nextY + LINE_H > maxY) break;
        }

        // ---- hover controls (hidden while resizing) ----
        // prev | play-pause | next | loop | shuffle
        if (showCtrls) {
            int btnH = 14, btnW = 16, gap = 6;
            int totalW = btnW * 5 + gap * 4;
            int bx = (sw - totalW) / 2;
            int by = sh - btnH - pad;

            int msX = round6((mouseX - x) / scale);
            int msY = round6((mouseY - y) / scale);

            drawBtn(g, font, bx, by, btnW, btnH, "\u23EE",
                    in(msX, msY, bx, by, btnW, btnH));
            int pbx = bx + btnW + gap;
            // Play/pause glyph shows the CURRENT state (pause glyph while
            // paused, play glyph while playing) + state echo: accent fill
            // while playing so the state reads at a glance.
            boolean pHover = in(msX, msY, pbx, by, btnW, btnH);
            g.fill(pbx, by, pbx + btnW, by + btnH,
                    pHover ? COLOR_BUTTON_HOVER : COLOR_BUTTON);
            String pGlyph = state.playing ? "\u25B6" : "\u23F8";
            int pgw = font.width(pGlyph);
            g.text(font, pGlyph, pbx + (btnW - pgw) / 2, by + (btnH - 8) / 2,
                    state.playing ? CraftifyConfig.get().accentRgb() : COLOR_BUTTON_TEXT);
            int nbx = bx + (btnW + gap) * 2;
            drawBtn(g, font, nbx, by, btnW, btnH, "\u23ED",
                    in(msX, msY, nbx, by, btnW, btnH));
            // loop 3-state: off (grey), ALL (accent, ring), ONE (accent, "1")
            int lbx = bx + (btnW + gap) * 3;
            boolean loopAll = "ALL".equals(state.loopMode);
            boolean loopOne = "ONE".equals(state.loopMode);
            boolean loopOn = loopAll || loopOne;
            drawToggleBtn(g, font, lbx, by, btnW, btnH,
                    loopOne ? "\u21BB1" : "\u21BB",
                    loopOn, in(msX, msY, lbx, by, btnW, btnH));
            // shuffle toggle: accent when ON, grey when OFF
            int sbx = bx + (btnW + gap) * 4;
            drawToggleBtn(g, font, sbx, by, btnW, btnH, "\u292E",
                    state.shuffle, in(msX, msY, sbx, by, btnW, btnH));
        }

        // resize grip (always visible on hover, even while resizing)
        if (hovered) {
            g.fill(sw - 9, sh - 9, sw - 7, sh - 7, 0x90FFFFFF);
            g.fill(sw - 9, sh - 6, sw - 4, sh - 4, 0x90FFFFFF);
            g.fill(sw - 6, sh - 3, sw - 2, sh - 2, 0x90FFFFFF);
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

    // ---------------------------------------------------------------- layout helpers

    /** h=68 -> 1.0 scale; clamped 0.5..1.5. */
    private static float computeScale(int h) {
        return clampF(h / 68.0f, 0.5f, 1.5f);
    }

    private static float clampF(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int round6(double v) {
        return (int) Math.round(v);
    }

    /**
     * Split YTM's "artist • album • year" subtitle into separate info lines.
     */
    private static List<String> splitInfoLines(String artist, String album) {
        List<String> lines = new ArrayList<>();
        String sub = artist == null ? "" : artist.trim();
        String[] parts = sub.split("\\s*\\u2022\\s*|\\s*\u2022\\s*");
        if (parts.length == 0 || (parts.length == 1 && parts[0].isEmpty())) {
            if (album != null && !album.isEmpty()) lines.add(album.trim());
            return lines;
        }
        for (String p : parts) {
            if (p != null && !p.isEmpty()) lines.add(p.trim());
        }
        if (album != null && !album.isEmpty()) {
            boolean have = lines.stream().anyMatch(l -> l.equals(album.trim()));
            if (!have) lines.add(album.trim());
        }
        return lines;
    }

    /** Marquee-scroll text that doesn't fit; static when it does. Scissor-clipped. */
    private static void drawScrollingText(GuiGraphicsExtractor g, Font font,
                                          String text, int x, int y, int maxW, int color) {
        if (maxW <= 4) return;
        if (text == null) text = "";
        if (font.width(text) <= maxW) {
            g.text(font, text, x, y, color);
            return;
        }
        // scroll sequence = text + gap, advancing ~8px/s, looping
        String seq = text + "     ";
        int seqW = font.width(seq);
        int offset = (int) ((System.currentTimeMillis() / 125L) % seqW);

        // scissor to the title box so the scroll never bleeds outside
        g.enableScissor(x, y - 1, x + maxW, y + LINE_H);
        // draw the sequence twice, offset-advancing, to cover the whole box
        g.text(font, seq, x - offset, y, color);
        if (offset + maxW > seqW) {
            g.text(font, seq, x - offset + seqW, y, color);
        }
        g.disableScissor();
    }

    /** Longest prefix of s that fits maxW (adds ellipsis when truncated). */
    private static String fitText(Font font, String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        String ell = "...";
        while (s.length() > 0 && font.width(s + ell) > maxW) {
            s = s.substring(0, s.length() - 1);
        }
        return s + ell;
    }

    private static void drawBtn(GuiGraphicsExtractor g, Font font,
                               int bx, int by, int bw, int bh,
                               String glyph, boolean hover) {
        g.fill(bx, by, bx + bw, by + bh, hover ? COLOR_BUTTON_HOVER : COLOR_BUTTON);
        int gw = font.width(glyph);
        g.text(font, glyph, bx + (bw - gw) / 2, by + (bh - 8) / 2, COLOR_BUTTON_TEXT);
    }

    /** Toggle button: accent-filled + accent glyph when ON, plain when OFF. */
    private static void drawToggleBtn(GuiGraphicsExtractor g, Font font,
                                     int bx, int by, int bw, int bh,
                                     String glyph, boolean on, boolean hover) {
        CraftifyConfig cfg = CraftifyConfig.get();
        int accent = cfg.accentRgb();
        int fill = on ? accent : (hover ? COLOR_BUTTON_HOVER : COLOR_BUTTON);
        g.fill(bx, by, bx + bw, by + bh, fill);
        int gw = font.width(glyph);
        // dark glyph on the bright accent when ON, light on dark when OFF
        g.text(font, glyph, bx + (bw - gw) / 2, by + (bh - 8) / 2,
                on ? 0xFF181825 : COLOR_BUTTON_TEXT);
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
        boolean showCtrls = hovered && cfg.showControls && !resizing;
        if (controller != null && showCtrls) {
            float scale = computeScale(h);
            int sw = round6(w / scale);
            int sh = round6(h / scale);
            int msX = round6((mouseX - x) / scale);
            int msY = round6((mouseY - y) / scale);
            int btnH = 14, btnW = 16, gap = 6;
            int totalW = btnW * 5 + gap * 4;
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
            int lbx = bx + (btnW + gap) * 3;
            if (in(msX, msY, lbx, by, btnW, btnH)) {
                controller.sendCommand("loop");
                return true;
            }
            int sbx = bx + (btnW + gap) * 4;
            if (in(msX, msY, sbx, by, btnW, btnH)) {
                controller.sendCommand("shuffle");
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
        // Prefer the extension's canvas-re-encoded PNG data URL (ytimg serves
        // webp to Chrome, which NativeImage/stb_image cannot decode). Fall
        // back to a direct fetch of the raw URL (jpeg thumbs decode fine).
        String png = state.albumArtPng;
        String url = state.albumArt;
        String key = !png.isEmpty() ? png : url;
        if (key.isEmpty() || key.equals(loadedArtUrl)) return;
        loadedArtUrl = key;
        textureRegistered = false;

        if (!png.isEmpty()) {
            int comma = png.indexOf(',');
            if (comma > 0) {
                try {
                    byte[] bytes = java.util.Base64.getDecoder()
                            .decode(png.substring(comma + 1));
                    NativeImage img = NativeImage.read(bytes);
                    Minecraft.getInstance().execute(() -> registerArt(img));
                } catch (Exception e) {
                    Craftify.LOGGER.warn("album art png decode failed: {}",
                            String.valueOf(e));
                }
            }
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<byte[]> resp = ART_HTTP.send(
                        HttpRequest.newBuilder(URI.create(url)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() / 100 != 2) return;
                NativeImage square = centerCropSquare(NativeImage.read(resp.body()));
                Minecraft.getInstance().execute(() -> registerArt(square));
            } catch (Exception e) {
                Craftify.LOGGER.warn("album art fetch failed: {}", String.valueOf(e));
            }
        });
    }

    private static void registerArt(NativeImage square) {
        try {
            Minecraft.getInstance().getTextureManager().release(ALBUM_TEX_ID);
            Minecraft.getInstance().getTextureManager()
                    .register(ALBUM_TEX_ID,
                            new DynamicTexture(() -> "craftify-album-art", square));
            artTexW = square.getWidth();
            artTexH = square.getHeight();
            textureRegistered = true;
        } catch (Exception e) {
            Craftify.LOGGER.warn("album art register failed: {}", String.valueOf(e));
        }
    }

    /** Crop an image to a centered square (yt thumbs are 16:9 letterboxed). */
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
    // Smooth latency-compensated clock:
    //  - advances in real time every frame (never stalls, never skips)
    //  - reports lag by the pipeline latency; that latency is tracked with an
    //    outlier-rejected EMA and compensated before comparing
    //  - corrections are FORWARD-ONLY and gentle (30% absorb) - the old code
    //    absorbed 30% of NEGATIVE errors too, dragging the clock ~0.5s back
    //    on every update = the visible "occasional skips"
    //  - hard-sync on song change - keyed by TITLE: YTM autoplay leaves the
    //    videoId/URL stale, so the old videoId-keyed reset NEVER FIRED and
    //    the clock kept counting the old song's position into the next song
    //    (user saw the time "extend" and lengths stack up)

    private static long clockMs;
    private static long clockAnchorMs;
    private static String clockSongKey = "";
    private static boolean clockRunning;
    private static boolean clockInit;
    private static long latencyEma; // typical (report - display) while playing
    private static int pendingSyncSkip; // stale boundary reports to skip

    private static YTMState advancePosition(YTMState s) {
        long now = System.currentTimeMillis();
        String songKey = s.title + "|" + s.videoId;
        boolean songChanged = clockInit && !songKey.equals(clockSongKey);
        if (songChanged) {
            Craftify.LOGGER.info("Song changed: {} (pos {}ms / dur {}ms)",
                    s.title, s.positionMs, s.durationMs);
        }
        clockSongKey = songKey;

        long shown;
        if (!clockInit || songChanged) {
            // A song-change report may carry the OLD song's playhead (the
            // video element lags the title). Positions deep into the track
            // at a boundary are almost always stale bleed-through; if the
            // report is suspicious, delay the hard-sync by one update.
            boolean suspiciousBoundary = songChanged && s.durationMs > 0
                    && s.positionMs > s.durationMs * 40 / 100
                    && pendingSyncSkip < 2;
            if (suspiciousBoundary) {
                pendingSyncSkip++;
                Craftify.LOGGER.debug("song-change report looks stale (pos {} / "
                        + "dur {}); waiting one update before syncing",
                        s.positionMs, s.durationMs);
            } else {
                pendingSyncSkip = 0;
                clockInit = true;
                clockMs = s.positionMs;
                clockAnchorMs = now;
                clockRunning = s.playing;
                latencyEma = 0;
            }
            shown = liveMs(now);
        } else if (!s.playing) {
            // paused: the report is authoritative - freeze exactly on it
            clockRunning = false;
            clockMs = s.positionMs;
            clockAnchorMs = now;
            shown = clockMs;
        } else {
            long live = liveMs(now);
            long err = s.positionMs - live;          // ~ -latency while stable
            // latency estimate from inliers only (rejects seek outliers)
            if (Math.abs(err - latencyEma) < 1500L) {
                latencyEma = (latencyEma * 4 + err) / 5;
            }
            long errAdj = err - latencyEma;          // latency-compensated
            if (errAdj > 2500L || errAdj < -5000L) {
                // seek / stall / element swap: snap to the report
                clockMs = s.positionMs;
                clockAnchorMs = now;
                pendingSyncSkip = 0;
            } else if (errAdj > 120L) {
                // display behind report: gently catch up (absorb 30%)
                clockMs = live + errAdj * 30 / 100;
                clockAnchorMs = now;
            }
            // otherwise: keep ticking - a report that is merely BEHIND (the
            // normal case) must never drag the clock backwards
            clockRunning = true;
            shown = liveMs(now);
        }
        if (s.durationMs > 0 && shown > s.durationMs) shown = s.durationMs;
        return withPosition(s, Math.max(0L, shown));
    }

    private static long liveMs(long now) {
        return clockRunning ? clockMs + (now - clockAnchorMs) : clockMs;
    }

    private static YTMState withPosition(YTMState s, long posMs) {
        return new YTMState(s.playing, s.title, s.artist, s.album,
                s.durationMs, Math.max(0, posMs), s.videoId, s.albumArt,
                s.albumArtPng, s.loopMode, s.shuffle);
    }

    // ---------------------------------------------------------------- helpers

    private static boolean in(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String fmtMs(long ms) {
        long totalSec = ms / 1000;
        long m = totalSec / 60;
        long s = totalSec % 60;
        return m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }
}
