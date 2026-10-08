package net.gravtech

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text

class CraftifyClient : ClientModInitializer {
    override fun onInitializeClient() {
        Craftify.LOGGER.info("Initializing Craftify Client")
        
        // Register /craftify command
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            // Command registration here
        }
        
        // Tick handler for YTM state sync
        ClientTickEvents.END_CLIENT_TICK.register { client: MinecraftClient ->
            if (client.player != null) {
                // Sync YTM state to client
            }
        }
    }
}
