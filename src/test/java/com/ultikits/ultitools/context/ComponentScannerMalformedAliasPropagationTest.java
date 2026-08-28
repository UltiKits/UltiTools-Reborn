package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.testfixtures.malformedalias.scanner.MalformedAliasFixture;
import com.ultikits.ultitools.exceptions.ContainerException;

/**
 * Integration test closing the {@code unrun-verify} window 03-01 recorded in
 * {@code .planning/WINDOWS.md} (id 1): a malformed {@code @AliasFor} declaration discovered
 * during a real {@code ComponentScanner.scanPackage()} scan propagates
 * {@link ContainerException} out of the scan, rather than being logged and skipped.
 * <p>
 * 03-01 proved {@code MergedAnnotationResolver.validateAliases} throws directly
 * ({@code MergedAnnotationResolverTest}'s five malformed-shape tests); this test proves the same
 * refusal reaches a real caller, now that {@code ComponentScanner.hasComponentAnnotation} is
 * wired to {@link MergedAnnotationResolver#isPresent} (03-02). Mirrors
 * {@code ComponentScannerFinalContractTest}'s existing pattern for the identical propagation
 * question about {@code @Final} contract violations.
 */
@DisplayName("ComponentScanner malformed @AliasFor propagation (closes WINDOWS.md id 1)")
class ComponentScannerMalformedAliasPropagationTest {

    private SimpleContainer container;
    private ComponentScanner scanner;

    @BeforeEach
    void setUp() {
        container = new SimpleContainer();
        scanner = new ComponentScanner(container);
    }

    @Test
    @DisplayName("scanPackage should propagate a malformed @AliasFor declaration, not swallow it")
    void shouldPropagateMalformedAliasFromScanPackage() {
        ContainerException exception = assertThrows(ContainerException.class,
                () -> scanner.scanPackage("com.ultikits.testfixtures.malformedalias.scanner"));

        assertTrue(exception.getMessage().contains(
                        com.ultikits.testfixtures.malformedalias.scanner.MalformedComposedAnnotation.class.getName()),
                exception.getMessage());
    }

    @Test
    @DisplayName("scanComponents should propagate a malformed @AliasFor declaration, not swallow it")
    void shouldPropagateMalformedAliasFromScanComponents() {
        ContainerException exception = assertThrows(ContainerException.class,
                () -> container.scanComponents("com.ultikits.testfixtures.malformedalias.scanner"));

        assertTrue(exception.getMessage().contains(
                        com.ultikits.testfixtures.malformedalias.scanner.MalformedComposedAnnotation.class.getName()),
                exception.getMessage());
    }

    @Test
    @DisplayName("Inert-case guard: the fixture class name alone is not proof -- the declaring annotation must be named")
    void exceptionNamesTheDeclaringAnnotationNotJustTheFixtureClass() {
        // If a future refactor accidentally caught ContainerException at a different layer and
        // rethrew a generic message, an assertion that only checked "some ContainerException was
        // thrown" would still pass. Naming MalformedComposedAnnotation specifically -- the type
        // MergedAnnotationResolver.validateAliases actually names in its refusal message -- is
        // what ties this assertion to the real mechanism rather than to MalformedAliasFixture's
        // own class name, which never appears in validateAliases' message at all.
        ContainerException exception = assertThrows(ContainerException.class,
                () -> scanner.scanPackage("com.ultikits.testfixtures.malformedalias.scanner"));

        assertTrue(exception.getMessage().contains("value"), exception.getMessage());
        assertTrue(exception.getMessage().contains("not meta-present"), exception.getMessage());
    }

    // Referenced only to keep an explicit compile-time dependency on the fixture class itself
    // (not just the annotation), matching ComponentScannerFinalContractTest's own pattern.
    @SuppressWarnings("unused")
    private static final Class<MalformedAliasFixture> FIXTURE_CLASS = MalformedAliasFixture.class;
}
