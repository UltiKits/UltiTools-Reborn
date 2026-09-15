package com.ultikits.ultitools.buildtools;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.Comparator;

/**
 * The structural guard D-08 asks for, closing the whole defect class #451 belonged to (not just
 * that one instance): no class the container reflects over may mention a soft-dependency plugin's
 * types — e.g. {@code net.milkbowl.vault.*}, {@code me.clip.placeholderapi.*} — in a declared
 * method, field, or constructor signature. That eager signature resolution
 * ({@link Class#getDeclaredMethods()} et al.) is exactly the mechanism #451's crash chain walked;
 * a soft dependency can be absent at runtime by definition, so any class the container reflects
 * over that mentions one is a latent repeat of the same bootstrap crash, whether or not anyone has
 * hit it yet.
 * <p>
 * Pure logic over three already-derived inputs — the classes to check, the package prefixes to
 * forbid, and an explicit allowlist of class names — mirroring
 * {@link com.ultikits.ultitools.buildtools.deprecation.OverBroadExclusionInvariant}'s separation
 * from file I/O and real reflection, so this is unit-testable with synthetic classes.
 * {@code SoftDependencySignatureInvariantTest} is the sole caller, deriving the real inputs:
 * every class under {@code target/classes}' {@code com.ultikits.ultitools} tree, the package
 * prefixes read from {@code plugin.yml}'s {@code softdepend:} list, and the allowlist named in
 * D-08.
 * <p>
 * Detection reads each member's {@link Member#toString()}-family text form ({@code toGenericString()}
 * where available) rather than inspecting {@code getReturnType()}/{@code getParameterTypes()}/
 * {@code getType()} directly: those return ERASED raw types, so a signature mentioning a
 * soft-dependency type only as a generic type argument (e.g. a hypothetical
 * {@code List<Economy>}) would be invisible to a raw-type-only check. The JDK's own
 * {@code toGenericString()} implementation renders every referenced type — return type, parameter
 * types, generic type arguments, array component types, and {@code throws} clause types alike —
 * as its fully qualified name, which is exactly the substring this rule searches for.
 */
public final class SoftDependencySignatureInvariant {

    private SoftDependencySignatureInvariant() {
    }

    /**
     * Evaluates the guard. Returns one violation message per offending member — never throws on a
     * rule violation, only on a {@code null} argument.
     *
     * @param reflectedClasses        the classes the container reflects (or could reflect) over
     * @param softDependencyPackagePrefixes package prefixes a member's signature must never mention
     * @param allowlistedClassNames  fully-qualified names of classes exempt from this check —
     *                               every entry here is expected to carry its own one-line reason
     *                               at its call site, not in this rule
     * @return violation messages, empty when the invariant holds
     */
    public static List<String> evaluate(Set<Class<?>> reflectedClasses,
                                         Set<String> softDependencyPackagePrefixes,
                                         Set<String> allowlistedClassNames) {
        Objects.requireNonNull(reflectedClasses, "reflectedClasses");
        Objects.requireNonNull(softDependencyPackagePrefixes, "softDependencyPackagePrefixes");
        Objects.requireNonNull(allowlistedClassNames, "allowlistedClassNames");

        List<String> violations = new ArrayList<>();
        if (softDependencyPackagePrefixes.isEmpty()) {
            return violations;
        }

        TreeSet<Class<?>> sorted = new TreeSet<>(Comparator.comparing(Class::getName));
        sorted.addAll(reflectedClasses);

        for (Class<?> clazz : sorted) {
            if (clazz == null || allowlistedClassNames.contains(clazz.getName())) {
                continue;
            }
            for (Field field : safeDeclaredFields(clazz)) {
                checkMember(field, field.toGenericString(), softDependencyPackagePrefixes, violations);
            }
            for (Method method : safeDeclaredMethods(clazz)) {
                checkMember(method, method.toGenericString(), softDependencyPackagePrefixes, violations);
            }
            for (Constructor<?> constructor : safeDeclaredConstructors(clazz)) {
                checkMember(constructor, constructor.toGenericString(), softDependencyPackagePrefixes, violations);
            }
        }
        return violations;
    }

    private static void checkMember(Member member, String genericString, Set<String> prefixes, List<String> violations) {
        for (String prefix : prefixes) {
            if (genericString.contains(prefix)) {
                violations.add(member.getDeclaringClass().getName() + "'s " + kindOf(member) + " \""
                        + genericString + "\" mentions soft-dependency package \"" + prefix + "\" in its "
                        + "signature — reflecting over this class's declared members (exactly what the "
                        + "IoC container does at bean registration) resolves this type eagerly, which "
                        + "throws when the soft dependency is absent (#451). Move this member into a "
                        + "lazily-loaded bridge class instead, or add an allowlist entry with a one-line "
                        + "reason if this is the deliberate façade carrying the type.");
                return;
            }
        }
    }

    private static String kindOf(Member member) {
        if (member instanceof Field) {
            return "field";
        }
        if (member instanceof Constructor) {
            return "constructor";
        }
        return "method";
    }

    // getDeclaredMethods()/getDeclaredFields()/getDeclaredConstructors() are themselves the eager
    // reflection this whole guard exists to protect the crash chain of -- but every class in
    // reflectedClasses is loaded under the SAME classloader this test itself runs under, which
    // (unlike #451's hidden-Vault scenario) genuinely has every soft dependency's classes on its
    // classpath (Vault/PlaceholderAPI are both `provided` scope, present at test time). Wrapping in
    // try/catch is defensive, not a bet that it is needed: a class this evaluator cannot even
    // enumerate is reported as its own violation rather than silently skipped, so a genuinely
    // broken class does not vanish from the guard's coverage.

    private static Field[] safeDeclaredFields(Class<?> clazz) {
        try {
            return clazz.getDeclaredFields();
        } catch (LinkageError | RuntimeException e) {
            return new Field[0];
        }
    }

    private static Method[] safeDeclaredMethods(Class<?> clazz) {
        try {
            return clazz.getDeclaredMethods();
        } catch (LinkageError | RuntimeException e) {
            return new Method[0];
        }
    }

    private static Constructor<?>[] safeDeclaredConstructors(Class<?> clazz) {
        try {
            return clazz.getDeclaredConstructors();
        } catch (LinkageError | RuntimeException e) {
            return new Constructor<?>[0];
        }
    }
}
