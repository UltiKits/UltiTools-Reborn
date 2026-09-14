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
import com.ultikits.ultitools.entities.TokenEntity;

/**
 * Pins {@link CredentialStaticSurfaceInvariant}'s D-18 rule: no {@code public static} method in the
 * {@code utils} package may accept or return a {@link TokenEntity} or a generation. Synthetic cases
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
    @DisplayName("a public static method returning TokenEntity produces exactly one violation naming that method")
    void publicStaticMethodReturningTokenEntityReportsOneViolationNamingIt() {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(
                methodsOf(TokenReturningOffender.class));

        assertThat(violations)
                .hasSize(1)
                .anySatisfy(v -> {
                    assertThat(v).contains("offend");
                    assertThat(v).contains(TokenEntity.class.getSimpleName());
                });
    }

    @Test
    @DisplayName("a package-private static method returning TokenEntity produces no violation -- the rule is about the public surface")
    void packagePrivateStaticMethodReturningTokenEntityReportsNone() {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(
                methodsOf(PackagePrivateOffender.class));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("a public static method whose name says 'generation' and takes a long parameter produces one violation")
    void publicStaticMethodAcceptingGenerationShapedParameterReportsOneViolation() {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(
                methodsOf(GenerationParameterOffender.class));

        assertThat(violations)
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains("advanceGeneration"));
    }

    @Test
    @DisplayName("a public static method with a bare long parameter but an unrelated name produces no violation -- the accepted, named blind spot")
    void publicStaticMethodWithUnrelatedNamedLongParameterReportsNone() {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(
                methodsOf(UnrelatedLongParameterNonOffender.class));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("a public instance (non-static) method returning TokenEntity produces no violation -- only the static surface is D-18's concern")
    void publicInstanceMethodReturningTokenEntityReportsNone() {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(
                methodsOf(InstanceMethodNonOffender.class));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("the scan mechanism is non-vacuous: pointed at a fixture package with a permanent, deliberate offender, it finds it every run")
    void scanMechanismCatchesAFixtureOffender() throws IOException, ClassNotFoundException {
        List<Method> fixtureMethods = scanPublicStaticMethodsOfPackage(
                testClassesRoot(), CredentialSurfaceOffenderFixture.class.getPackage().getName());

        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(fixtureMethods);

        assertThat(violations)
                .as("CredentialSurfaceOffenderFixture#offendByReturningToken() must be found by the "
                        + "same scan this test uses on the real utils package below -- a scan that "
                        + "reports zero because it looked at nothing is the same failure mode as the "
                        + "stale build-output scan this phase separately fixes")
                .isNotEmpty()
                .anySatisfy(v -> assertThat(v).contains("offendByReturningToken"));
    }

    @Test
    @DisplayName("WR-03: the scan mechanism also catches the fixture's erased-generic offenders (Optional<TokenEntity> return, Consumer<TokenEntity> parameter)")
    void scanMechanismCatchesTheFixturesGenericOffenders() throws IOException, ClassNotFoundException {
        List<Method> fixtureMethods = scanPublicStaticMethodsOfPackage(
                testClassesRoot(), CredentialSurfaceOffenderFixture.class.getPackage().getName());

        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(fixtureMethods);

        assertThat(violations)
                .as("Optional<TokenEntity> erases to Optional.class -- the pre-WR-03 rule would have "
                        + "missed this entirely")
                .anySatisfy(v -> assertThat(v).contains("offendByReturningOptionalOfToken"));
        assertThat(violations)
                .as("Consumer<TokenEntity> erases to Consumer.class -- same gap, on a parameter")
                .anySatisfy(v -> assertThat(v).contains("offendByAcceptingConsumerOfToken"));
    }

    @Test
    @DisplayName("Round 1 外部评审（第四轮）：TokenEntity[] 这种具体数组类型必须被抓到，不能因为它是 Class 而不是 GenericArrayType 就漏掉")
    void publicStaticMethodReturningTokenEntityArrayReportsOneViolation() {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(
                methodsOf(ArrayReturningOffender.class));

        assertThat(violations)
                .as("反射把具体数组 TokenEntity[] 表示成 isArray()==true 的 Class，从不是 " +
                        "GenericArrayType——只处理 GenericArrayType 的旧版本会漏掉这一种")
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains("leaked"));
    }

    @Test
    @DisplayName("Round 1 外部评审（第五轮）：带 TokenEntity 上界的类型变量必须被抓到，不能因为它是 TypeVariable 而不是 WildcardType 就漏掉")
    void publicStaticMethodReturningBoundedTypeVariableReportsOneViolation() {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(
                methodsOf(BoundedTypeVariableOffender.class));

        assertThat(violations)
                .as("<T extends Iterable<TokenEntity>> 这种上界通过反射拿到的是 TypeVariable，" +
                        "不是 WildcardType——只处理 WildcardType 的旧版本会漏掉这一种")
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains("leaked"));
    }

    @Test
    @DisplayName("Round 6 external review: a self-referential bounded type variable must not StackOverflowError and must report no violation")
    void selfReferentialBoundedTypeVariableDoesNotStackOverflowAndReportsNoViolation() {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(
                methodsOf(SelfReferentialBoundNonOffender.class));

        assertThat(violations)
                .as("<T extends Comparable<T>> recurses into its own bound via Comparable<T>'s sole "
                        + "type argument, T itself -- without cycle detection this call never "
                        + "returns, and nothing here references TokenEntity anyway")
                .isEmpty();
    }

    @Test
    @DisplayName("Round 6 external review: TokenEntity referenced only through a parameterized owner type must still be caught")
    void ownerTypeLeakReportsOneViolation() {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluate(
                methodsOf(OwnerTypeLeakOffender.class));

        assertThat(violations)
                .as("GenericOwner<TokenEntity>.Inner records TokenEntity only in "
                        + "ParameterizedType#getOwnerType() -- Inner's own raw type and actual type "
                        + "arguments, both already checked, say nothing about it")
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains("leaked"));
    }

    @Test
    @DisplayName("WR-03: the field scan mechanism is non-vacuous, pointed at the fixture's permanent offending field")
    void fieldScanMechanismCatchesAFixtureOffender() throws IOException, ClassNotFoundException {
        List<Field> fixtureFields = scanPublicStaticFieldsOfPackage(
                testClassesRoot(), CredentialSurfaceOffenderFixture.class.getPackage().getName());

        List<String> violations = CredentialStaticSurfaceInvariant.evaluateFields(fixtureFields);

        assertThat(violations)
                .as("CredentialSurfaceOffenderFixture#offendingLeakedField must be found -- fields "
                        + "were entirely outside the pre-WR-03 scope of this rule")
                .isNotEmpty()
                .anySatisfy(v -> assertThat(v).contains("offendingLeakedField"));
    }

    @Test
    @DisplayName("a public static field returning TokenEntity via a synthetic fixture produces exactly one violation naming it")
    void publicStaticFieldTypedAsTokenEntityReportsOneViolationNamingIt() throws NoSuchFieldException {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluateFields(
                fieldsOf(TokenFieldOffender.class));

        assertThat(violations)
                .hasSize(1)
                .anySatisfy(v -> {
                    assertThat(v).contains("offendingField");
                    assertThat(v).contains(TokenEntity.class.getSimpleName());
                });
    }

    @Test
    @DisplayName("a public static Optional<TokenEntity> field produces one violation via the generic-type check")
    void publicStaticFieldWithGenericTokenEntityTypeReportsOneViolation() throws NoSuchFieldException {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluateFields(
                fieldsOf(GenericTokenFieldOffender.class));

        assertThat(violations)
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains("offendingGenericField"));
    }

    @Test
    @DisplayName("a package-private static field typed as TokenEntity produces no violation -- the rule is about the public surface")
    void packagePrivateStaticFieldTypedAsTokenEntityReportsNone() throws NoSuchFieldException {
        List<String> violations = CredentialStaticSurfaceInvariant.evaluateFields(
                fieldsOf(PackagePrivateFieldOffender.class));

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

    // -------------------------------------------------------------------------
    // Synthetic fixtures -- one offending or non-offending shape each
    // -------------------------------------------------------------------------

    static class TokenReturningOffender {
        public static TokenEntity offend() {
            return null;
        }
    }

    static class PackagePrivateOffender {
        static TokenEntity notPublic() {
            return null;
        }
    }

    static class GenerationParameterOffender {
        public static void advanceGeneration(long generation) {
            // Never actually called -- reflected over only, to exercise the name-based
            // generation-shaped check against a synthetic offender.
        }
    }

    static class ArrayReturningOffender {
        // Round-1 review, fourth pass (16-10, PR #464): a REIFIED array (TokenEntity[]) is a plain
        // Class with isArray() == true, never a GenericArrayType -- the pre-fix code missed this.
        public static TokenEntity[] leaked() {
            return null;
        }
    }

    static class BoundedTypeVariableOffender {
        // Round-1 review, fifth pass (16-10, PR #464): a bounded type variable is reified as a
        // TypeVariable, not a WildcardType -- the pre-fix code's WildcardType branch never fires.
        public static <T extends Iterable<TokenEntity>> T leaked() {
            return null;
        }
    }

    static class SelfReferentialBoundNonOffender {
        // Round-6 external review finding (16-10, PR #464): <T extends Comparable<T>> recurses
        // from the TypeVariable T into its bound Comparable<T> (a ParameterizedType), whose sole
        // type argument is T itself -- the same TypeVariable -- so the pre-fix recursion never
        // terminated. Nothing here references TokenEntity; this exists only to prove the scan
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
        // parameterized enclosing type -- GenericOwner<TokenEntity>.Inner -- records TokenEntity
        // ONLY in the reflected ParameterizedType's getOwnerType(); Inner's own raw type and
        // (empty) actual type arguments, both already checked, say nothing about it.
        public static GenericOwner<TokenEntity>.Inner leaked() {
            return null;
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
        public TokenEntity notStatic() {
            return null;
        }
    }

    static class TokenFieldOffender {
        public static TokenEntity offendingField = null;
    }

    static class GenericTokenFieldOffender {
        public static Optional<TokenEntity> offendingGenericField = Optional.empty();
    }

    static class PackagePrivateFieldOffender {
        static TokenEntity notPublicField = null;
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
