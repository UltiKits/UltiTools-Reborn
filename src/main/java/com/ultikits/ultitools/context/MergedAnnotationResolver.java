package com.ultikits.ultitools.context;

import com.ultikits.ultitools.annotations.AliasFor;
import com.ultikits.ultitools.exceptions.ContainerException;

import org.jetbrains.annotations.ApiStatus;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * General merged-annotation resolution: a recursive walk of the whole annotation tree, with
 * explicit meta-annotation attribute override via {@link AliasFor}.
 * <p>
 * {@link com.ultikits.ultitools.utils.AnnotationUtils#findAnnotation} only walks one
 * meta-annotation level and never inspects {@link AliasFor} at all, which is why
 * {@code @UltiToolsModule.scanBasePackages} works today (its call site hand-reads the
 * meta-annotation) while every other {@code @AliasFor} attribute on that same annotation is
 * declared but has no reader. Per-site special-casing was rejected (D-01) because it does not
 * remove the defect class, it only moves it to the next attribute nobody wired up yet -- this
 * class is the single general replacement.
 * <p>
 * <b>Deliberately cacheless.</b> Phase 1 (D-35/D-38) forbids a {@code static Class}-keyed cache
 * anywhere in this codebase: it would pin every module's {@code Class} objects for the lifetime
 * of the JVM and defeat the plugin class loader's release on module unload (see
 * {@link com.ultikits.ultitools.aop.AnnotationLookupCache}'s class javadoc for the full
 * reasoning). The walk this class performs runs once per class at scan/load time, not on a
 * per-invocation hot path, so a v1 without a cache is a deliberate choice, not an oversight. Any
 * future cache added here must be instance-scoped and owned by the container/scanner instance
 * that uses it -- never a {@code static} field on this class.
 * <p>
 * This type is {@link ApiStatus.Internal @ApiStatus.Internal}: module authors need
 * {@code @AliasFor} to work, not to call this resolver directly (D-04).
 *
 * @since 6.3.0
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // annotation-attribute reflection, see invokeAttribute
@ApiStatus.Internal
public final class MergedAnnotationResolver {

    private static final String JAVA_LANG_ANNOTATION_PACKAGE = "java.lang.annotation";

    private MergedAnnotationResolver() {
    }

    /**
     * Find an annotation on a class, or on any of its (meta-)annotations, or on its superclass
     * hierarchy -- generalizing {@code AnnotationUtils.findAnnotation} from one meta-annotation
     * level to the whole annotation tree, and merging in any {@code @AliasFor} override found
     * along the way.
     *
     * @param clazz          the class to search
     * @param annotationType the annotation type to find
     * @param <A>            the annotation type
     * @return the merged annotation if found, or {@code null}
     */
    public static <A extends Annotation> A find(Class<?> clazz, Class<A> annotationType) {
        if (clazz == null || annotationType == null) {
            return null;
        }
        return findOnClassHierarchy(clazz, annotationType, new HashSet<>());
    }

    /**
     * Whether {@code annotationType} is present anywhere on {@code clazz}'s annotation tree or
     * superclass hierarchy.
     *
     * @param clazz          the class to search
     * @param annotationType the annotation type to look for
     * @return {@code true} if present
     */
    public static boolean isPresent(Class<?> clazz, Class<? extends Annotation> annotationType) {
        return find(clazz, annotationType) != null;
    }

