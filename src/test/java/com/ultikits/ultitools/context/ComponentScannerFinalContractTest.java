package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.testfixtures.finalviolation.ViolatingComponent;
import com.ultikits.ultitools.exceptions.ContainerException;

/**
 * Integration test proving the {@link FinalContractValidator} check wired into
 * {@link ComponentScanner#processClass} actually stops module loading end-to-end, rather than
 * being caught and logged by {@link ComponentScanner#scanPackage}'s own catch-all.
 * <p>
 * {@code com.ultikits.testfixtures.finalviolation} is a dedicated fixture package (see
 * {@link ViolatingComponent}) so this test scans a directory that contains nothing but the one
 * violation. It deliberately lives outside {@code com.ultikits.ultitools} so it is never swept in
 * by a scan of the framework's own package tree - see {@code ComponentScannerTest} and
 * {@code ContextConfigTest}, which scan {@code com.ultikits.ultitools.context} and
 * {@code com.ultikits.ultitools} respectively and must stay violation-free.
 */
@DisplayName("ComponentScanner @Final contract propagation")
class ComponentScannerFinalContractTest {

    private SimpleContainer container;
    private ComponentScanner scanner;

    @BeforeEach
    void setUp() {
        container = new SimpleContainer();
        scanner = new ComponentScanner(container);
    }

    @Test
    @DisplayName("scanPackage should propagate a @Final contract violation, not swallow it")
    void shouldPropagateFinalViolationFromScanPackage() {
        ContainerException exception = assertThrows(ContainerException.class,
                () -> scanner.scanPackage("com.ultikits.testfixtures.finalviolation"));

        assertTrue(exception.getMessage().contains(ViolatingComponent.class.getName()),
                exception.getMessage());
    }

    @Test
    @DisplayName("scanComponents should propagate a @Final contract violation, not swallow it")
    void shouldPropagateFinalViolationFromScanComponents() {
        ContainerException exception = assertThrows(ContainerException.class,
                () -> container.scanComponents("com.ultikits.testfixtures.finalviolation"));

        assertTrue(exception.getMessage().contains(ViolatingComponent.class.getName()),
                exception.getMessage());
    }
}
