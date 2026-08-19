package dev.pathweaver.gametest;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * The screen the client is currently showing, across a field that moved between versions.
 *
 * <p>26.1.2 has {@code Minecraft.screen}, public. 26.2 removed it: the client has no
 * {@code Screen}-typed field at all any more, and the current screen lives on {@code Gui.screen},
 * which is private. A test naming either one compiles against exactly one version.
 *
 * <p>Reflection is the wrong tool almost everywhere and is the right one here: the alternative is an
 * accessor mixin, and this source set's mixins target {@code PathNavigation} — a class the
 * compatibility scanner watches — so every mixin added here changes what the scan reports and has
 * already once contaminated the measurement it exists to take. Reflection adds no mixin.
 *
 * <p>Resolved by TYPE rather than by name, so a future rename of the field itself does not break it;
 * only a move to a third owner would, and that fails loudly rather than silently returning null.
 */
final class CurrentScreen {

    private CurrentScreen() {}

    /** The showing screen, or null when none is. */
    static Screen get(Minecraft client) {
        Screen direct = screenFieldOf(client, client);
        if (direct != NOT_FOUND) return direct;
        // 26.2: hanging off the Gui instance instead.
        Object gui = fieldValueOfType(client, "net.minecraft.client.gui.Gui");
        if (gui != null) {
            Screen viaGui = screenFieldOf(gui, client);
            if (viaGui != NOT_FOUND) return viaGui;
        }
        throw new IllegalStateException(
            "no Screen-typed field found on Minecraft or its Gui -- the current-screen field moved "
                + "again, and this accessor needs updating rather than the test being deleted");
    }

    /** Distinguishes "field present, currently null" from "no such field". */
    private static final Screen NOT_FOUND = null;

    private static Screen screenFieldOf(Object owner, Minecraft client) {
        for (Field f : owner.getClass().getDeclaredFields()) {
            if (!Screen.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                return (Screen) f.get(owner);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // keep looking; an inaccessible field is not the answer
            }
        }
        return NOT_FOUND;
    }

    private static Object fieldValueOfType(Object owner, String className) {
        for (Field f : owner.getClass().getDeclaredFields()) {
            if (!f.getType().getName().equals(className)) continue;
            try {
                f.setAccessible(true);
                return f.get(owner);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // fall through
            }
        }
        return null;
    }
}
