package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.testfixtures.beanname.BeanNameFixtures;
import com.ultikits.testfixtures.beanname.PostConstructCounter;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ComponentScan;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.context.scanfixture.markerpkg.MarkerClass;
import com.ultikits.ultitools.context.scanfixture.otherpkg.OtherMarkerClass;
import com.ultikits.ultitools.exceptions.ContainerException;
import com.ultikits.ultitools.manager.PluginManager;

/**
 * Per-attribute effect tests for this phase's last two declared-but-dead attributes (03-09):
 * {@code @Bean(name=)}/{@code @Bean(value=)} (Task 1) and {@code scanBasePackageClasses()} at
 * both of its read sites (Task 2). Every assertion here looks up the bean or registration the
 * attribute declares -- never observes a log line -- per this phase's success criterion 3.
 * <p>
 * Task 1's {@code @Bean} fixtures live in {@code com.ultikits.testfixtures.beanname}, not
 * nested here -- see that package's {@code package-info} for why co-locating a deliberately
 * malformed {@code @Bean} declaration under {@code com.ultikits.ultitools.context} would abort
 * every unrelated test that scans this package. Task 2's {@code scanBasePackageClasses} fixture
 * modules stay nested here at this outer class's top level (JUnit 5 requires {@code @Nested}
 * test classes to be non-static inner classes, and a non-static inner class cannot itself
 * declare a {@code static} member class) -- matching {@code ComponentScannerTest}'s established
 * shape, where {@code TestComponent}/{@code TestConfiguration} sit at the top level and
 * {@code @Nested} groups hold only test methods. They carry no risk of the same collision:
 * {@code ComponentScanner.hasComponentAnnotation} unconditionally excludes every
 * {@code UltiToolsPlugin} subclass, so an unrelated scan of this package can encounter them
 * without side effects.
 */
@DisplayName("Declared-but-dead attribute effect tests (03-09)")
class DeclaredAttributeEffectTest {

    // ===== Task 2 fixtures: scanBasePackageClasses =====

    private static final String MARKER_PKG = "com.ultikits.ultitools.context.scanfixture.markerpkg";
    private static final String OTHER_PKG = "com.ultikits.ultitools.context.scanfixture.otherpkg";

    @UltiToolsModule(scanBasePackageClasses = MarkerClass.class)
    static class ModuleWithBasePackageClassesOnly extends UltiToolsPlugin {
        @Override
        public boolean registerSelf() {
            return true;
        }
    }

    @UltiToolsModule(scanBasePackages = MARKER_PKG, scanBasePackageClasses = OtherMarkerClass.class)
    static class ModuleWithBothSources extends UltiToolsPlugin {
        @Override
        public boolean registerSelf() {
            return true;
        }
    }

    @UltiToolsModule(scanBasePackages = OTHER_PKG, scanBasePackageClasses = OtherMarkerClass.class)
    static class ModuleWithAdjacentDuplicate extends UltiToolsPlugin {
        @Override
        public boolean registerSelf() {
            return true;
        }
    }

    @UltiToolsModule(scanBasePackages = MARKER_PKG, scanBasePackageClasses = {})
    static class ModuleWithEmptyScanBasePackageClasses extends UltiToolsPlugin {
        @Override
        public boolean registerSelf() {
            return true;
        }
    }

    @UltiToolsModule
    static class ModuleWithNothingDeclared extends UltiToolsPlugin {
        @Override
        public boolean registerSelf() {
            return true;
        }
    }

    @UltiToolsModule(scanBasePackages = MARKER_PKG, scanBasePackageClasses = {Object[].class})
    static class ModuleWithNullPackageClassElement extends UltiToolsPlugin {
        @Override
        public boolean registerSelf() {
            return true;
        }
    }

    @ComponentScan(basePackageClasses = MarkerClass.class)
    static class ConfigWithBasePackageClasses {
    }

