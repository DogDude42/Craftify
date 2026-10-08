package net.gravtech;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Craftify implements ModInitializer {
    public static final String MOD_ID = "craftify-ytm-web";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile YTMWebController ytmController;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Craftify Chrome/Thorium YTM (MC 26.1.2)");
        CraftifyConfig.load();
        ytmController = YTMWebController.getInstance(CraftifyConfig.get().bridgeUrl);
        LOGGER.info("YTMWebController initialized (bridge: {})", CraftifyConfig.get().bridgeUrl);
    }

    public static YTMWebController getYtmController() {
        return ytmController;
    }
}