    /**
     * Validate that every {@link AliasFor}-annotated attribute declared on {@code annotationType}
     * satisfies Spring's documented Implementation Requirements for an explicit meta-annotation
     * alias, throwing a {@link ContainerException} naming the annotation and the offending
     * attribute when it does not (D-02).
     * <p>
     * The structural checks land in this class's Task 2 commit; this stub exists so every caller
     * of {@link #find} is already wired against the real method signature before the checks
     * themselves are filled in.
     *
     * @param annotationType the annotation type to validate
     */
    public static void validateAliases(Class<? extends Annotation> annotationType) {
        if (annotationType == null) {
            return;
        }
        Map<String, AliasDeclaration> withinSelfDeclarations = new LinkedHashMap<>();
        for (Method method : annotationType.getDeclaredMethods()) {
            AliasFor aliasFor = method.getAnnotation(AliasFor.class);
            if (aliasFor == null) {
                continue;
            }
            Class<? extends Annotation> targetType =
                    aliasFor.annotation() == Annotation.class ? annotationType : aliasFor.annotation();
            String targetAttribute = resolveAttributeName(aliasFor, method);
            boolean withinSelf = targetType.equals(annotationType);

            // Requirement 1: annotation() must reference a type that is meta-present on the
            // declaring annotation (a within-self alias trivially satisfies this).
            if (!withinSelf && !isMetaPresent(annotationType, targetType)) {
                throw ContainerException.malformedAliasFor(annotationType, method.getName(),
                        "target annotation " + targetType.getName() + " is not meta-present on "
                                + annotationType.getName());
            }

            // Requirement 2: the target attribute must actually exist on the target annotation.
            Method targetMethod = findAttributeMethod(targetType, targetAttribute);
            if (targetMethod == null) {
                throw ContainerException.malformedAliasFor(annotationType, method.getName(),
                        "target attribute '" + targetAttribute + "' does not exist on "
                                + targetType.getName());
            }

            // Requirement 3: aliased attributes must share the same return type.
            if (!targetMethod.getReturnType().equals(method.getReturnType())) {
                throw ContainerException.malformedAliasFor(annotationType, method.getName(),
                        "return type " + method.getReturnType().getName() + " does not match "
                                + "aliased attribute '" + targetAttribute + "' return type "
                                + targetMethod.getReturnType().getName());
            }

            if (withinSelf) {
                withinSelfDeclarations.put(method.getName(), new AliasDeclaration(method.getName(), targetAttribute));
            }
        }

        // Requirement 4: a within-annotation mutual alias must be reciprocal -- both attributes
        // must declare @AliasFor pointing at each other.
        for (AliasDeclaration declaration : withinSelfDeclarations.values()) {
            AliasDeclaration reciprocal = withinSelfDeclarations.get(declaration.targetAttribute);
            if (reciprocal == null || !declaration.attributeName.equals(reciprocal.targetAttribute)) {
                throw ContainerException.malformedAliasFor(annotationType, declaration.attributeName,
                        "mutual alias with '" + declaration.targetAttribute + "' is not reciprocal -- "
                                + "both attributes must declare @AliasFor at each other");
            }
        }
    }

    /** One attribute's declared {@code @AliasFor} target, for reciprocity checking. */
    private static final class AliasDeclaration {
        private final String attributeName;
        private final String targetAttribute;

        private AliasDeclaration(String attributeName, String targetAttribute) {
            this.attributeName = attributeName;
            this.targetAttribute = targetAttribute;
        }
    }

    /**
     * Whether {@code target} is present, directly or transitively, on {@code declaring}'s own
     * meta-annotation graph -- Spring's "meta-present" requirement for an explicit
     * {@code @AliasFor(annotation = ...)}. Cycle-guarded the same way {@link #search} is.
     */
    private static boolean isMetaPresent(Class<? extends Annotation> declaring, Class<? extends Annotation> target) {
        return isMetaPresent(declaring, target, new HashSet<>());
    }

    private static boolean isMetaPresent(Class<? extends Annotation> current, Class<? extends Annotation> target,
            Set<Class<? extends Annotation>> visited) {
        for (Annotation meta : current.getAnnotations()) {
            Class<? extends Annotation> metaType = meta.annotationType();
            if (isJavaLangAnnotation(metaType)) {
                continue;
            }
            if (metaType.equals(target)) {
                return true;
            }
            if (visited.add(metaType) && isMetaPresent(metaType, target, visited)) {
                return true;
            }
        }
        return false;
    }

    private static <A extends Annotation> A findOnClassHierarchy(Class<?> clazz, Class<A> annotationType,
            Set<Class<? extends Annotation>> visited) {
        if (clazz == null) {
            return null;
        }
        for (Annotation direct : clazz.getAnnotations()) {
            A found = search(direct, annotationType, visited, new ArrayList<>());
            if (found != null) {
                return found;
            }
        }
        return findOnClassHierarchy(clazz.getSuperclass(), annotationType, visited);
    }

