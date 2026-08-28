package com.ultikits.ultitools.context;

import com.ultikits.ultitools.context.isolationfixture.clean.CleanService;
import com.ultikits.ultitools.exceptions.ContainerException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Container-granularity layer of D-08's isolation guarantee: an unresolvable
 * {@code @Autowired(required = true)} dependency aborts the container whose scan hit it, and a
 * sibling container scanning a clean package -- and the shared parent both containers descend
 * from -- are unaffected.
 * <p>
 * {@link com.ultikits.ultitools.manager.PluginManagerClassScanningTest} already proves the
 * JAR-loading layer above this one ("a bad JAR doesn't block scanning of a subsequent valid
 * JAR"); this test proves the container-level layer beneath it and deliberately does not rebuild
 * a JAR-loading harness.
 * <br>
 * D-08 隔离保证在容器粒度上的一层：一个无法解析的 {@code @Autowired(required = true)} 依赖会中止
 * 触发它的那个容器的扫描，而扫描干净包的同级容器——以及两个容器共同的父容器——都不受影响。
 * {@link com.ultikits.ultitools.manager.PluginManagerClassScanningTest} 已经证明了它上层的
 * JAR 加载层（"坏 JAR 不应该阻止后续有效 JAR 扫描"）；本测试证明的是它下方的容器层，故意不重建
 * 一套 JAR 加载测试设施。
 */
@DisplayName("Required-dependency refusal is contained to one module's container")
class RequiredDependencyModuleIsolationTest {

    private static final String BROKEN_PACKAGE =
            "com.ultikits.ultitools.context.isolationfixture.broken";
    private static final String CLEAN_PACKAGE =
            "com.ultikits.ultitools.context.isolationfixture.clean";

    private SimpleContainer parentContainer;
    private SimpleContainer brokenContainer;
    private SimpleContainer cleanContainer;

    @BeforeEach
    void setUp() {
        parentContainer = new SimpleContainer();
        parentContainer.registerSingleton("parentMarker", "parent value");

        brokenContainer = new SimpleContainer(parentContainer);
        cleanContainer = new SimpleContainer(parentContainer);
    }

    @Test
    @DisplayName("A container whose scan hits an unresolvable required dependency aborts on refresh")
    void brokenContainerAbortsOnScanAndRefresh() {
        // Given - scanning only registers the bean definition, it does not eagerly instantiate it
        brokenContainer.scanComponents(BROKEN_PACKAGE);

        // When / Then - the unresolvable @Autowired(required = true) field surfaces as
        // ContainerException when the container is refreshed (bean instantiation time), not
        // caught and logged as a skip
        assertThrows(ContainerException.class, () -> brokenContainer.refresh());
    }

    @Test
    @DisplayName("A sibling container scanning a clean package still registers its components")
    void siblingContainerStillRegistersAfterBrokenContainerFails() {
        // Given - the broken container's scan+refresh has already failed
        brokenContainer.scanComponents(BROKEN_PACKAGE);
        assertThrows(ContainerException.class, () -> brokenContainer.refresh());

        // When - a second, independent container scans a clean package
        cleanContainer.scanComponents(CLEAN_PACKAGE);
        assertDoesNotThrow(() -> cleanContainer.refresh());

        // Then - its own component is retrievable, by name and by type
        assertNotNull(cleanContainer.getBean("cleanService"),
                "the sibling container's own component must still be retrievable by name");
        CleanService service = cleanContainer.getBean(CleanService.class);
        assertNotNull(service, "the sibling container's own component must still be retrievable by type");
        assertEquals("pong", service.ping());
    }

    @Test
    @DisplayName("A bean registered on the shared parent before the failure is still retrievable after it")
    void parentContainerIsUnaffectedByChildFailure() {
        // Given - a bean already registered on the shared parent, before either child scans
        assertEquals("parent value", parentContainer.getBean("parentMarker"));

        // When - the broken child container fails
        brokenContainer.scanComponents(BROKEN_PACKAGE);
        assertThrows(ContainerException.class, () -> brokenContainer.refresh());

        // Then - the parent-registered bean is still retrievable directly, and through the
        // still-working sibling container's own parent-delegation
        assertEquals("parent value", parentContainer.getBean("parentMarker"));
        assertEquals("parent value", cleanContainer.getBean("parentMarker"));
    }
}
