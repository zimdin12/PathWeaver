package dev.pathweaver.gate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The selector parser that decides whether a foreign claim can be cleared.
 *
 * <p>This is the half of the narrowing most likely to be quietly wrong, because it parses strings
 * written by other people. Every ambiguous shape must resolve to "cannot tell", since the caller
 * treats an answer as permission to stop denying.
 */
class MixinClaimMethodsTest {

    @Test
    void plainNamesAndDescriptorFormsResolve() {
        assertEquals("getCollisionShape",
            MixinClaimMethods.bareMethodName("getCollisionShape"));
        assertEquals("getCollisionShape",
            MixinClaimMethods.bareMethodName(
                "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;)V"));
        assertEquals("getShape",
            MixinClaimMethods.bareMethodName(
                "Lnet/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase;getShape()V"));
        assertEquals("<init>", MixinClaimMethods.bareMethodName("<init>(I)V"));
    }

    @Test
    void everyAmbiguousSelectorRefusesToAnswer() {
        // Each of these could match a method a search DOES reach. Guessing here would clear a claim
        // that should deny, which is the one direction this must never fail in.
        assertNull(MixinClaimMethods.bareMethodName("*"), "wildcard must not resolve");
        assertNull(MixinClaimMethods.bareMethodName("get*"), "prefix wildcard must not resolve");
        assertNull(MixinClaimMethods.bareMethodName("/get.*Shape/"), "regex must not resolve");
        assertNull(MixinClaimMethods.bareMethodName("${dynamic}"), "dynamic selector must not resolve");
        assertNull(MixinClaimMethods.bareMethodName(""), "empty must not resolve");
        assertNull(MixinClaimMethods.bareMethodName(null), "null must not resolve");
        assertNull(MixinClaimMethods.bareMethodName("   "), "blank must not resolve");
    }

    @Test
    void aMixinWithNoInjectorsCannotBeCleared() {
        // A bare @Mixin may still add fields or implement interfaces on the target. "No injectors"
        // is not the same as "harmless", so it must read as unresolvable.
        assertTrue(MixinClaimMethods.injectedMethodsOf(
                "dev.pathweaver.mixin.PathFinderAccessor").isEmpty(),
            "an accessor-only mixin must not be reported as having clearable injected methods");
    }

    @Test
    void aClassThatCannotBeReadCannotBeCleared() {
        assertTrue(MixinClaimMethods.injectedMethodsOf("com.example.NoSuchMixin").isEmpty(),
            "unreadable bytes must fail closed");
    }

    @Test
    void ourOwnInjectorsAreParsedFromRealBytecode() {
        // Non-vacuity: if the parser silently matched nothing, every test above would still pass.
        var methods = MixinClaimMethods.injectedMethodsOf(
            "dev.pathweaver.mixin.WalkNodeEvaluatorMixin");
        assertTrue(methods.isPresent(), "a mixin with real @Redirects must resolve its targets");
        assertTrue(methods.get().contains("getNeighbors"),
            "expected the redirected getNeighbors target: " + methods.get());
        assertTrue(methods.get().contains("tryFindFirstGroundNodeBelow"),
            "expected the redirected tryFindFirstGroundNodeBelow target: " + methods.get());
    }
}
