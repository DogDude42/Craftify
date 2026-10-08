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
 *  - Album art (DynamicTexture from the URL the extension reports)
 *  - Draggable (drag the panel) + resizable (bottom-right grip); geometry
 *    persisted in config/craftify-ytm-web.json via CraftifyConfig
 *  - Hover controls prev / play-pause / next (pause glyph while playing)
 *  - Catppuccin Mocha palette; accent color configurable (default pink)
 *  - Title / artist / time on separate lines - no overlap
 */
public final class YtmHud {

    private static final Identifier ELEMENT_ID =
            Identifier.fromNamespaceAndPath(Craftify.MOD_ID, "ytm_player");
    private static final Identifier ALBUM_TEX_ID =
            Identifier.fromNamespaceAndPath(Craftify.MOD_ID, "ytm_album_art");

    // ---- Catppuccin Mocha base palette (accent comes from config) ----
    private static final int COLOR_BG            = 0xF01E1E2E; // base
    private static final int COLOR_BG_HOVER     = 0xF0181825; // mantle
    private static final int COLOR_TITLE        = 0xFFCDD6F4; // text
    private static final int COLOR_ARTIST       = 0xFFA6ADC8; // subtext0
    private static final int COLOR_BUTTON       = 0xFF45475A; // surface0
    private static final int COLOR_BUTTON_HOVER = 0xFF585B70; // surface1
    private static final int COLOR_BUTTON_TEXT  = 0xFFCDD6F4;

    // interaction state
    private static boolean hovered;
    private static boolean dragging;
    private static boolean resizing;
    private static double dragOffX, dragOffY;

    // album art
    private static volatile String loadedArtUrl = "";
    private static volatile boolean textureRegistered;
    private static final HttpClient ART_HTTP = HttpClient.newHttpClient();

    private YtmHud() {}

    // ---------------------------------------------------------------- setup

    public static void register() {
        HudElementRegistry.addLast(ELEMENT_ID, YtmHud::render);
    }

    public static void toggleVisible() {
        CraftifyConfig.update(g -> g.displayMode =
                g.displayModeEnum() == CraftifyConfig.DisplayMode.NEVER ? "SONG" : "NEVER");
    }

    /** Called by ConfigScreen when display mode was turned back on. */
    public static void resetHiddenByConfig() {}

    // ---------------------------------------------------------------- render

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return;

        CraftifyClient.handleKeybinds();

        CraftifyConfig cfg = CraftifyConfig.get();
        if (!cfg.enabled) return;

        switch (cfg.displayModeEnum()) {
            case NEVER -> { return; }
            case ALWAYS -> { /* show even without a song */ }
            case SONG -> {
                YTMWebController controller = Craftify.getYtmController();
                YTMState state = controller == null ? null : controller.lastState();
                if (state == null) return;
            }
        }

        YTMWebController controller = Craftify.getYtmController();
        YTMState state = controller == null ? null : controller.lastState();
        if (state == null) return;

        Window window = mc.getWindow();
        int mouseX = (int) mc.mouseHandler.getScaledXPos(window);
        int mouseY = (int) mc.mouseHandler.getScaledYPos(window);

        int x = cfg.widgetX, y = cfg.widgetY, w = cfg.widgetW, h = cfg.widgetH;
        int accent = cfg.accentRgb();

        hovered = in(mouseX, mouseY, x, y, w, h);

        // background
        g.fill(x, y, x + w, y + h, hovered ? COLOR_BG_HOVER : COLOR_BG);
        if (hovered) {
            // outline(x, y, WIDTH, HEIGHT, color) - size not max-coords
            g.outline(x, y, w, h, accent);
        }

        Font font = mc.font;
        int pad = 5;

        // ---- album art ----
        int artSize = h - pad * 2 - (hovered && cfg.showControls ? 24 : 0);
        if (cfg.showAlbumArt && artSize >= 12) {
            ensureAlbumArt(state);
            if (textureRegistered) {
                g.blit(RenderPipelines.GUI_TEXTURED, ALBUM_TEX_ID,
                        x + pad, y + pad, 0.0f, 0.0f,
                        artSize, artSize, artSize, artSize);
            } else {
                g.fill(x + pad, y + pad, x + pad + artSize, y + pad + artSize,
                       COLOR_BUTTON);
                g.text(font, "\u266B", x + pad + artSize / 2 - 3,
                       y + pad + artSize / 2 - 4, COLOR_ARTIST);
            }
        }

        // ---- text ----
        int textX = x + pad + (cfg.showAlbumArt ? artSize + 6 : 0);
        int textRight = x + w - pad;