    /**
     * Depth-first walk of one annotation's own meta-annotation tree. {@code visited} is shared
     * across the whole {@link #find} call (not reset per branch) so a cyclic meta-annotation
     * graph terminates instead of overflowing the stack (T-03-03) -- once an annotation type has
     * been visited anywhere in this call, it is never walked into again.
     */
    private static <A extends Annotation> A search(Annotation current, Class<A> annotationType,
            Set<Class<? extends Annotation>> visited, List<Annotation> ancestors) {
        Class<? extends Annotation> currentType = current.annotationType();
        if (isJavaLangAnnotation(currentType) || !visited.add(currentType)) {
            return null;
        }
        validateAliases(currentType);
        if (currentType.equals(annotationType)) {
            return merge(current, annotationType, ancestors);
        }
        ancestors.add(current);
        try {
            for (Annotation meta : currentType.getAnnotations()) {
                A found = search(meta, annotationType, visited, ancestors);
                if (found != null) {
                    return found;
                }
            }
            return null;
        } finally {
            ancestors.remove(ancestors.size() - 1);
        }
    }

    /**
     * Compute the merged attribute view of {@code found} (the located instance of
     * {@code annotationType}), applying any {@code @AliasFor} override declared by an annotation
     * on the path from the scanned class down to {@code found} -- including {@code found} itself,
     * for a within-annotation mutual alias.
     */
    private static <A extends Annotation> A merge(Annotation found, Class<A> annotationType,
            List<Annotation> ancestors) {
        Map<String, List<Candidate>> candidatesByAttribute = new LinkedHashMap<>();
        for (Annotation ancestor : ancestors) {
            collectOverrideCandidates(ancestor, annotationType, candidatesByAttribute);
        }
        collectOverrideCandidates(found, annotationType, candidatesByAttribute);

        A typed = annotationType.cast(found);
        if (candidatesByAttribute.isEmpty()) {
            return typed;
        }

        Map<String, Object> overrides = new LinkedHashMap<>();
        for (Map.Entry<String, List<Candidate>> entry : candidatesByAttribute.entrySet()) {
            List<Candidate> candidates = entry.getValue();
            Object agreedValue = candidates.get(0).value;
            for (Candidate candidate : candidates) {
                if (!Objects.deepEquals(candidate.value, agreedValue)) {
                    String sourceAttributes = candidates.stream()
                            .map(c -> c.sourceMethodName)
                            .distinct()
                            .reduce((a, b) -> a + "', '" + b)
                            .orElse("");
                    throw ContainerException.malformedAliasFor(annotationType, entry.getKey(),
                            "conflicting @AliasFor values for target attribute '" + entry.getKey()
                                    + "' from '" + sourceAttributes + "' on a single concrete instance");
                }
            }
            overrides.put(entry.getKey(), agreedValue);
        }
        return createProxy(typed, annotationType, overrides);
    }