    /**
     * Task 1: {@code @Bean(name=)}/{@code @Bean(value=)} determine the registered bean name, with
     * the rest of a declared name array bound as aliases sharing one fully-assembled instance
     * (D-06). Every scenario invokes {@code ComponentScanner.processBeanMethod} directly via
     * reflection against a freshly built {@link SimpleContainer}/{@link ComponentScanner} pair --
     * deliberately not through a package scan, so a deliberately malformed fixture here can never
     * abort an unrelated test's {@code scanPackage("com.ultikits.ultitools.context")} call (the
     * same isolation reason {@code com.ultikits.testfixtures.finalviolation.scanner} exists as its
     * own package rather than living inside {@code ComponentScannerTest}).
     */
    @Nested
    @DisplayName("Task 1: @Bean(name)/@Bean(value) determine the registered bean name")
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    class BeanNameAndValue {

        private SimpleContainer container;
        private ComponentScanner scanner;
        private Method processBeanMethod;

        @BeforeEach
        void setUp() throws NoSuchMethodException {
            container = new SimpleContainer();
            scanner = new ComponentScanner(container);
            processBeanMethod = ComponentScanner.class.getDeclaredMethod(
                    "processBeanMethod", Object.class, Method.class);
            processBeanMethod.setAccessible(true);
        }

        /**
         * Invokes {@code ComponentScanner.processBeanMethod} reflectively for the named
         * {@code @Bean} method on {@code configInstance}, unwrapping the
         * {@link InvocationTargetException} {@code Method.invoke} wraps any thrown exception in
         * so callers see the real {@link ContainerException} (or none at all).
         */
        private void invokeProcessBeanMethod(Object configInstance, String methodName) throws Throwable {
            Method beanMethod = configInstance.getClass().getDeclaredMethod(methodName);
            try {
                processBeanMethod.invoke(scanner, configInstance, beanMethod);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        @Test
        @DisplayName("no name/value declared -> registered under the method name, exactly as today")
        void defaultsToMethodName() throws Throwable {
            BeanNameFixtures fixtures = new BeanNameFixtures();
            invokeProcessBeanMethod(fixtures, "defaultName");
            assertNotNull(container.getBean("defaultName"));
        }

        @Test
        @DisplayName("@Bean(name = \"customName\") registers under customName, not the method name")
        void customNameWins() throws Throwable {
            BeanNameFixtures fixtures = new BeanNameFixtures();
            invokeProcessBeanMethod(fixtures, "withCustomName");
            assertNotNull(container.getBean("customName"));
            assertNull(container.getBean("withCustomName"),
                    "the method-name key must not also resolve once a custom name is declared");
        }

        @Test
        @DisplayName("@Bean(value = \"customValueName\") is interchangeable with name")
        void valueAliasWins() throws Throwable {
            BeanNameFixtures fixtures = new BeanNameFixtures();
            invokeProcessBeanMethod(fixtures, "withCustomValue");
            assertNotNull(container.getBean("customValueName"));
            assertNull(container.getBean("withCustomValue"));
        }

        @Test
        @DisplayName("@Bean(name = {primary, alias1, alias2}) -- all three resolve to the same instance")
        void multipleNamesShareOneInstance() throws Throwable {
            BeanNameFixtures fixtures = new BeanNameFixtures();
            invokeProcessBeanMethod(fixtures, "withAliases");

            Object primary = container.getBean("primary");
            Object alias1 = container.getBean("alias1");
            Object alias2 = container.getBean("alias2");

            assertNotNull(primary);
            assertSame(primary, alias1, "alias1 must be the SAME assembled instance as primary");
            assertSame(primary, alias2, "alias2 must be the SAME assembled instance as primary");
        }

        @Test
        @DisplayName("a @PostConstruct method on a three-name bean runs exactly once")
        void postConstructRunsOnceDespiteAliases() throws Throwable {
            BeanNameFixtures fixtures = new BeanNameFixtures();
            invokeProcessBeanMethod(fixtures, "withAliases");

            PostConstructCounter bean = (PostConstructCounter) container.getBean("primary");
            assertEquals(1, bean.getConstructCount(),
                    "registering 3 names for one bean must not invoke @PostConstruct 3 times");
        }

        @Test
        @DisplayName("@Bean(name = \"a\", value = \"b\") -- conflicting non-empty name/value throws")
        void conflictingNameAndValueThrows() {
            BeanNameFixtures fixtures = new BeanNameFixtures();
            ContainerException thrown = assertThrows(ContainerException.class,
                    () -> invokeProcessBeanMethod(fixtures, "conflictingNameValue"));
            String message = thrown.getMessage();
            assertTrue(message.contains("conflictingNameValue"), message);
            assertTrue(message.contains("a"), message);
            assertTrue(message.contains("b"), message);
        }

        @Test
        @DisplayName("@Bean(name = \"same\", value = \"same\") -- identical content is legal")
        void identicalNameAndValueIsLegal() throws Throwable {
            BeanNameFixtures fixtures = new BeanNameFixtures();
            invokeProcessBeanMethod(fixtures, "identicalNameValue");
            assertNotNull(container.getBean("same"));
        }

        @Test
        @DisplayName("@Bean(name = {}) falls back to the method name, same as absent")
        void emptyNameArrayFallsBackToMethodName() throws Throwable {
            BeanNameFixtures fixtures = new BeanNameFixtures();
            invokeProcessBeanMethod(fixtures, "emptyNameArray");
            assertNotNull(container.getBean("emptyNameArray"));
        }

        @Test
        @DisplayName("@Bean(value = {}) falls back to the method name, same as absent")
        void emptyValueArrayFallsBackToMethodName() throws Throwable {
            BeanNameFixtures fixtures = new BeanNameFixtures();
            invokeProcessBeanMethod(fixtures, "emptyValueArray");
            assertNotNull(container.getBean("emptyValueArray"));
        }

        @Test
        @DisplayName("@Bean(name = \"\") -- a blank declared name fails at load")
        void blankNameThrows() {
            BeanNameFixtures fixtures = new BeanNameFixtures();
            assertThrows(ContainerException.class, () -> invokeProcessBeanMethod(fixtures, "blankName"));
        }

        @Test
        @DisplayName("@Bean(name = {\"ok\", \"  \"}) -- a blank element among several also fails at load")
        void blankElementInArrayThrows() {
            BeanNameFixtures fixtures = new BeanNameFixtures();
            assertThrows(ContainerException.class,
                    () -> invokeProcessBeanMethod(fixtures, "blankElementInArray"));
        }

        @Test
        @DisplayName("two @Bean names differing only by Unicode normalization form register as two distinct beans")
        void unicodeNormalizationFormsAreDistinctNames() throws Throwable {
            BeanNameFixtures fixtures = new BeanNameFixtures();
            invokeProcessBeanMethod(fixtures, "nfcName");
            invokeProcessBeanMethod(fixtures, "nfdName");

            String nfc = "caf\u00e9";
            String nfd = "cafe\u0301";
            assertNotEquals(nfc, nfd,
                    "the two literals must actually differ by code unit for this test to mean anything");

            Object nfcBean = container.getBean(nfc);
            Object nfdBean = container.getBean(nfd);
            assertNotNull(nfcBean);
            assertNotNull(nfdBean);
            assertNotSame(nfcBean, nfdBean, "normalization-distinct names must never merge into one bean");
        }
    }

    /**
     * Task 2: {@code scanBasePackageClasses()} is implemented at both of its dead read sites --
     * {@code PluginManager.getPluginScanPackages} (via reflection, since the method is private and
     * needs no Bukkit/plugin bootstrap to exercise) and
     * {@code SimpleContainer.processConfigurationClass} (its own public entry point, following
     * {@code ScanTest}'s established pattern). Every positive assertion retrieves the scanned
     * component from a real container, per this phase's success criterion 3 -- never just
     * inspects the resolved package-name array.
     */
    @Nested
    @DisplayName("Task 2: scanBasePackageClasses is implemented at both read sites")
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    class ScanBasePackageClasses {

        private PluginManager pluginManager;
        private Method getPluginScanPackagesMethod;

        @BeforeEach
        void setUp() throws NoSuchMethodException {
            pluginManager = new PluginManager();
            getPluginScanPackagesMethod = PluginManager.class.getDeclaredMethod(
                    "getPluginScanPackages", Class.class);
            getPluginScanPackagesMethod.setAccessible(true);
        }

        private String[] resolvePackages(Class<? extends UltiToolsPlugin> pluginClass) throws Throwable {
            try {
                return (String[]) getPluginScanPackagesMethod.invoke(pluginManager, pluginClass);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private SimpleContainer freshScanningContainer() {
            SimpleContainer container = new SimpleContainer();
            container.setClassLoader(getClass().getClassLoader());
            return container;
        }

        @Test
        @DisplayName("scanBasePackageClasses alone resolves to the marker's package and its @Service is retrievable")
        void scanBasePackageClassesAloneScansAndRegisters() throws Throwable {
            String[] resolved = resolvePackages(ModuleWithBasePackageClassesOnly.class);
            assertArrayEquals(new String[]{MARKER_PKG}, resolved);

            SimpleContainer container = freshScanningContainer();
            container.scanComponents(resolved);
            assertNotNull(container.getBean("markerService"));
        }

        @Test
        @DisplayName("@ComponentScan(basePackageClasses = Marker.class) declared directly is honoured too")
        void componentScanBasePackageClassesDirectlyDeclared() {
            SimpleContainer container = freshScanningContainer();
            container.processConfigurationClass(ConfigWithBasePackageClasses.class);
            assertNotNull(container.getBean("markerService"));
        }

        @Test
        @DisplayName("scanBasePackages and scanBasePackageClasses combine, scanBasePackages first, both scanned")
        void bothSourcesCombineInDeclarationOrder() throws Throwable {
            String[] resolved = resolvePackages(ModuleWithBothSources.class);
            assertArrayEquals(new String[]{MARKER_PKG, OTHER_PKG}, resolved);

            SimpleContainer container = freshScanningContainer();
            container.scanComponents(resolved);
            assertNotNull(container.getBean("markerService"));
            assertNotNull(container.getBean("otherService"));
        }

        @Test
        @DisplayName("a package named by both sources resolves and registers exactly once")
        void adjacentDuplicateCollapsesToOneEntry() throws Throwable {
            String[] resolved = resolvePackages(ModuleWithAdjacentDuplicate.class);
            assertArrayEquals(new String[]{OTHER_PKG}, resolved);

            SimpleContainer container = freshScanningContainer();
            container.scanComponents(resolved);

            int matches = 0;
            for (String beanName : container.getBeanDefinitionNames()) {
                if ("otherService".equals(beanName)) {
                    matches++;
                }
            }
            assertEquals(1, matches, "the shared package must be registered exactly once, not duplicated");
        }

        @Test
        @DisplayName("scanBasePackageClasses = {} contributes nothing and does not suppress scanBasePackages")
        void emptyScanBasePackageClassesContributesNothing() throws Throwable {
            String[] resolved = resolvePackages(ModuleWithEmptyScanBasePackageClasses.class);
            assertArrayEquals(new String[]{MARKER_PKG}, resolved);
        }

        @Test
        @DisplayName("neither attribute set -- falls back to the plugin class's own package, unchanged")
        void bothEmptyFallsBackToOwnPackage() throws Throwable {
            String[] resolved = resolvePackages(ModuleWithNothingDeclared.class);
            assertArrayEquals(new String[]{ModuleWithNothingDeclared.class.getPackage().getName()}, resolved);
        }

        @Test
        @DisplayName("a Class whose getPackage() is null (array type) does not produce a null entry")
        void nullPackageElementIsSkipped() throws Throwable {
            String[] resolved = resolvePackages(ModuleWithNullPackageClassElement.class);
            assertArrayEquals(new String[]{MARKER_PKG}, resolved);
            for (String pkg : resolved) {
                assertNotNull(pkg);
            }
        }
    }
}
