package com.ultikits.ultitools.buildtools;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
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
 *
 * <p><b>WR-03 (16-REVIEW-cloud.md) -- three gaps in the original scope, two closed, one documented
 * rather than closed:</b>
 * <ol>
 *   <li><b>Closed.</b> {@link #evaluate(Collection)} originally checked only the <em>erased</em>
 *   return/parameter types ({@link Method#getReturnType()}/{@link Method#getParameterTypes()}), so a
 *   hypothetical {@code public static void login(Consumer<TokenEntity> onSuccess)} or
 *   {@code public static Optional<TokenEntity> peek()} would have reported {@code Consumer.class}/
 *   {@code Optional.class} and passed silently while still handing the raw token to any caller. This
 *   method now also walks {@link Method#getGenericReturnType()}/{@link Method#getGenericParameterTypes()}
 *   via {@link #referencesTokenEntity(Type)}, recursing into every type argument of a
 *   {@link ParameterizedType} (so {@code Optional<List<TokenEntity>>} is caught, not just one level
 *   deep) and into a {@link WildcardType}'s bounds.</li>
 *   <li><b>Closed.</b> Fields were never scanned -- only {@link Method} objects reached this class.
 *   {@link #evaluateFields(Collection)} is the field-shaped twin of {@link #evaluate(Collection)},
 *   catching a hypothetical {@code public static TokenEntity leaked;}.</li>
 *   <li><b>Documented, not closed.</b> Both checks above are scoped to the {@code public static}
 *   surface, matching D-18's own literal wording ("no {@code public static} method... may accept or
 *   return a {@code TokenEntity}"). A {@code public} <em>instance</em> method returning
 *   {@code TokenEntity} on some future public, instantiable class in this package would pass either
 *   check. This is a real, accepted gap, not an oversight: today it is unreachable in practice --
 *   {@code CloudAuthManager}'s constructor is {@code private} and {@code CloudSession}/
 *   {@code TokenStore} are package-private, so no public, instantiable class in {@code utils} exposes
 *   any instance method to a caller outside the package at all. If that ever changes, this rule
 *   provides no protection and would need to be extended to instance methods on public classes too.
 *   </li>
 * </ol>
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
    // PMD.NPathComplexity: raised well above the 200 threshold by WR-03's generic-type checks
    // (16-10, 16-REVIEW-cloud.md) layered onto the pre-existing erased-type and generation checks.
    // The method is a flat sequence of independent per-condition guards over one sorted loop -- no
    // guard nests inside another -- and the count is the product of those independent checks, the
    // same shape RegistryLedger.merge()'s own identical suppression already documents in this
    // codebase, not a measure of genuinely tangled control flow.
    @SuppressWarnings("PMD.NPathComplexity")
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
            } else if (referencesTokenEntity(method.getGenericReturnType())) {
                // WR-03: the erased check above missed this -- e.g. Optional<TokenEntity> reports
                // Optional.class to getReturnType(), never TokenEntity.class. Checked as an "else"
                // only so a directly-erased TokenEntity return does not additionally recurse through
                // its own (non-generic) type and produce a duplicate, differently-worded reason.
                reasons.add("returns a generic type that references " + TokenEntity.class.getSimpleName());
            }

            boolean acceptsToken = false;
            boolean acceptsTokenGenerically = false;
            for (Class<?> paramType : method.getParameterTypes()) {
                if (paramType == TokenEntity.class) {
                    acceptsToken = true;
                }
            }
            if (!acceptsToken) {
                for (Type paramType : method.getGenericParameterTypes()) {
                    if (referencesTokenEntity(paramType)) {
                        acceptsTokenGenerically = true;
                    }
                }
            }
            if (acceptsToken) {
                reasons.add("accepts a " + TokenEntity.class.getSimpleName() + " parameter");
            } else if (acceptsTokenGenerically) {
                reasons.add("accepts a parameter whose generic type references " + TokenEntity.class.getSimpleName());
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

    /**
     * The field-shaped twin of {@link #evaluate(Collection)} (WR-03, 16-REVIEW-cloud.md): no
     * {@code public static} field in the {@code utils} package may be typed (erased or generically)
     * as a {@link TokenEntity}. Fields were entirely outside the original scope of this class --
     * only {@link Method} objects were ever passed in.
     *
     * @param fields the fields to check -- typically every declared field of one or more classes
     * @return one violation message per offending {@code public static} field; empty when the
     *         invariant holds. Deterministically ordered by the field's own {@link Field#toString()}.
     */
    public static List<String> evaluateFields(Collection<Field> fields) {
        Objects.requireNonNull(fields, "fields");

        TreeMap<String, Field> sorted = new TreeMap<>();
        for (Field field : fields) {
            Objects.requireNonNull(field, "fields must not contain a null element");
            sorted.put(field.toString(), field);
        }

        List<String> violations = new ArrayList<>();
        for (Field field : sorted.values()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers)) {
                continue;
            }
            if (field.getType() == TokenEntity.class) {
                violations.add(describeField(field) + ": is typed as " + TokenEntity.class.getSimpleName());
            } else if (referencesTokenEntity(field.getGenericType())) {
                violations.add(describeField(field)
                        + ": has a generic type that references " + TokenEntity.class.getSimpleName());
            }
        }
        return violations;
    }

    private static String describeField(Field field) {
        return field.getDeclaringClass().getName() + "#" + field.getName();
    }

    /**
     * Recursively determines whether {@code type} mentions {@link TokenEntity} anywhere in its
     * generic structure -- the raw type itself, any type argument of a {@link ParameterizedType}
     * (recursing into each, so a doubly-nested {@code Optional<List<TokenEntity>>} is caught, not
     * just a single level of wrapping), an array component type, or a {@link WildcardType}'s upper
     * or lower bounds (WR-03, 16-REVIEW-cloud.md). {@code null}-safe: a bound array being empty (an
     * unbounded wildcard) is not an error, just nothing further to check.
     *
     * @param type a {@link Type} obtained from {@link Method#getGenericReturnType()},
     *             {@link Method#getGenericParameterTypes()}, or {@link Field#getGenericType()}
     * @return {@code true} if {@code type} references {@link TokenEntity} anywhere in its structure
     */
    // PMD.NPathComplexity: the loops over getActualTypeArguments()/getUpperBounds()/getLowerBounds()
    // each recurse, and PMD's path count for a recursive method does not converge the way it does
    // for a flat one -- the actual runtime depth is bounded by the type's own nesting, which is
    // shallow for every real signature in this package (see the class javadoc's worked example,
    // Optional<List<TokenEntity>>, two levels deep).
    @SuppressWarnings("PMD.NPathComplexity")
    private static boolean referencesTokenEntity(Type type) {
        if (type == null) {
            return false;
        }
        if (type == TokenEntity.class) {
            return true;
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) type;
            if (referencesTokenEntity(parameterized.getRawType())) {
                return true;
            }
            for (Type typeArgument : parameterized.getActualTypeArguments()) {
                if (referencesTokenEntity(typeArgument)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof GenericArrayType) {
            return referencesTokenEntity(((GenericArrayType) type).getGenericComponentType());
        }
        if (type instanceof Class<?> && ((Class<?>) type).isArray()) {
            return referencesTokenEntity(((Class<?>) type).getComponentType());
        }
        // Round-1 review, fourth pass (16-10, PR #464): a REIFIED array (e.g. `TokenEntity[]`) is
        // represented by reflection as a plain Class with isArray() == true, never as a
        // GenericArrayType -- GenericArrayType only covers a generic array whose component type is
        // itself a type variable or parameterized type (e.g. T[] or List<String>[]). Without this
        // branch, `public static TokenEntity[] leaked()` or a `public static TokenEntity[]` field
        // would fall through to the final "plain Class" case below and be missed entirely.
        if (type instanceof WildcardType) {
            WildcardType wildcard = (WildcardType) type;
            for (Type upperBound : wildcard.getUpperBounds()) {
                if (referencesTokenEntity(upperBound)) {
                    return true;
                }
            }
            for (Type lowerBound : wildcard.getLowerBounds()) {
                if (referencesTokenEntity(lowerBound)) {
                    return true;
                }
            }
            return false;
        }
        // A plain (non-generic, non-array, non-wildcard) Class other than TokenEntity itself --
        // already handled by the `type == TokenEntity.class` check above.
        return false;
    }
}