    /**
     * Collect every attribute-value override {@code dInstance} contributes towards
     * {@code target}'s merged view: for each of {@code dInstance}'s own attribute methods
     * carrying {@code @AliasFor}, if the alias points at {@code target} (explicitly, or via the
     * {@code Annotation.class} default meaning "within this same annotation") and the method's
     * value on {@code dInstance} differs from that method's own declared default, it is a
     * candidate override for the aliased attribute name on {@code target}.
     * <p>
     * When {@code dInstance} IS an instance of {@code target} itself, any attribute that
     * participates in a within-annotation mutual alias also contributes its OWN declared value
     * as a second candidate for its own attribute name -- otherwise a conflicting pair
     * ({@code foo=x} aliased to {@code bar}, {@code bar=y} aliased to {@code foo}) would only
     * ever compare "the other side's value" against itself, and the conflict between {@code x}
     * and {@code y} would never surface as more than one candidate for the same attribute.
     */
    private static <A extends Annotation> void collectOverrideCandidates(Annotation dInstance, Class<A> target,
            Map<String, List<Candidate>> out) {
        Class<? extends Annotation> dType = dInstance.annotationType();
        Set<String> withinSelfParticipants = new HashSet<>();

        for (Method method : dType.getDeclaredMethods()) {
            AliasFor aliasFor = method.getAnnotation(AliasFor.class);
            if (aliasFor == null) {
                continue;
            }
            Class<? extends Annotation> aliasTargetType =
                    aliasFor.annotation() == Annotation.class ? dType : aliasFor.annotation();
            if (!aliasTargetType.equals(target)) {
                continue;
            }
            String targetAttribute = resolveAttributeName(aliasFor, method);
            addCandidateIfNonDefault(out, targetAttribute, method, dInstance, dType);
            if (dType.equals(target)) {
                withinSelfParticipants.add(targetAttribute);
                withinSelfParticipants.add(method.getName());
            }
        }

        if (dType.equals(target) && !withinSelfParticipants.isEmpty()) {
            for (Method method : dType.getDeclaredMethods()) {
                if (withinSelfParticipants.contains(method.getName())) {
                    addCandidateIfNonDefault(out, method.getName(), method, dInstance, dType);
                }
            }
        }
    }

    private static void addCandidateIfNonDefault(Map<String, List<Candidate>> out, String attributeName,
            Method sourceMethod, Annotation sourceInstance, Class<? extends Annotation> sourceType) {
        Object value = invokeAttribute(sourceMethod, sourceInstance);
        Object defaultValue = sourceMethod.getDefaultValue();
        if (!Objects.deepEquals(value, defaultValue)) {
            out.computeIfAbsent(attributeName, key -> new ArrayList<>())
                    .add(new Candidate(value, sourceType, sourceMethod.getName()));
        }
    }

    private static String resolveAttributeName(AliasFor aliasFor, Method declaringMethod) {
        if (!aliasFor.attribute().isEmpty()) {
            return aliasFor.attribute();
        }
        if (!aliasFor.value().isEmpty()) {
            return aliasFor.value();
        }
        return declaringMethod.getName();
    }

    static Method findAttributeMethod(Class<? extends Annotation> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) {
                return method;
            }
        }
        return null;
    }

    private static Object invokeAttribute(Method method, Annotation instance) {
        try {
            method.setAccessible(true);
            return method.invoke(instance);
        } catch (ReflectiveOperationException e) {
            throw new ContainerException("Failed to read annotation attribute " + method.getName()
                    + " on " + instance.annotationType().getName(), e);
        }
    }

    private static boolean isJavaLangAnnotation(Class<? extends Annotation> type) {
        Package pkg = type.getPackage();
        return pkg != null && JAVA_LANG_ANNOTATION_PACKAGE.equals(pkg.getName());
    }

    @SuppressWarnings("unchecked")
    private static <A extends Annotation> A createProxy(A original, Class<A> annotationType,
            Map<String, Object> overrides) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ((args == null || args.length == 0) && overrides.containsKey(name)) {
                return overrides.get(name);
            }
            switch (name) {
                case "annotationType":
                    return annotationType;
                case "toString":
                    return "@" + annotationType.getName() + "(merged, overrides=" + overrides + ")";
                case "hashCode":
                    return original.hashCode();
                case "equals":
                    return args != null && args.length == 1 && proxy == args[0];
                default:
                    return method.invoke(original, args);
            }
        };
        return (A) Proxy.newProxyInstance(annotationType.getClassLoader(),
                new Class<?>[] {annotationType}, handler);
    }

    /** One candidate value for a target attribute, plus where it came from (for conflict messages). */
    private static final class Candidate {
        private final Object value;
        private final Class<? extends Annotation> sourceType;
        private final String sourceMethodName;

        private Candidate(Object value, Class<? extends Annotation> sourceType, String sourceMethodName) {
            this.value = value;
            this.sourceType = sourceType;
            this.sourceMethodName = sourceMethodName;
        }
    }
}
