package net.gravtech

import net.fabricmc.api.ModInitializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class Craftify : ModInitializer {
    companion object {
        const val MOD_ID = "craftify-ytm-web"
        val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)
        lateinit var ytmController: YTMWebController
    }

    override fun onInitialize() {
        LOGGER.info("Initializing Craftify Chrome/Thorium YTM for Fabric 26.1.2")
        ytmController = YTMWebController("ws://localhost:8765/youtube-music")
        LOGGER.info("YTMWebController initialized")
    }
}
