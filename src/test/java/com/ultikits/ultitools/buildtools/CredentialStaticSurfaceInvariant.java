package com.ultikits.ultitools.buildtools;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeMap;

import com.ultikits.ultitools.entities.TokenEntity;

/**
 * D-18's structural enforcement: no {@code public static} method in the {@code utils} package may
 * accept or return a {@link TokenEntity} or a generation. Plan 16-09 removed every such method from
 * {@code CloudAuthManager} and {@code PluginInitiationUtils} outright (D-17) -- this rule is what
 * keeps a static bypass of {@code CloudSession#commit(TokenEntity)} from being reintroduced without
 * anyone noticing, the same way {@link com.ultikits.ultitools.buildtools.deprecation.OverBroadExclusionInvariant}
 * keeps a stale japicmp exclude from silencing a class nobody removed.
 *
 * <p>Pure logic over an already-derived input -- the {@link Method} objects to check -- so this is
 * unit-testable with hand-built synthetic fixtures, without touching the classpath or the real
 * {@code utils} package at all. {@code CredentialStaticSurfaceInvariantTest} is the sole caller that
 * derives real input, by scanning compiled class files rather than checking a hand-written list of
 * class names (so a class added to the package later is covered without editing the test).
 *
 * <p><b>What "generation-shaped" means here, and why it is name-based rather than a blanket
 * {@code long}:</b> this build does not compile with {@code -parameters}, so reflection erases
 * parameter names -- "a {@code long} named {@code generation}" and "a {@code long} named
 * {@code cooldownMs}" are indistinguishable once compiled, and a blanket "any {@code long} is
 * generation-shaped" rule was measured to false-positive on real, unrelated members:
 * {@code ApiRateLimiter#isAllowed(String, long)} / {@code #getRemainingCooldown(String, long)} (a
 * cooldown duration in milliseconds) and {@code SecurityPolicy#isSafeFileStructure(long, int)} (the
 * Zip-Bomb guard's byte-size limit). The one signal reflection does not erase is the method's own
 * name, and every generation-carrying method this package ever declared that named the concept in
 * its own name did so with the literal word "generation"
 * ({@code CloudAuthManager#currentCredentialGeneration()}). This rule therefore treats a {@code long}
 * parameter or return type as generation-shaped only on a method whose own name contains
 * "generation" (case-insensitive) -- catching a reintroduction that names itself honestly, at the
 * cost of not catching a hypothetical future overload that reuses an existing innocuous name (like
 * {@code startPolling}) with a bare extra {@code long} tacked on and no textual hint anywhere. That
 * narrower shape was possible for the two now-removed shim overloads
 * ({@code #startPolling(String, Consumer, long)}, {@code PluginInitiationUtils#activateCloudIfCurrent(long)})
 * and is an accepted, named blind spot of this specific check -- the {@link TokenEntity} half of the
 * rule below carries no such gap and applies to every {@code public static} method in the package
 * unconditionally, which is the shape a reintroduction is far more likely to need in the first place
 * (there has to be a token to actually persist one).
 */
public final class CredentialStaticSurfaceInvariant {

    private CredentialStaticSurfaceInvariant() {
    }

    /**
     * @param method the method to classify
     * @return {@code true} if {@code method}'s own name contains "generation" (case-insensitive) --
     *         see the class javadoc for why this is name-based rather than a blanket {@code long} check
     */
    private static boolean nameSuggestsGeneration(Method method) {
        return method.getName().toLowerCase(Locale.ROOT).contains("generation");
    }

    /**
     * Evaluates the rule over {@code methods}. Never throws on a violation, only on a {@code null}
     * argument or a {@code null} element.
     *
     * @param methods the methods to check -- typically every declared method of one or more classes
     * @return one violation message per offending {@code public static} method, naming the method
     *         and which condition(s) it violates; empty when the invariant holds. Deterministically
     *         ordered by the method's own {@link Method#toString()} so repeated runs over the same
     *         input produce identical output.
     */
    public static List<String> evaluate(Collection<Method> methods) {
        Objects.requireNonNull(methods, "methods");

        // TreeMap keyed by Method#toString() -- deterministic order regardless of the caller's
        // collection implementation or insertion order, mirroring RemovalConsistencyEvaluator's use
        // of a TreeSet for the same reason.
        TreeMap<String, Method> sorted = new TreeMap<>();
        for (Method method : methods) {
            Objects.requireNonNull(method, "methods must not contain a null element");
            sorted.put(method.toString(), method);
        }

        List<String> violations = new ArrayList<>();
        for (Method method : sorted.values()) {
            int modifiers = method.getModifiers();
            if (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers)) {
                // The rule is about the PUBLIC surface, not about statics in general -- a
                // package-private or private static method is not reachable from outside the
                // package and is not the bypass D-18 names.
                continue;
            }

            List<String> reasons = new ArrayList<>();
            if (method.getReturnType() == TokenEntity.class) {
                reasons.add("returns " + TokenEntity.class.getSimpleName());
            }
            boolean acceptsToken = false;
            for (Class<?> paramType : method.getParameterTypes()) {
                if (paramType == TokenEntity.class) {
                    acceptsToken = true;
                }
            }
            if (acceptsToken) {
                reasons.add("accepts a " + TokenEntity.class.getSimpleName() + " parameter");
            }

            if (nameSuggestsGeneration(method)) {
                boolean generationReturn = method.getReturnType() == long.class;
                boolean generationParam = false;
                for (Class<?> paramType : method.getParameterTypes()) {
                    if (paramType == long.class) {
                        generationParam = true;
                    }
                }
                if (generationReturn) {
                    reasons.add("returns a generation-shaped long");
                }
                if (generationParam) {
                    reasons.add("accepts a generation-shaped long parameter");
                }
            }

            if (!reasons.isEmpty()) {
                violations.add(describe(method) + ": " + String.join("; ", reasons));
            }
        }
        return violations;
    }

    private static String describe(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }
}
