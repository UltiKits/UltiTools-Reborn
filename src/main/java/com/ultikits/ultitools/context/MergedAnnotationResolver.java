package com.ultikits.ultitools.context;

import com.ultikits.ultitools.annotations.AliasFor;
import com.ultikits.ultitools.exceptions.ContainerException;

import org.jetbrains.annotations.ApiStatus;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * General merged-annotation resolution: a search of the whole annotation tree, with explicit
 * meta-annotation attribute override via {@link AliasFor}.
 * <p>
 * <b>Nearest selection (decision P-10).</b> The occurrence of the requested annotation nearest
 * the class is the one resolved: a directly declared annotation (distance 0) wins over any
 * meta-present one, then meta-annotations are searched breadth-first by distance with declaration
 * order breaking ties, and a superclass is consulted only when the class's own annotation trees
 * hold no occurrence. This follows Spring's {@code @ComponentScan} javadoc: "Locally declared
 * {@code @ComponentScan} annotations always take precedence over and effectively hide
 * {@code @ComponentScan} meta-annotations, which allows explicit local configuration to override
 * configuration that is meta-present." A module that declares both {@code @UltiToolsModule} and a
 * direct {@code @ComponentScan} (or {@code @EnableAutoRegister}) therefore gets the direct one,
 * whatever order the two are written in (C45).
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
     * along the path to the selected occurrence.
     * <p>
     * The occurrence nearest the class is selected (see the class javadoc): a directly declared
     * {@code annotationType} hides every meta-present one, and the class's own annotations are
     * exhausted before its superclass is searched.
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
     * {@code @AliasFor(annotation = ...)}. Cycle-guarded the same way {@link #findNearest} is.
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

    /**
     * Nearest selection over the class hierarchy (decision P-10): the class's own annotation
     * trees are searched completely before its superclass is consulted. {@code visited} is shared
     * across the whole {@link #find} call so each annotation type is entered at most once; a type
     * already entered on a subclass cannot lead to the target on the superclass either, because
     * its whole subtree was already searched.
     */
    private static <A extends Annotation> A findOnClassHierarchy(Class<?> clazz, Class<A> annotationType,
            Set<Class<? extends Annotation>> visited) {
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            Occurrence occurrence = findNearest(current, annotationType, visited);
            if (occurrence != null) {
                return merge(occurrence.annotation, annotationType, occurrence.ancestors());
            }
        }
        return null;
    }

    /**
     * Breadth-first search of one class's annotation graph for the occurrence of
     * {@code annotationType} nearest the class: its directly declared annotations are distance 0,
     * their meta-annotations distance 1, and so on; at equal distance, declaration order breaks
     * the tie. A directly declared target therefore always wins over a meta-present one, whatever
     * order the annotations are written in -- Spring's rule that a locally declared annotation
     * "always take[s] precedence over and effectively hide[s]" a meta-present one
     * ({@code @ComponentScan} javadoc).
     * <p>
     * {@code visited} is shared across the whole {@link #find} call (not reset per branch) so a
     * cyclic meta-annotation graph terminates (T-03-03): once an annotation type has been
     * enqueued anywhere in this call, it is never enqueued again. Every annotation type the
     * search enters is passed to {@link #validateAliases}, as the depth-first walk did.
     *
     * @return the nearest occurrence with its parent chain, or {@code null} if this class's own
     *         annotation graph holds none
     */
    private static Occurrence findNearest(Class<?> clazz, Class<? extends Annotation> annotationType,
            Set<Class<? extends Annotation>> visited) {
        Deque<Occurrence> queue = new ArrayDeque<>();
        for (Annotation direct : clazz.getAnnotations()) {
            enqueue(queue, direct, null, visited);
        }
        while (!queue.isEmpty()) {
            Occurrence occurrence = queue.removeFirst();
            Class<? extends Annotation> type = occurrence.annotation.annotationType();
            validateAliases(type);
            if (type.equals(annotationType)) {
                return occurrence;
            }
            for (Annotation meta : type.getAnnotations()) {
                enqueue(queue, meta, occurrence, visited);
            }
        }
        return null;
    }

    private static void enqueue(Deque<Occurrence> queue, Annotation annotation, Occurrence parent,
            Set<Class<? extends Annotation>> visited) {
        Class<? extends Annotation> type = annotation.annotationType();
        if (isJavaLangAnnotation(type) || !visited.add(type)) {
            return;
        }
        queue.addLast(new Occurrence(annotation, parent));
    }

    /** One annotation instance reached by {@link #findNearest}, linked to the one it was found on. */
    private static final class Occurrence {
        private final Annotation annotation;
        private final Occurrence parent;

        private Occurrence(Annotation annotation, Occurrence parent) {
            this.annotation = annotation;
            this.parent = parent;
        }

        /**
         * The annotations on the path from the class down to (excluding) this occurrence,
         * outermost (declared on the class) first.
         */
        private List<Annotation> ancestors() {
            List<Annotation> path = new ArrayList<>();
            for (Occurrence current = parent; current != null; current = current.parent) {
                path.add(0, current.annotation);
            }
            return path;
        }
    }

    /**
     * Compute the merged attribute view of {@code found} (the located instance of
     * {@code annotationType}) by walking the selected path top-down (decision P-10): from the
     * annotation declared on the class, through every intermediate meta-annotation, down to
     * {@code found}. Each level's candidate values are computed by
     * {@link #collectOverrideCandidates} from the levels above it, so a value set on an outer
     * annotation travels through every intermediate {@code @AliasFor} to the final target (C46):
     * an attribute aliasing {@code @UltiToolsModule.scanBasePackages} reaches
     * {@code @ComponentScan.basePackages}, because {@code scanBasePackages} itself aliases it.
     * <p>
     * Every candidate records the annotation instance that declared it and that instance's
     * distance from the class. For each attribute of {@code found} the rule is (C47):
     * <ul>
     * <li>if one annotation instance supplies two different values -- through a mutual alias, or
     * through two routes that reach the same final attribute -- the declaration contradicts
     * itself and is refused with a {@link ContainerException} naming that instance's annotation;</li>
     * <li>otherwise the value declared by the instance nearest the class wins. Values from
     * different instances never conflict: an outer override beats a value an inner
     * meta-annotation declares.</li>
     * </ul>
     * This is Spring's {@code @AliasFor} contract: an explicit meta-annotation attribute override
     * wins over the meta-annotation's own value, the lower level (closer to the annotated element)
     * wins, and only an attribute and its alias declared with different values in one annotation
     * is an error.
     */
    private static <A extends Annotation> A merge(Annotation found, Class<A> annotationType,
            List<Annotation> ancestors) {
        List<Annotation> path = new ArrayList<>(ancestors);
        path.add(found);
        List<Map<String, List<Candidate>>> levels = new ArrayList<>(path.size());
        for (int level = 0; level < path.size(); level++) {
            levels.add(collectOverrideCandidates(path, level, levels));
        }
        Map<String, List<Candidate>> candidatesByAttribute = levels.get(levels.size() - 1);

        A typed = annotationType.cast(found);
        if (candidatesByAttribute.isEmpty()) {
            return typed;
        }

        Map<String, Object> overrides = new LinkedHashMap<>();
        for (Map.Entry<String, List<Candidate>> entry : candidatesByAttribute.entrySet()) {
            Object value = resolveNearest(annotationType, entry.getKey(), entry.getValue());
            if (value != null) { // never null for a non-empty list; an attribute is never overridden to null
                overrides.put(entry.getKey(), value);
            }
        }
        return createProxy(typed, annotationType, overrides);
    }

    /**
     * Apply the per-instance conflict rule and nearest-wins rule (see {@link #merge}) to one
     * attribute's candidates. Each level of the path is exactly one annotation instance, so the
     * candidates' distance identifies their declaring instance.
     */
    private static Object resolveNearest(Class<? extends Annotation> annotationType, String attribute,
            List<Candidate> candidates) {
        Candidate nearest = null;
        for (Candidate candidate : candidates) {
            for (Candidate other : candidates) {
                if (other.distance == candidate.distance && !Objects.deepEquals(other.value, candidate.value)) {
                    throw selfContradiction(annotationType, attribute, candidate, candidates);
                }
            }
            if (nearest == null || candidate.distance < nearest.distance) {
                nearest = candidate;
            }
        }
        return nearest == null ? null : nearest.value;
    }

    private static ContainerException selfContradiction(Class<? extends Annotation> annotationType,
            String attribute, Candidate conflicting, List<Candidate> candidates) {
        String sourceAttributes = candidates.stream()
                .filter(c -> c.distance == conflicting.distance)
                .map(c -> c.sourceMethodName)
                .distinct()
                .reduce((a, b) -> a + "', '" + b)
                .orElse("");
        return ContainerException.malformedAliasFor(conflicting.origin.annotationType(),
                conflicting.sourceMethodName,
                "conflicting @AliasFor values for target attribute '" + attribute + "' of "
                        + annotationType.getName() + " from '" + sourceAttributes
                        + "' on a single concrete instance");
    }

    /**
     * Collect the candidate values for every attribute of the annotation at {@code path[level]},
     * given the already-computed candidates of every level above it ({@code outerLevels}, indexed
     * by distance from the class):
     * <ol>
     * <li><b>Forwarded.</b> For each outer level, each attribute declaring
     * {@code @AliasFor(annotation = <this level's type>)} forwards all of its own candidates to
     * the aliased attribute here. A forwarded candidate keeps the instance that originally
     * declared it -- an intermediate annotation only relays it.</li>
     * <li><b>Own.</b> Each of this annotation's attributes that declares {@code @AliasFor} and
     * whose value differs from its declared default is a candidate for itself, declared by this
     * instance. (Attributes taking part in no alias never need a candidate: the merged view falls
     * back to the found instance for them.)</li>
     * <li><b>Mirrored.</b> Two attributes of this annotation that alias each other (a
     * within-annotation mutual alias) share one candidate list, so {@code foo=x} with
     * {@code bar=y} surfaces as two different values for the same attribute.</li>
     * </ol>
     * Candidates are only collected here, never reconciled: a list may hold values from several
     * instances. {@link #merge} applies the rule once, at the found annotation -- two different
     * values from one instance are refused, otherwise the instance nearest the class wins --
     * which is Spring's {@code @AliasFor} contract (explicit meta-annotation overrides,
     * transitive aliases, lower level wins, same-annotation contradiction is an error).
     */
    private static Map<String, List<Candidate>> collectOverrideCandidates(List<Annotation> path, int level,
            List<Map<String, List<Candidate>>> outerLevels) {
        Annotation instance = path.get(level);
        Class<? extends Annotation> type = instance.annotationType();
        Map<String, List<Candidate>> out = new LinkedHashMap<>();

        for (int outer = 0; outer < level; outer++) {
            Map<String, List<Candidate>> outerCandidates = outerLevels.get(outer);
            for (Method method : path.get(outer).annotationType().getDeclaredMethods()) {
                AliasFor aliasFor = method.getAnnotation(AliasFor.class);
                List<Candidate> forwarded = outerCandidates.get(method.getName());
                if (aliasFor == null || forwarded == null || !type.equals(aliasFor.annotation())) {
                    continue;
                }
                addAllDistinct(out.computeIfAbsent(resolveAttributeName(aliasFor, method), key -> new ArrayList<>()),
                        forwarded);
            }
        }

        List<String[]> mirrors = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            AliasFor aliasFor = method.getAnnotation(AliasFor.class);
            if (aliasFor == null) {
                continue;
            }
            addCandidateIfNonDefault(out, method, instance, level);
            Class<? extends Annotation> aliasTargetType =
                    aliasFor.annotation() == Annotation.class ? type : aliasFor.annotation();
            if (aliasTargetType.equals(type)) {
                mirrors.add(new String[] {method.getName(), resolveAttributeName(aliasFor, method)});
            }
        }

        for (String[] mirror : mirrors) {
            List<Candidate> shared = new ArrayList<>();
            addAllDistinct(shared, out.get(mirror[0]));
            addAllDistinct(shared, out.get(mirror[1]));
            if (!shared.isEmpty()) {
                out.put(mirror[0], shared);
                out.put(mirror[1], new ArrayList<>(shared));
            }
        }
        return out;
    }

    private static void addAllDistinct(List<Candidate> target, List<Candidate> source) {
        if (source == null) {
            return;
        }
        for (Candidate candidate : source) {
            if (!target.contains(candidate)) {
                target.add(candidate);
            }
        }
    }

    private static void addCandidateIfNonDefault(Map<String, List<Candidate>> out, Method sourceMethod,
            Annotation sourceInstance, int distance) {
        Object value = invokeAttribute(sourceMethod, sourceInstance);
        Object defaultValue = sourceMethod.getDefaultValue();
        if (!Objects.deepEquals(value, defaultValue)) {
            out.computeIfAbsent(sourceMethod.getName(), key -> new ArrayList<>())
                    .add(new Candidate(value, sourceInstance, distance, sourceMethod.getName()));
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

    /**
     * One candidate value for an attribute, with the annotation instance that declared it, that
     * instance's distance from the class (0 = declared on the class), and the declaring attribute's
     * name (for conflict messages). A value forwarded through intermediate aliases keeps its
     * original declaring instance, distance and attribute name.
     */
    private static final class Candidate {
        private final Object value;
        private final Annotation origin;
        private final int distance;
        private final String sourceMethodName;

        private Candidate(Object value, Annotation origin, int distance, String sourceMethodName) {
            this.value = value;
            this.origin = origin;
            this.distance = distance;
            this.sourceMethodName = sourceMethodName;
        }
    }
}
