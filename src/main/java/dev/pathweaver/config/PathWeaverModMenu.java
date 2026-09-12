package dev.pathweaver.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.pathweaver.PathWeaver;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Offers the settings screen through ModMenu, when there is a settings screen to offer.
 *
 * <p><b>This class must not name a single Cloth type.</b> It is loaded whenever ModMenu is installed,
 * including on setups with no cloth-config, and a Cloth type in its signature or body would be
 * resolved at that moment. The screen itself lives in {@link ClothScreen}, which is only touched
 * inside the branch below, after the mod has been confirmed present.
 *
 * <p>Cloth is optional now. Without it the mod reads and writes the same JSON file and does
 * everything it does on a server; what is missing is the screen, and a screen is not something a
 * dedicated server was ever going to draw.
 */
public final class PathWeaverModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
                PathWeaver.LOG.info("PathWeaver has no settings screen without Cloth Config; edit "
                    + "config/pathweaver.json instead.");
                return parent;
            }
            try {
                return ClothScreen.build(parent);
            } catch (RuntimeException | LinkageError e) {
                PathWeaver.LOG.warn("PathWeaver config screen is unavailable; returning to ModMenu.", e);
                return parent;
            }
        };
    }
}
