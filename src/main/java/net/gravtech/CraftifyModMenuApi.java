package net.gravtech;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.gravtech.ui.ConfigScreen;

/**
 * ModMenu integration - shows the "config" gear on Craftify's ModMenu entry.
 * Soft-depends on ModMenu: this entrypoint is only loaded when ModMenu
 * is present (declared in fabric.mod.json "modmenu" entrypoint).
 */
public class CraftifyModMenuApi implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ConfigScreen::new;
    }
}
