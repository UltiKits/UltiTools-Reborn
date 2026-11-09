package com.ultikits.ultitools.buildtools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ultikits.ultitools.buildtools.fixtures.CredentialSurfaceOffenderFixture;
import com.ultikits.ultitools.buildtools.fixtures.CredentialSurfaceOffenderFixture.StandInCredential;
import com.ultikits.ultitools.utils.ServerCredentialSurfaceOffenderFixture;

/**
 * Pins {@link CredentialStaticSurfaceInvariant}'s D-18 rule: no {@code public static} method in the
 * {@code utils} package may accept or return a {@link StandInCredential} or a generation. Synthetic cases
 * (nested classes below) cover the discrimination logic directly against hand-built {@link Method}
 * arrays; {@link #scanMechanismCatchesAFixtureOffender()} then proves the package-<em>scanning</em>
 * mechanism itself is non-vacuous -- pointed at a fixture package holding a permanent, deliberate
 * offender, before the final test trusts that same mechanism against the real {@code utils} package.
 * The real-package case runs last by design, per this class's own name in 16-09-PLAN.md's task list.
 */
@DisplayName("CredentialStaticSurfaceInvariant tests (D-18)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CredentialStaticSurfaceInvariantTest {

    @Test
    @DisplayName("a public static method returning StandInCredential produces exactly one violation naming that method")
    void publicStaticMethodReturningStandInCredentialReportsOneViolationNamingIt() {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(
                methodsOf(TokenReturningOffender.class), StandInCredential.class);

        assertThat(violations)
                .hasSize(1)
                .anySatisfy(v -> {
                    assertThat(v).contains("offend");
                    assertThat(v).contains(StandInCredential.class.getSimpleName());
                });
    }

    @Test
    @DisplayName("round-15 review: a public static method returning a concrete SUBCLASS of StandInCredential "
            + "produces exactly one violation naming that method -- assignability, not exact equality")
    void publicStaticMethodReturningStandInCredentialSubclassReportsOneViolation() {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(
                methodsOf(TokenSubclassReturningOffender.class), StandInCredential.class);

        assertThat(violations)
                .as("StandInCredential is public and not final, so a concrete subclass is a real, "
                        + "constructible signature that reifies as SessionToken.class, never "
                        + "StandInCredential.class -- an exact-equality check would miss it entirely")
                .hasSize(1)
                .anySatisfy(v -> {
                    assertThat(v).contains("offend");
                    assertThat(v).contains(StandInCredential.class.getSimpleName());
                });
    }

    @Test
    @DisplayName("round-15 review: a public static method ACCEPTING a concrete SUBCLASS of "
            + "StandInCredential produces exactly one violation naming that method")
    void publicStaticMethodAcceptingStandInCredentialSubclassReportsOneViolation() {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(
                methodsOf(TokenSubclassAcceptingOffender.class), StandInCredential.class);

        assertThat(violations)
                .hasSize(1)
                .anySatisfy(v -> {
                    assertThat(v).contains("offend");
                    assertThat(v).contains(StandInCredential.class.getSimpleName());
                });
    }

    @Test
    @DisplayName("a package-private static method returning StandInCredential produces no violation -- the rule is about the public surface")
    void packagePrivateStaticMethodReturningStandInCredentialReportsNone() {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(
                methodsOf(PackagePrivateOffender.class), StandInCredential.class);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("a public static method whose name says 'generation' and takes a long parameter produces one violation")
    void publicStaticMethodAcceptingGenerationShapedParameterReportsOneViolation() {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(
                methodsOf(GenerationParameterOffender.class), StandInCredential.class);

        assertThat(violations)
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains("advanceGeneration"));
    }

    @Test
    @DisplayName("a public static method with a bare long parameter but an unrelated name produces no violation -- the accepted, named blind spot")
    void publicStaticMethodWithUnrelatedNamedLongParameterReportsNone() {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(
                methodsOf(UnrelatedLongParameterNonOffender.class), StandInCredential.class);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("a public instance (non-static) method returning StandInCredential produces no violation -- only the static surface is D-18's concern")
    void publicInstanceMethodReturningStandInCredentialReportsNone() {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(
                methodsOf(InstanceMethodNonOffender.class), StandInCredential.class);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("the scan mechanism is non-vacuous: pointed at a fixture package with a permanent, deliberate offender, it finds it every run")
    void scanMechanismCatchesAFixtureOffender() throws IOException, ClassNotFoundException {
        List<Method> fixtureMethods = scanPublicStaticMethodsOfPackage(
                testClassesRoot(), CredentialSurfaceOffenderFixture.class.getPackage().getName());

        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(fixtureMethods, StandInCredential.class);

        assertThat(violations)
                .as("CredentialSurfaceOffenderFixture#offendByReturningToken() must be found by the "
                        + "same scan this test uses on the real utils package below -- a scan that "
                        + "reports zero because it looked at nothing is the same failure mode as the "
                        + "stale build-output scan this phase separately fixes")
                .isNotEmpty()
                .anySatisfy(v -> assertThat(v).contains("offendByReturningToken"));
    }

    @Test
    @DisplayName("WR-03: the scan mechanism also catches the fixture's erased-generic offenders (Optional<StandInCredential> return, Consumer<StandInCredential> parameter)")
    void scanMechanismCatchesTheFixturesGenericOffenders() throws IOException, ClassNotFoundException {
        List<Method> fixtureMethods = scanPublicStaticMethodsOfPackage(
                testClassesRoot(), CredentialSurfaceOffenderFixture.class.getPackage().getName());

        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(fixtureMethods, StandInCredential.class);

        assertThat(violations)
                .as("Optional<StandInCredential> erases to Optional.class -- the pre-WR-03 rule would have "
                        + "missed this entirely")
                .anySatisfy(v -> assertThat(v).contains("offendByReturningOptionalOfToken"));
        assertThat(violations)
                .as("Consumer<StandInCredential> erases to Consumer.class -- same gap, on a parameter")
                .anySatisfy(v -> assertThat(v).contains("offendByAcceptingConsumerOfToken"));
    }

    @Test
    @DisplayName("Round 1 外部评审（第四轮）：StandInCredential[] 这种具体数组类型必须被抓到，不能因为它是 Class 而不是 GenericArrayType 就漏掉")
    void publicStaticMethodReturningStandInCredentialArrayReportsOneViolation() {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(
                methodsOf(ArrayReturningOffender.class), StandInCredential.class);

        assertThat(violations)
                .as("反射把具体数组 StandInCredential[] 表示成 isArray()==true 的 Class，从不是 " +
                        "GenericArrayType——只处理 GenericArrayType 的旧版本会漏掉这一种")
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains("leaked"));
    }

    @Test
    @DisplayName("Round 1 外部评审（第五轮）：带 StandInCredential 上界的类型变量必须被抓到，不能因为它是 TypeVariable 而不是 WildcardType 就漏掉")
    void publicStaticMethodReturningBoundedTypeVariableReportsOneViolation() {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(
                methodsOf(BoundedTypeVariableOffender.class), StandInCredential.class);

        assertThat(violations)
                .as("<T extends Iterable<StandInCredential>> 这种上界通过反射拿到的是 TypeVariable，" +
                        "不是 WildcardType——只处理 WildcardType 的旧版本会漏掉这一种")
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains("leaked"));
    }

    @Test
    @DisplayName("Round 6 external review: a self-referential bounded type variable must not StackOverflowError and must report no violation")
    void selfReferentialBoundedTypeVariableDoesNotStackOverflowAndReportsNoViolation() {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(
                methodsOf(SelfReferentialBoundNonOffender.class), StandInCredential.class);

        assertThat(violations)
                .as("<T extends Comparable<T>> recurses into its own bound via Comparable<T>'s sole "
                        + "type argument, T itself -- without cycle detection this call never "
                        + "returns, and nothing here references StandInCredential anyway")
                .isEmpty();
    }

    @Test
    @DisplayName("Round 6 external review: StandInCredential referenced only through a parameterized owner type must still be caught")
    void ownerTypeLeakReportsOneViolation() {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(
                methodsOf(OwnerTypeLeakOffender.class), StandInCredential.class);

        assertThat(violations)
                .as("GenericOwner<StandInCredential>.Inner records StandInCredential only in "
                        + "ParameterizedType#getOwnerType() -- Inner's own raw type and actual type "
                        + "arguments, both already checked, say nothing about it")
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains("leaked"));
    }

    @Test
    @DisplayName("Defect-class sweep: a GenericArrayType (List<StandInCredential>[]) must be caught, distinct from the reified-array Class case")
    void genericArrayTypeReportsOneViolation() {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(
                methodsOf(GenericArrayOffender.class), StandInCredential.class);

        assertThat(violations)
                .as("List<StandInCredential>[] reifies as GenericArrayType, not as a plain Class with "
                        + "isArray()==true -- ArrayReturningOffender's reified StandInCredential[] does not "
                        + "exercise this branch at all")
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains("leaked"));
    }

    @Test
    @DisplayName("Defect-class sweep: a WildcardType upper bound (List<? extends StandInCredential>) must be caught")
    void wildcardUpperBoundReportsOneViolation() throws NoSuchMethodException {
        Method method = WildcardUpperBoundOffender.class.getMethod("leaked", List.class);

        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(java.util.Collections.singletonList(method), StandInCredential.class);

        assertThat(violations)
                .as("List<? extends StandInCredential> reifies as a WildcardType whose getUpperBounds() "
                        + "names StandInCredential -- no existing fixture drove this specific loop")
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains("leaked"));
    }

    @Test
    @DisplayName("Defect-class sweep: a WildcardType lower bound (List<? super StandInCredential>) must be caught")
    void wildcardLowerBoundReportsOneViolation() throws NoSuchMethodException {
        Method method = WildcardLowerBoundOffender.class.getMethod("leaked", List.class);

        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(java.util.Collections.singletonList(method), StandInCredential.class);

        assertThat(violations)
                .as("List<? super StandInCredential> reifies as a WildcardType whose getLowerBounds() "
                        + "names StandInCredential while getUpperBounds() names only Object -- only the "
                        + "lower-bounds loop can catch this shape")
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains("leaked"));
    }

    @Test
    @DisplayName("WR-03: the field scan mechanism is non-vacuous, pointed at the fixture's permanent offending field")
    void fieldScanMechanismCatchesAFixtureOffender() throws IOException, ClassNotFoundException {
        List<Field> fixtureFields = scanPublicStaticFieldsOfPackage(
                testClassesRoot(), CredentialSurfaceOffenderFixture.class.getPackage().getName());

        List<String> violations = CredentialStaticSurfaceInvariant.evaluateFields(fixtureFields, StandInCredential.class);

        assertThat(violations)
                .as("CredentialSurfaceOffenderFixture#offendingLeakedField must be found -- fields "
                        + "were entirely outside the pre-WR-03 scope of this rule")
                .isNotEmpty()
                .anySatisfy(v -> assertThat(v).contains("offendingLeakedField"));
    }

    @Test
    @DisplayName("a public static field returning StandInCredential via a synthetic fixture produces exactly one violation naming it")
    void publicStaticFieldTypedAsStandInCredentialReportsOneViolationNamingIt() throws NoSuchFieldException {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluateFields(
                fieldsOf(TokenFieldOffender.class), StandInCredential.class);

        assertThat(violations)
                .hasSize(1)
                .anySatisfy(v -> {
                    assertThat(v).contains("offendingField");
                    assertThat(v).contains(StandInCredential.class.getSimpleName());
                });
    }

    @Test
    @DisplayName("a public static Optional<StandInCredential> field produces one violation via the generic-type check")
    void publicStaticFieldWithGenericStandInCredentialTypeReportsOneViolation() throws NoSuchFieldException {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluateFields(
                fieldsOf(GenericTokenFieldOffender.class), StandInCredential.class);

        assertThat(violations)
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains("offendingGenericField"));
    }

    @Test
    @DisplayName("a package-private static field typed as StandInCredential produces no violation -- the rule is about the public surface")
    void packagePrivateStaticFieldTypedAsStandInCredentialReportsNone() throws NoSuchFieldException {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluateFields(
                fieldsOf(PackagePrivateFieldOffender.class), StandInCredential.class);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("run against the real utils package (scanned, not hand-listed), the rule reports zero violations")
    void realUtilsPackageReportsZeroViolations() throws IOException, ClassNotFoundException {
        // Files.list + Class.forName below -- the input set is DERIVED by scanning the compiled
        // package, not enumerated by hand, so a class added to com.ultikits.ultitools.utils later
        // is covered here without anyone having to remember to edit this test.
        List<Method> realMethods = scanPublicStaticMethodsOfPackage(mainClassesRoot(), "com.ultikits.ultitools.utils");

        assertThat(realMethods)
                .as("the scan must actually find something in the real utils package, or the empty "
                        + "result below would be vacuous rather than earned")
                .isNotEmpty();

        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(realMethods);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("WR-03: run the field check against the real utils package (scanned, not hand-listed), it reports zero violations")
    void realUtilsPackageReportsZeroFieldViolations() throws IOException, ClassNotFoundException {
        List<Field> realFields = scanPublicStaticFieldsOfPackage(mainClassesRoot(), "com.ultikits.ultitools.utils");

        List<String> violations = CredentialStaticSurfaceInvariant.evaluateFields(realFields);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("the real rule guards the protocol v2 server credential, which must still exist")
    void realRuleGuardsTheServerCredentialType() {
        assertThat(CredentialStaticSurfaceInvariant.credentialType().getName())
                .as("TokenEntity was removed in 6.4.0; the rule now guards the only credential type "
                        + "left -- if this type is renamed or removed the rule must be re-pointed")
                .isEqualTo("com.ultikits.ultitools.utils.ServerCredential");
    }

    @Test
    @DisplayName("the real rule is non-vacuous: utils-package offenders exposing ServerCredential are each caught")
    void realRuleCatchesServerCredentialOffenders() {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(
                methodsOf(ServerCredentialSurfaceOffenderFixture.class));

        assertThat(violations)
                .as("one violation per offending method: direct return, Optional<ServerCredential> "
                        + "return and Consumer<ServerCredential> parameter")
                .hasSize(3)
                .anySatisfy(v -> assertThat(v).contains("leakCredential"))
                .anySatisfy(v -> assertThat(v).contains("leakOptionalCredential"))
                .anySatisfy(v -> assertThat(v).contains("acceptCredentialConsumer"));

        List<String> fieldViolations = CredentialStaticSurfaceInvariant.evaluateFields(
                fieldsOf(ServerCredentialSurfaceOffenderFixture.class));

        assertThat(fieldViolations)
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains("leakedCredentialField"));
    }

    // -------------------------------------------------------------------------
    // Synthetic fixtures -- one offending or non-offending shape each
    // -------------------------------------------------------------------------

    static class TokenReturningOffender {
        public static StandInCredential offend() {
            return null;
        }
    }

    static class PackagePrivateOffender {
        static StandInCredential notPublic() {
            return null;
        }
    }

    static class GenerationParameterOffender {
        public static void advanceGeneration(long generation) {
            // Never actually called -- reflected over only, to exercise the name-based
            // generation-shaped check against a synthetic offender.
        }
    }

    /**
     * round-15 review (16-10 gap-closure addendum, PR #464): a concrete subclass of
     * {@link StandInCredential} -- since {@link StandInCredential} is {@code public} and not {@code final}, this
     * is a real, constructible type, not a hypothetical one.
     */
    static class SessionToken extends StandInCredential {
    }

    static class TokenSubclassReturningOffender {
        public static SessionToken offend() {
            return null;
        }
    }

    static class TokenSubclassAcceptingOffender {
        public static void offend(SessionToken token) {
            // Never actually called -- reflected over only.
        }
    }

    static class ArrayReturningOffender {
        // Round-1 review, fourth pass (16-10, PR #464): a REIFIED array (StandInCredential[]) is a plain
        // Class with isArray() == true, never a GenericArrayType -- the pre-fix code missed this.
        public static StandInCredential[] leaked() {
            return null;
        }
    }

    static class BoundedTypeVariableOffender {
        // Round-1 review, fifth pass (16-10, PR #464): a bounded type variable is reified as a
        // TypeVariable, not a WildcardType -- the pre-fix code's WildcardType branch never fires.
        public static <T extends Iterable<StandInCredential>> T leaked() {
            return null;
        }
    }

    static class SelfReferentialBoundNonOffender {
        // Round-6 external review finding (16-10, PR #464): <T extends Comparable<T>> recurses
        // from the TypeVariable T into its bound Comparable<T> (a ParameterizedType), whose sole
        // type argument is T itself -- the same TypeVariable -- so the pre-fix recursion never
        // terminated. Nothing here references StandInCredential; this exists only to prove the scan
        // survives a self-referential bound instead of exhausting the stack.
        public static <T extends Comparable<T>> T maxOf(T left, T right) {
            return null;
        }
    }

    static class OwnerTypeLeakOffender {
        static class GenericOwner<T> {
            class Inner {
            }
        }

        // Round-6 external review finding (16-10, PR #464): a member type used against a
        // parameterized enclosing type -- GenericOwner<StandInCredential>.Inner -- records StandInCredential
        // ONLY in the reflected ParameterizedType's getOwnerType(); Inner's own raw type and
        // (empty) actual type arguments, both already checked, say nothing about it.
        public static GenericOwner<StandInCredential>.Inner leaked() {
            return null;
        }
    }

    static class GenericArrayOffender {
        // Defect-class sweep (16-10, PR #464): ArrayReturningOffender above proves the REIFIED
        // array branch (StandInCredential[], a plain Class with isArray()==true); this proves the
        // separate GenericArrayType branch, which only fires when the array's component type is
        // itself generic (a type variable or parameterized type) -- e.g. List<StandInCredential>[],
        // confirmed via a standalone reflection probe to reify as GenericArrayType with
        // getGenericComponentType() == the ParameterizedType List<StandInCredential>. Never both
        // branches at once: a single array return type is either a plain reified Class or a
        // GenericArrayType, never both, so this is a genuinely distinct shape from
        // ArrayReturningOffender, not a duplicate of it.
        public static List<StandInCredential>[] leaked() {
            return null;
        }
    }

    static class WildcardUpperBoundOffender {
        // Defect-class sweep (16-10, PR #464): the WildcardType branch's getUpperBounds() loop had
        // no dedicated offender proving it non-vacuous -- BoundedTypeVariableOffender above proves
        // the sibling TypeVariable branch, not this one. List<? extends StandInCredential> reifies as a
        // ParameterizedType whose sole type argument is a WildcardType with getUpperBounds() ==
        // [StandInCredential.class] and empty getLowerBounds() (confirmed via a standalone reflection
        // probe).
        public static void leaked(List<? extends StandInCredential> tokens) {
            // no-op -- reflected over, never called
        }
    }

    static class WildcardLowerBoundOffender {
        // Defect-class sweep (16-10, PR #464): the WildcardType branch's getLowerBounds() loop had
        // no dedicated offender either -- List<? extends StandInCredential> above only exercises
        // getUpperBounds(). List<? super StandInCredential> reifies as a ParameterizedType whose sole
        // type argument is a WildcardType with getLowerBounds() == [StandInCredential.class] (and
        // getUpperBounds() == [Object.class], confirmed via the same reflection probe) -- so this
        // is the one shape that can ONLY be caught by the lower-bounds loop, not the upper one.
        public static void leaked(List<? super StandInCredential> sink) {
            // no-op -- reflected over, never called
        }
    }

    static class UnrelatedLongParameterNonOffender {
        // Mirrors the real, measured false positives this rule must NOT catch:
        // ApiRateLimiter#isAllowed(String, long) / #getRemainingCooldown(String, long) and
        // SecurityPolicy#isSafeFileStructure(long, int) -- a bare long with a name that says
        // nothing about generations.
        public static boolean isAllowedWithinCooldown(String key, long cooldownMs) {
            return true;
        }
    }

    static class InstanceMethodNonOffender {
        public StandInCredential notStatic() {
            return null;
        }
    }

    static class TokenFieldOffender {
        public static StandInCredential offendingField = null;
    }

    static class GenericTokenFieldOffender {
        public static Optional<StandInCredential> offendingGenericField = Optional.empty();
    }

    static class PackagePrivateFieldOffender {
        static StandInCredential notPublicField = null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static List<Method> methodsOf(Class<?> clazz) {
        return Arrays.asList(clazz.getDeclaredMethods());
    }

    private static List<Field> fieldsOf(Class<?> clazz) {
        return Arrays.asList(clazz.getDeclaredFields());
    }

    private static Path mainClassesRoot() {
        return Paths.get("target", "classes");
    }

    private static Path testClassesRoot() {
        return Paths.get("target", "test-classes");
    }

    /**
     * Scans {@code classesRoot/<packageName as a path>} (non-recursive -- neither {@code utils} nor
     * the fixtures package has sub-packages) for {@code *.class} files, loads each via
     * {@link Class#forName(String)}, and collects every declared method -- the same derivation
     * mechanism {@link #scanMechanismCatchesAFixtureOffender()} and
     * {@link #realUtilsPackageReportsZeroViolations()} both exercise, against two different
     * packages, so the fixture test is genuine proof the mechanism works before the real one is
     * trusted (see this class's own javadoc).
     */
    private static List<Method> scanPublicStaticMethodsOfPackage(Path classesRoot, String packageName)
            throws IOException, ClassNotFoundException {
        String packagePath = packageName.replace('.', '/');
        Path packageDir = classesRoot.resolve(packagePath);
        List<Method> methods = new ArrayList<>();
        if (!Files.isDirectory(packageDir)) {
            return methods;
        }
        List<Path> classFiles;
        try (Stream<Path> paths = Files.list(packageDir)) {
            classFiles = paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .collect(Collectors.toList());
        }
        for (Path classFile : classFiles) {
            String simpleName = classFile.getFileName().toString();
            String withoutSuffix = simpleName.substring(0, simpleName.length() - ".class".length());
            String fqcn = packageName + "." + withoutSuffix;
            // The name is not attacker-controlled: it is derived by walking classesRoot's own
            // compiled package directory on disk. Rationale precedes the marker deliberately -- an
            // Opengrep marker only counts on its own line or the one immediately above.
            // nosemgrep: java.lang.security.audit.unsafe-reflection.unsafe-reflection
            Class<?> clazz = Class.forName(fqcn);
            methods.addAll(Arrays.asList(clazz.getDeclaredMethods()));
        }
        return methods;
    }

    /**
     * The field-shaped twin of {@link #scanPublicStaticMethodsOfPackage(Path, String)} (WR-03,
     * 16-REVIEW-cloud.md) -- same scan-by-compiled-class-file derivation, collecting every declared
     * field instead of every declared method.
     */
    private static List<Field> scanPublicStaticFieldsOfPackage(Path classesRoot, String packageName)
            throws IOException, ClassNotFoundException {
        String packagePath = packageName.replace('.', '/');
        Path packageDir = classesRoot.resolve(packagePath);
        List<Field> fields = new ArrayList<>();
        if (!Files.isDirectory(packageDir)) {
            return fields;
        }
        List<Path> classFiles;
        try (Stream<Path> paths = Files.list(packageDir)) {
            classFiles = paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .collect(Collectors.toList());
        }
        for (Path classFile : classFiles) {
            String simpleName = classFile.getFileName().toString();
            String withoutSuffix = simpleName.substring(0, simpleName.length() - ".class".length());
            String fqcn = packageName + "." + withoutSuffix;
            // Same rationale as scanPublicStaticMethodsOfPackage's identical call above -- the
            // name is derived from classesRoot's own compiled package directory, not attacker input.
            // nosemgrep: java.lang.security.audit.unsafe-reflection.unsafe-reflection
            Class<?> clazz = Class.forName(fqcn);
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
        }
        return fields;
    }
}