        String icon = state.playing ? "\u25B6" : "\u23F8";
        int iconW = font.width(icon);
        g.text(font, icon, textX, y + pad, accent);
        String title = truncate(font, state.title, textRight - textX - iconW - 4);
        g.text(font, title, textX + iconW + 4, y + pad, COLOR_TITLE);

        String artist = truncate(font, state.artist, textRight - textX);
        g.text(font, artist, textX, y + pad + 11, COLOR_ARTIST);

        String time = fmt(state.position) + " / " + fmt(state.duration);
        int timeW = font.width(time);
        g.text(font, time, textRight - timeW, y + pad + 22, accent);

        // ---- progress bar ----
        if (state.duration > 0) {
            int barY = y + h - (hovered && cfg.showControls ? 24 : 6);
            int barX = x + pad;
            int barW = w - pad * 2;
            g.fill(barX, barY, barX + barW, barY + 2, COLOR_BUTTON);
            int fillW = (int) (barW * ((float) state.position / state.duration));
            g.fill(barX, barY, barX + Math.max(fillW, 1), barY + 2, accent);
        }

        // ---- hover controls ----
        if (hovered && cfg.showControls) {
            int btnH = 14, btnW = 16, gap = 6;
            int totalW = btnW * 3 + gap * 2;
            int bx = x + (w - totalW) / 2;
            int by = y + h - btnH - pad;

            drawBtn(g, font, bx, by, btnW, btnH, "\u23EE",
                    in(mouseX, mouseY, bx, by, btnW, btnH));
            int pbx = bx + btnW + gap;
            drawBtn(g, font, pbx, by, btnW, btnH,
                    state.playing ? "\u23F8" : "\u25B6",
                    in(mouseX, mouseY, pbx, by, btnW, btnH));
            int nbx = bx + (btnW + gap) * 2;
            drawBtn(g, font, nbx, by, btnW, btnH, "\u23ED",
                    in(mouseX, mouseY, nbx, by, btnW, btnH));

            // resize grip
            g.fill(x + w - 9, y + h - 9, x + w - 7, y + h - 7, 0x90FFFFFF);
            g.fill(x + w - 9, y + h - 6, x + w - 4, y + h - 4, 0x90FFFFFF);
            g.fill(x + w - 9, y + h - 3, x + w - 2, y + h - 2, 0x90FFFFFF);
        }

        // live drag / resize
        if (dragging) {
            cfg.widgetX = clamp((int) (mouseX - dragOffX), 0,
                    window.getGuiScaledWidth() - w);
            cfg.widgetY = clamp((int) (mouseY - dragOffY), 0,
                    window.getGuiScaledHeight() - h);
        }
        if (resizing) {
            cfg.widgetW = clamp(mouseX - x + 6, 170, 460);
            cfg.widgetH = clamp(mouseY - y + 6, 56, 220);
        }
        if (dragging || resizing) CraftifyConfig.save();
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
        CraftifyConfig cfg = CraftifyConfig.get();
        if (!cfg.enabled) return false;
        int x = cfg.widgetX, y = cfg.widgetY, w = cfg.widgetW, h = cfg.widgetH;
        if (!in((int) mouseX, (int) mouseY, x, y, w, h)) return false;

        // resize grip
        if (in((int) mouseX, (int) mouseY, x + w - 10, y + h - 10, 10, 10)) {
            resizing = true;
            return true;
        }

        YTMWebController controller = Craftify.getYtmController();
        if (controller != null && hovered && cfg.showControls) {
            int btnH = 14, btnW = 16, gap = 6;
            int totalW = btnW * 3 + gap * 2;
            int bx = x + (w - totalW) / 2;
            int by = y + h - btnH - 5;
            if (in((int) mouseX, (int) mouseY, bx, by, btnW, btnH)) {
                controller.previousTrack();
                return true;
            }
            int pbx = bx + btnW + gap;
            if (in((int) mouseX, (int) mouseY, pbx, by, btnW, btnH)) {
                YTMState s = controller.lastState();
                if (s != null && s.playing) controller.pause(); else controller.play();
                return true;
            }
            int nbx = bx + (btnW + gap) * 2;
            if (in((int) mouseX, (int) mouseY, nbx, by, btnW, btnH)) {
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
                Minecraft.getInstance().execute(() -> {
                    try {
                        Minecraft.getInstance().getTextureManager()
                                .register(ALBUM_TEX_ID,
                                        new DynamicTexture(() -> "craftify-album-art", img));
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

    private static String fmt(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        return m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }
}
