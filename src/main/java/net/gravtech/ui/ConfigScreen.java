package net.gravtech.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.gravtech.CraftifyConfig;

import java.util.List;

/**
 * Native config screen for Craftify (opened via ModMenu or /craftify).
 * Ports the upstream Vigilance GUI options to plain MC widgets.
 */
public class ConfigScreen extends Screen {

    private final Screen parent;

    public ConfigScreen(Screen parent) {
        super(Component.literal("Craftify Config"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        CraftifyConfig c = CraftifyConfig.get();

        int cx = this.width / 2;
        int y = 40;
        int btnW = 250;

        // ---- General ----
        addRenderableWidget(CycleButton.onOffBuilder(c.enabled)
                .create(cx - btnW / 2, y, btnW, 20,
                        Component.literal("Mod Enabled"),
                        (btn, val) -> CraftifyConfig.update(g -> g.enabled = val)));
        y += 24;

        addRenderableWidget(CycleButton.booleanBuilder(
                        Component.literal("Show"),
                        Component.literal("Hide"),
                        c.displayModeEnum() != CraftifyConfig.DisplayMode.NEVER)
                .create(cx - btnW / 2, y, btnW, 20,
                        Component.literal("Show HUD"),
                        (btn, val) -> CraftifyConfig.update(g ->
                                g.displayMode = val ? "SONG" : "NEVER")));
        y += 24;

        addRenderableWidget(CycleButton.onOffBuilder(c.showControls)
                .create(cx - btnW / 2, y, btnW, 20,
                        Component.literal("Controls on Hover"),
                        (btn, val) -> CraftifyConfig.update(g -> g.showControls = val)));
        y += 24;

        addRenderableWidget(CycleButton.onOffBuilder(c.showAlbumArt)
                .create(cx - btnW / 2, y, btnW, 20,
                        Component.literal("Album Art"),
                        (btn, val) -> CraftifyConfig.update(g -> g.showAlbumArt = val)));
        y += 24;

        // Display mode: SONG / ALWAYS / NEVER
        addRenderableWidget(CycleButton.<CraftifyConfig.DisplayMode>builder(
                        (CraftifyConfig.DisplayMode mode) -> Component.literal(mode.name()),
                        CraftifyConfig.DisplayMode.SONG)
                .withValues(List.of(CraftifyConfig.DisplayMode.values()))
                .create(cx - btnW / 2, y, btnW, 20,
                        Component.literal("Display Mode"),
                        (btn, val) -> CraftifyConfig.update(g -> g.displayMode = val.name())));
        y += 24;

        // ---- Accent color cycle ----
        List<String> accents = List.of("pink", "blue", "green", "mauve", "red", "yellow");
        addRenderableWidget(CycleButton.<String>builder(
                        (String col) -> Component.literal(col),
                        c.accentColor == null ? "pink" : c.accentColor)
                .withValues(accents)
                .create(cx - btnW / 2, y, btnW, 20,
                        Component.literal("Accent Color"),
                        (btn, val) -> CraftifyConfig.update(g -> g.accentColor = val)));
        y += 24;

        // ---- Widget size (sliders) ----
        addRenderableWidget(new IntSlider(cx - btnW / 2, y, btnW, 20,
                "Width", c.widgetW, 170, 460,
                v -> CraftifyConfig.update(g -> g.widgetW = v)));
        y += 24;
        addRenderableWidget(new IntSlider(cx - btnW / 2, y, btnW, 20,
                "Height", c.widgetH, 56, 220,
                v -> CraftifyConfig.update(g -> g.widgetH = v)));
        y += 24;

        // ---- Announcements ----
        addRenderableWidget(CycleButton.onOffBuilder(c.announcementEnabled)
                .create(cx - btnW / 2, y, btnW, 20,
                        Component.literal("Chat Announcements"),
                        (btn, val) -> CraftifyConfig.update(g -> g.announcementEnabled = val)));
        y += 30;

        // Reset position button
        addRenderableWidget(Button.builder(
                        Component.literal("Reset Position & Size"),
                        b -> CraftifyConfig.update(g -> {
                            g.widgetX = 5; g.widgetY = 5;
                            g.widgetW = 240; g.widgetH = 68;
                        }))
                .bounds(cx - btnW / 2, y, btnW, 20).build());
        y += 24;

        // Done button
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(cx - 100, Math.min(y + 4, this.height - 28), 200, 20).build());
    }

    @Override
    public void onClose() {
        CraftifyConfig.save();
        this.minecraft.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);
        // background dim
        g.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xC0101010);
        g.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);
    }

    /** Simple labeled integer slider (no vanilla Options-tied slider in 26.1.2). */
    private static class IntSlider extends AbstractSliderButton {
        private final String label;
        private final int min, max;
        private final java.util.function.IntConsumer onChange;

        IntSlider(int x, int y, int w, int h, String label, int initial,
                  int min, int max, java.util.function.IntConsumer onChange) {
            super(x, y, w, h,
                  Component.literal(label + ": " + initial),
                  (initial - min) / (double) (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.onChange = onChange;
        }

        private int value() {
            return min + (int) Math.round(value * (max - min));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(label + ": " + value()));
        }

        @Override
        protected void applyValue() {
            onChange.accept(value());
        }
    }
}
