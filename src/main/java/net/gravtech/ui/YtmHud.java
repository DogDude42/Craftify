package net.gravtech.ui;

import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.gravtech.Craftify;
import net.gravtech.CraftifyClient;
import net.gravtech.YTMWebController;
import net.gravtech.YTMWebController.YTMState;

/**
 * Port of upstream Craftify's Player HUD for MC 26.1.2 / fabric-api 26.x.
 *
 * Renders in the top-left of the screen:
 *   [state icon] Title
 *   artist          mm:ss / mm:ss
 * and when hovered, control buttons (prev / play-pause / next) appear -
 * clickable via MouseHandlerMixin.
 */
public final class YtmHud {

    private static final Identifier ELEMENT_ID =
            Identifier.fromNamespaceAndPath(Craftify.MOD_ID, "ytm_player");

    // Layout constants, ported from upstream UIPlayerV2
    private static final int PADDING = 5;
    private static final int WIDTH = 150;
    private static final int HEIGHT = 36;
    private static final int FOCUSED_HEIGHT = 52;

    // Catppuccin Mocha palette to match the user's theme taste
    private static final int COLOR_BG          = 0xF01E1E2E; // base, ~94% alpha
    private static final int COLOR_BG_HOVER    = 0xF0303030;
    private static final int COLOR_BORDER       = 0xFF89B4FA; // blue on hover
    private static final int COLOR_TITLE        = 0xFFCDD6F4; // text
    private static final int COLOR_ARTIST      = 0xFFA6ADC8; // subtext0
    private static final int COLOR_TIME        = 0xFFA6E3A1; // green
    private static final int COLOR_BUTTON       = 0xFF45475A; // surface0
    private static final int COLOR_BUTTON_HOVER= 0xFF585B70; // surface1
    private static final int COLOR_BUTTON_TEXT  = 0xFFCDD6F4;
    private static final int COLOR_DISABLED     = 0x8045475A;

    private static boolean visible = true;

    // hover/click state (scaled coords)
    private static boolean hovered;
    private static boolean prevHover, playHover, nextHover;

    private YtmHud() {}

    public static void register() {
        HudElementRegistry.addLast(ELEMENT_ID, YtmHud::render);
    }

    public static void toggleVisible() {
        visible = !visible;
    }

    // ---------------------------------------------------------------- render

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return;

        // poll keybinds every frame while in-game
        CraftifyClient.handleKeybinds();

        if (!visible) return;

        YTMWebController controller = Craftify.getYtmController();
        YTMState state = controller == null ? null : controller.lastState();
        if (state == null) return;

        Window window = mc.getWindow();
        int mouseX = (int) mc.mouseHandler.getScaledXPos(window);
        int mouseY = (int) mc.mouseHandler.getScaledYPos(window);

        int x = PADDING;
        int y = PADDING;
        int height = hovered ? FOCUSED_HEIGHT : HEIGHT;

        hovered = mouseX >= x && mouseX < x + WIDTH
               && mouseY >= y && mouseY < y + height;

        Font font = mc.font;

        // background panel
        g.fill(x, y, x + WIDTH, y + height,
               hovered ? COLOR_BG_HOVER : COLOR_BG);
        if (hovered) {
            g.outline(x, y, x + WIDTH, y + height, COLOR_BORDER);
        }

        int textX = x + PADDING + 2;
        int titleY = y + PADDING;
        int artistY = titleY + 12;

        // state icon + title
        String icon = state.playing ? "\u25B6" : "\u23F8"; // ▶ / ⏸
        g.text(font, icon, textX, titleY, state.playing ? COLOR_TIME : COLOR_ARTIST);
        g.text(font, state.title, textX + 12, titleY, COLOR_TITLE);

        // artist + time on second line
        g.text(font, state.artist, textX, artistY, COLOR_ARTIST);
        String time = fmt(state.position) + " / " + fmt(state.duration);
        int timeW = font.width(time);
        g.text(font, time, x + WIDTH - PADDING - 2 - timeW, artistY, COLOR_TIME);

        // track progress bar (thin, full width)
        if (state.duration > 0) {
            int barY = y + height - (hovered ? 16 : 8);
            int barW = WIDTH - PADDING * 2;
            g.fill(x + PADDING, barY, x + PADDING + barW, barY + 2, COLOR_BUTTON);
            int fillW = (int) (barW * ((float) state.position / state.duration));
            g.fill(x + PADDING, barY, x + PADDING + Math.max(fillW, 1), barY + 2, COLOR_TIME);
        }

        // hover controls (prev | play/pause | next), like upstream
        if (hovered) {
            int btnY = y + HEIGHT + 4;
            int btnH = FOCUSED_HEIGHT - HEIGHT - PADDING;
            drawButton(g, font, mouseX, mouseY, textX + 0 * 30, btnY, btnW(0), btnH,
                    "\u23EE", "prev", prevHover = inRect(mouseX, mouseY, textX + 0 * 30, btnY, btnW(0), btnH));
            drawButton(g, font, mouseX, mouseY, textX + 1 * 30, btnY, btnW(1), btnH,
                    state.playing ? "\u23F8" : "\u25B6", "play",
                    playHover = inRect(mouseX, mouseY, textX + 1 * 30, btnY, btnW(1), btnH));
            drawButton(g, font, mouseX, mouseY, textX + 2 * 30, btnY, btnW(2), btnH,
                    "\u23ED", "next", nextHover = inRect(mouseX, mouseY, textX + 2 * 30, btnY, btnW(2), btnH));
        }
    }

    private static int btnW(int i) {
        return 28;
    }

    private static void drawButton(GuiGraphicsExtractor g, Font font,
                                   int mx, int my, int bx, int by, int bw, int bh,
                                   String glyph, String tooltip, boolean hover) {
        g.fill(bx, by, bx + bw, by + bh, hover ? COLOR_BUTTON_HOVER : COLOR_BUTTON);
        int gw = font.width(glyph);
        g.text(font, glyph, bx + (bw - gw) / 2, by + (bh - 8) / 2, COLOR_BUTTON_TEXT);
    }

    private static boolean inRect(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Exposed for the click mixin: was the mouse over a control button? */
    public static boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (!hovered) return false;
        int x = PADDING, y = PADDING;
        int btnY = y + HEIGHT + 4;
        int btnH = FOCUSED_HEIGHT - HEIGHT - PADDING;
        YTMWebController controller = Craftify.getYtmController();
        if (controller == null) return false;
        if (inRectD(mouseX, mouseY, x + PADDING + 2 + 0 * 30, btnY, 28, btnH)) {
            controller.previousTrack();
            return true;
        }
        if (inRectD(mouseX, mouseY, x + PADDING + 2 + 1 * 30, btnY, 28, btnH)) {
            YTMState s = controller.lastState();
            if (s != null && s.playing) controller.pause(); else controller.play();
            return true;
        }
        if (inRectD(mouseX, mouseY, x + PADDING + 2 + 2 * 30, btnY, 28, btnH)) {
            controller.nextTrack();
            return true;
        }
        return false;
    }

    private static boolean inRectD(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static String fmt(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        return m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }
}
