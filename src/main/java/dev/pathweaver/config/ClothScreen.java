package dev.pathweaver.config;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings screen, built against Cloth directly instead of by AutoConfig.
 *
 * <p><b>This class is only ever loaded when cloth-config is installed.</b> Every Cloth type it names
 * is resolved on first use, so the guard in {@link PathWeaverModMenu} that checks the mod is present
 * must stay in a class that does not mention Cloth at all. That is the whole reason this is a
 * separate file.
 *
 * <p>AutoConfig used to do this, and doing it meant {@code PathWeaverConfig} had to implement a Cloth
 * interface, which made Cloth a hard dependency of a server-side mod for the sake of a screen no
 * dedicated server renders. Building the screen by hand costs this file; it buys every server owner
 * not installing a GUI library.
 *
 * <p>Built by REFLECTION over the same annotations and the same translation keys AutoConfig used, not
 * from a list of options maintained here. A list would need editing every time a setting is added,
 * and the setting that gets forgotten is the one that silently stops appearing. The existing
 * settings-screen contract test still checks those annotations and keys, so it still covers this.
 */
public final class ClothScreen {

    private static final String KEY = "text.autoconfig.pathweaver.option.";

    private ClothScreen() { }

    public static Screen build(Screen parent) {
        PathWeaverConfig live = PathWeaverConfig.get();
        PathWeaverConfig editing = PathWeaverConfig.copyOf(live);

        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Component.translatable("text.autoconfig.pathweaver.title"))
            .setSavingRunnable(() -> PathWeaverConfig.save(editing));
        ConfigEntryBuilder entries = builder.entryBuilder();

        // Categories in declaration order, so the screen's shape follows the config file's rather
        // than an alphabetical accident.
        Map<String, ConfigCategory> categories = new LinkedHashMap<>();
        for (Field field : PathWeaverConfig.class.getDeclaredFields()) {
            if (!isOption(field)) continue;
            ConfigEntry.Category category = field.getAnnotation(ConfigEntry.Category.class);
            String name = category == null ? "general" : category.value();
            ConfigCategory target = categories.computeIfAbsent(name, key ->
                builder.getOrCreateCategory(
                    Component.translatable("text.autoconfig.pathweaver.category." + key)));
            addEntry(entries, target, field, editing);
        }
        return builder.build();
    }

    private static boolean isOption(Field field) {
        return !Modifier.isStatic(field.getModifiers())
            && !Modifier.isTransient(field.getModifiers())
            && !field.isAnnotationPresent(ConfigEntry.Gui.Excluded.class);
    }

    private static Component[] tooltip(Field field) {
        ConfigEntry.Gui.Tooltip declared = field.getAnnotation(ConfigEntry.Gui.Tooltip.class);
        if (declared == null) return new Component[0];
        List<Component> lines = new ArrayList<>();
        for (int i = 0; i < declared.count(); i++) {
            lines.add(Component.translatable(KEY + field.getName() + ".@Tooltip[" + i + "]"));
        }
        return lines.toArray(new Component[0]);
    }

    /**
     * One control per setting, chosen by the field's type.
     *
     * <p>{@code requireRestart} is called with no arguments and only when it applies: the overload
     * taking a boolean returns void rather than the builder, so chaining it silently ends the chain.
     * That is a Cloth API detail and the compiler caught it, which is the only reason it is not a
     * screen with no save consumers attached.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addEntry(ConfigEntryBuilder entries, ConfigCategory category,
                                 Field field, PathWeaverConfig editing) {
        field.setAccessible(true);
        Component label = Component.translatable(KEY + field.getName());
        Component[] tooltip = tooltip(field);
        boolean restart = field.isAnnotationPresent(ConfigEntry.Gui.RequiresRestart.class);
        Class<?> type = field.getType();
        try {
            Object current = field.get(editing);
            Object fallback = field.get(new PathWeaverConfig());
            if (type == boolean.class) {
                var builder = entries.startBooleanToggle(label, (Boolean) current)
                    .setDefaultValue((Boolean) fallback).setTooltip(tooltip)
                    .setSaveConsumer(value -> set(field, editing, value));
                if (restart) builder.requireRestart();
                category.addEntry(builder.build());
            } else if (type == int.class) {
                var builder = entries.startIntField(label, (Integer) current)
                    .setDefaultValue((Integer) fallback).setTooltip(tooltip)
                    .setSaveConsumer(value -> set(field, editing, value));
                if (restart) builder.requireRestart();
                category.addEntry(builder.build());
            } else if (type == double.class) {
                var builder = entries.startDoubleField(label, (Double) current)
                    .setDefaultValue((Double) fallback).setTooltip(tooltip)
                    .setSaveConsumer(value -> set(field, editing, value));
                if (restart) builder.requireRestart();
                category.addEntry(builder.build());
            } else if (type.isEnum()) {
                // The label for each constant comes from the enum's own getKey(), which AutoConfig
                // used to reach through a Cloth interface the enum implemented. That interface is
                // what made a server without Cloth fail to start, so the key is asked for directly.
                var builder = entries.startEnumSelector(label, (Class) type, (Enum) current)
                    .setDefaultValue((Enum) fallback).setTooltip(tooltip)
                    .setEnumNameProvider(value -> Component.translatable(keyOf((Enum<?>) value)))
                    .setSaveConsumer(value -> set(field, editing, value));
                if (restart) builder.requireRestart();
                category.addEntry(builder.build());
            } else if (List.class.isAssignableFrom(type)) {
                var builder = entries.startStrList(label, new ArrayList<>((List<String>) current))
                    .setDefaultValue(new ArrayList<>((List<String>) fallback)).setTooltip(tooltip)
                    .setSaveConsumer(value -> set(field, editing, value));
                if (restart) builder.requireRestart();
                category.addEntry(builder.build());
            } else {
                // FAILS LOUD. A settings type nobody wrote a control for would otherwise vanish from
                // the screen, and a setting that cannot be seen is a setting nobody can turn off.
                throw new IllegalStateException(
                    "no settings control for " + field.getName() + " of type " + type);
            }
        } catch (IllegalAccessException unreachable) {
            throw new IllegalStateException("could not read " + field.getName(), unreachable);
        }
    }

    /**
     * The translation key for an enum constant, from the constant itself.
     *
     * <p>Reflective because the two settings enums are unrelated types that happen to offer the same
     * method. Giving them a shared interface would be cleaner and would put a class in the load path
     * of every one of them, which is the mistake being undone here.
     */
    private static String keyOf(Enum<?> value) {
        try {
            return (String) value.getClass().getMethod("getKey").invoke(value);
        } catch (ReflectiveOperationException noKey) {
            return value.toString();
        }
    }

    private static void set(Field field, PathWeaverConfig target, Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException unreachable) {
            throw new IllegalStateException("could not write " + field.getName(), unreachable);
        }
    }
}
