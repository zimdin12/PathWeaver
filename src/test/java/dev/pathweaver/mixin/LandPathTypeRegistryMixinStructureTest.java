package dev.pathweaver.mixin;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LandPathTypeRegistryMixinStructureTest {
    @Test void productionConfigRequiresAllThreeExactRegistryHooks() throws Exception {
        try (InputStream in = LandPathTypeRegistryMixinStructureTest.class
                .getResourceAsStream("/pathweaver.mixins.json")) {
            assertNotNull(in);
            var json = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject();
            long count = json.getAsJsonArray("mixins").asList().stream()
                .filter(e -> e.getAsString().equals("LandPathTypeRegistryMixin")).count();
            assertEquals(1, count);
            assertTrue(json.get("required").getAsBoolean());
        }

        ClassNode node = new ClassNode();
        try (InputStream in = LandPathTypeRegistryMixin.class
                .getResourceAsStream("LandPathTypeRegistryMixin.class")) {
            assertNotNull(in);
            new ClassReader(in).accept(node, 0);
        }
        List<String> hooks = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AnnotationNode annotation : annotations(method)) {
                if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
                Object selector = value(annotation, "method");
                Object at = value(annotation, "at");
                boolean cancellable = Boolean.TRUE.equals(value(annotation, "cancellable"));
                int require = (Integer) value(annotation, "require");
                int expect = (Integer) value(annotation, "expect");
                assertEquals(1, require);
                assertEquals(1, expect);
                hooks.add(selector + "|" + atValue(at) + "|" + cancellable);
            }
        }
        assertEquals(Set.of(
            "[register(Lnet/minecraft/world/level/block/Block;Lnet/fabricmc/fabric/api/registry/LandPathTypeRegistry$StaticPathTypeProvider;)V]|INVOKE|false",
            "[registerDynamic(Lnet/minecraft/world/level/block/Block;Lnet/fabricmc/fabric/api/registry/LandPathTypeRegistry$DynamicPathTypeProvider;)V]|INVOKE|false",
            "[getPathTypeProvider(Lnet/minecraft/world/level/block/Block;)Lnet/fabricmc/fabric/api/registry/LandPathTypeRegistry$PathTypeProvider;]|HEAD|true"
        ), Set.copyOf(hooks));
    }

    private static List<AnnotationNode> annotations(MethodNode method) {
        List<AnnotationNode> result = new ArrayList<>();
        if (method.visibleAnnotations != null) result.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) result.addAll(method.invisibleAnnotations);
        return result;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static String atValue(Object at) {
        assertInstanceOf(List.class, at);
        Object first = ((List<?>) at).get(0);
        assertInstanceOf(AnnotationNode.class, first);
        return (String) value((AnnotationNode) first, "value");
    }
}
