package dev.pathweaver.async;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.world.level.pathfinder.NodeEvaluator;

/**
 * Builds a fresh {@link NodeEvaluator} of the same class as a mob's evaluator and copies the
 * configuration that decides what a search may traverse. This isolates evaluator scratch state from
 * the navigation's own evaluator. It does not prove path equivalence, because the search still
 * consumes live-backed region and mob inputs.
 *
 * <p>This used to name two classes. That made it a second, invisible allowlist: when the safety gate
 * was widened to admit third-party evaluator subclasses, every one of them reached here and was
 * rejected, so the widening did nothing at all. Two gates disagreeing silently is worse than one
 * gate refusing loudly, so this no longer enumerates classes — it works out how to build one.
 *
 * <p>Resolution, cached per class, in order:
 * <ol>
 *   <li>a no-argument constructor ({@code WalkNodeEvaluator}, {@code FlyNodeEvaluator}, and most
 *       third-party evaluators);</li>
 *   <li>a one-{@code boolean} constructor paired with the single {@code final boolean} field declared
 *       below {@code NodeEvaluator} ({@code SwimNodeEvaluator.allowBreaching},
 *       {@code AmphibiousNodeEvaluator.prefersShallowSwimming}, inherited by the frog's evaluator);</li>
 *   <li>a one-reference constructor paired with the single field of exactly that type — how an
 *       evaluator declared inside its mob is rebuilt, such as the creaking's, whose synthetic outer
 *       reference is its only state.</li>
 * </ol>
 *
 * <p>Anything else is refused, and refusal means that mob keeps pathing synchronously. Guessing at a
 * constructor whose arguments we do not understand would produce an evaluator configured differently
 * from the one the mob actually uses, and a subtly wrong path is worse than a synchronous one.
 *
 * <p><strong>What a rebuild does not carry over.</strong> Only the constructor argument and the four
 * traversal flags {@code NodeEvaluator} exposes are reproduced. A vanilla evaluator holds nothing
 * else, so for the six allowlisted classes the rebuild is complete. A third-party evaluator admitted
 * at the unsafe tier may hold configuration this knows nothing about — a field set after
 * construction, a reference to its mod's own settings — and that state is silently absent from the
 * copy the worker searches with. The result is a path computed under different rules than the mob's
 * own evaluator would have used: not a crash, not a race, just a quietly different answer. It is
 * part of what "unsafe" is buying, and it is the failure mode least likely to be noticed.
 */
public final class EvaluatorCloner {
    private EvaluatorCloner() {}

    /** @return a new evaluator of {@code src.getClass()} with matching configuration. */
    public static NodeEvaluator cloneWithConfig(NodeEvaluator src) throws ReflectiveOperationException {
        Rebuilder rebuilder = REBUILDERS.get(src.getClass());
        if (rebuilder == null) {
            throw new IllegalArgumentException(
                "No usable constructor for evaluator: " + src.getClass().getName());
        }
        NodeEvaluator fresh = rebuilder.rebuild(src);
        fresh.setCanPassDoors(src.canPassDoors());
        fresh.setCanOpenDoors(src.canOpenDoors());
        fresh.setCanFloat(src.canFloat());
        fresh.setCanWalkOverFences(src.canWalkOverFences());
        return fresh;
    }

    /** True when this evaluator class can be rebuilt at all. Resolution is cached, so this is cheap. */
    public static boolean canClone(Class<?> evaluatorClass) {
        return REBUILDERS.get(evaluatorClass) != null;
    }

    @FunctionalInterface
    private interface Rebuilder {
        NodeEvaluator rebuild(NodeEvaluator src) throws ReflectiveOperationException;
    }

    /**
     * Reflection lookups are not cheap and this runs on every dispatch, so each class is resolved
     * once. {@link ClassValue} keys on the class itself, so an unloaded mod's classes become
     * collectable with it rather than pinning them in a static map forever.
     */
    private static final ClassValue<Rebuilder> REBUILDERS = new ClassValue<>() {
        @Override
        protected Rebuilder computeValue(Class<?> type) {
            return resolve(type);
        }
    };

    private static Rebuilder resolve(Class<?> type) {
        Constructor<?> noArgs = declaredConstructor(type);
        if (noArgs != null) return src -> (NodeEvaluator) noArgs.newInstance();

        Constructor<?>[] all = type.getDeclaredConstructors();
        for (Constructor<?> candidate : all) {
            if (candidate.getParameterCount() != 1) continue;
            Class<?> parameter = candidate.getParameterTypes()[0];

            Field source = parameter == boolean.class
                ? soleFinalBooleanField(type)
                : soleFieldOfType(type, parameter);
            if (source == null) continue;

            try {
                candidate.setAccessible(true);
                source.setAccessible(true);
            } catch (RuntimeException inaccessible) {
                continue;
            }
            return src -> (NodeEvaluator) candidate.newInstance(source.get(src));
        }
        return null;
    }

    private static Constructor<?> declaredConstructor(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }

    /**
     * The single {@code final boolean} declared between {@code type} and {@code NodeEvaluator}.
     *
     * <p>{@code NodeEvaluator}'s own flags are excluded because they are mutable and already copied
     * through their setters. Requiring exactly one match is the point: two would mean guessing which
     * one the constructor takes, and a wrongly configured evaluator searches a different world than
     * the mob lives in.
     */
    private static Field soleFinalBooleanField(Class<?> type) {
        return soleMatch(type, field -> field.getType() == boolean.class
            && Modifier.isFinal(field.getModifiers()));
    }

    private static Field soleFieldOfType(Class<?> type, Class<?> parameter) {
        return soleMatch(type, field -> field.getType() == parameter);
    }

    private static Field soleMatch(Class<?> type, java.util.function.Predicate<Field> matches) {
        Field found = null;
        for (Class<?> level = type; level != null && level != NodeEvaluator.class;
                level = level.getSuperclass()) {
            for (Field field : level.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !matches.test(field)) continue;
                if (found != null) return null;              // ambiguous: refuse rather than guess
                found = field;
            }
        }
        return found;
    }
}
