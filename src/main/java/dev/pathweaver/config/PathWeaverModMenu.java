package dev.pathweaver.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.autoconfig.AutoConfigClient;

/** Exposes PathWeaver's persisted Cloth Config screen through ModMenu's config button. */
public final class PathWeaverModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> AutoConfigClient.getConfigScreen(PathWeaverConfig.class, parent).get();
    }
}
