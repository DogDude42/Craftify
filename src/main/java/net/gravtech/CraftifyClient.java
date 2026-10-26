package net.gravtech;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.gravtech.ui.YtmHud;

/**
 * Client-side bootstrap: registers the HUD element and keybinds.
 * The controller itself is created in Craftify (common init).
 */
public class CraftifyClient implements ClientModInitializer {

    public static final Identifier MOD_CATEGORY_ID =
            Identifier.fromNamespaceAndPath(Craftify.MOD_ID, "craftify");

    public static KeyMapping toggleHudKey;
    public static KeyMapping togglePlayingKey;
    public static KeyMapping skipForwardKey;
    public static KeyMapping skipPreviousKey;

    @Override
    public void onInitializeClient() {
        Craftify.LOGGER.info("Initializing Craftify Client (HUD + keybinds)");

        KeyMapping.Category category =
                KeyMapping.Category.register(MOD_CATEGORY_ID);

        toggleHudKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.craftify.toggle_hud",
                        InputConstants.KEY_H, category));
        togglePlayingKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.craftify.toggle_playing",
                        0, category)); // 0 = unbound (InputConstants.UNKNOWN)
        skipForwardKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.craftify.skip_forward",
                        0, category)); // 0 = unbound (InputConstants.UNKNOWN)
        skipPreviousKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.craftify.skip_previous",
                        0, category)); // 0 = unbound (InputConstants.UNKNOWN)

        YtmHud.register();
        Craftify.LOGGER.info("Craftify HUD registered");

        // /craftify opens the config screen (like upstream)
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
                .EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(
                com.mojang.brigadier.builder.LiteralArgumentBuilder
                    .<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>
                        literal("craftify")
                    .executes(ctx -> {
                        Minecraft.getInstance().execute(() ->
                                Minecraft.getInstance().gui.setScreen(
                                        new net.gravtech.ui.ConfigScreen(null)));
                        return 1;
                    })
            )
        );
    }

    /** Called every frame from YtmHud render to poll keybinds. */
    public static void handleKeybinds() {
        while (toggleHudKey.consumeClick()) {
            YtmHud.toggleVisible();
        }
        while (togglePlayingKey.consumeClick()) {
            YTMWebController c = Craftify.getYtmController();
            if (c != null) {
                YTMWebController.YTMState s = c.lastState();
                if (s != null && s.playing) c.pause();
                else c.play();
            }
        }
        while (skipForwardKey.consumeClick()) {
            YTMWebController c = Craftify.getYtmController();
            if (c != null) c.nextTrack();
        }
        while (skipPreviousKey.consumeClick()) {
            YTMWebController c = Craftify.getYtmController();
            if (c != null) c.previousTrack();
        }
    }
}
