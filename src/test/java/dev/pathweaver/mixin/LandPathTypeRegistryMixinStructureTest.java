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
            "[registerDynamic(Lnet/minecraft/world/level/block/Block;Lnet/fabricmc/fabric/api/registry/LandPathTypeRegistry$DynamicPathTypeProvider;)V]|INVOKE|false"
        ), Set.copyOf(hooks));

        // The per-node lookup is a redirect on the one Map.get, never a cancellable inject: a
        // cancellable inject allocates a CallbackInfoReturnable per call, on every thread, per node.
        List<String> redirects = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AnnotationNode annotation : annotations(method)) {
                if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")) continue;
                assertEquals(1, (Integer) value(annotation, "require"));
                assertEquals(1, (Integer) value(annotation, "expect"));
                Object atRaw = value(annotation, "at");
                AnnotationNode at = atRaw instanceof List<?> l ? (AnnotationNode) l.get(0) : (AnnotationNode) atRaw;
                redirects.add(value(annotation, "method") + "|" + value(at, "value") + "|" + value(at, "target"));
            }
        }
        assertEquals(List.of(
            "[getPathTypeProvider(Lnet/minecraft/world/level/block/Block;)Lnet/fabricmc/fabric/api/registry/LandPathTypeRegistry$PathTypeProvider;]"
                + "|INVOKE|Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"
        ), redirects, "the per-node provider lookup must be decided on its Map.get, without a cancellable inject");
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
