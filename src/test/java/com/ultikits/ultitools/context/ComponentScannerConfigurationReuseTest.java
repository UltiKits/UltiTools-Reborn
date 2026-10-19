package com.ultikits.ultitools.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.testfixtures.firstassembly.configreuse.ConfigReuseFixtures.BridgeService;
import com.ultikits.testfixtures.firstassembly.configreuse.ConfigReuseFixtures.DirectConfigA;
import com.ultikits.testfixtures.firstassembly.configreuse.ConfigReuseFixtures.DirectConfigB;
import com.ultikits.testfixtures.firstassembly.configreuse.ConfigReuseFixtures.DirectProduct;
import com.ultikits.testfixtures.firstassembly.configreuse.ConfigReuseFixtures.Recorder;
import com.ultikits.testfixtures.firstassembly.configreuse.ConfigReuseFixtures.ServiceConfigA;
import com.ultikits.testfixtures.firstassembly.configreuse.ConfigReuseFixtures.ServiceConfigB;
import com.ultikits.testfixtures.firstassembly.configreuse.ConfigReuseFixtures.ServiceProduct;

/**
 * A {@code @Configuration} class the container already created, because a configuration class
 * assembled before it needed it, is reused by the assembly phase: one construction, one
 * {@code @PostConstruct}, the instance the earlier class holds is the container's, its
 * {@code @Bean} methods run once, and {@code @PreDestroy} runs once, on that instance, at close.
 * <p>
 * The JAR lists the first configuration class before the second, so the assembly phase reaches
 * the first one first, the order that makes the container create the second one early. The loader
 * answers the fixture package's resource lookup with that JAR; the classes themselves load from the
 * test class path (see {@code ComponentScannerDiscoveryOrderTest}).
 */
@DisplayName("ComponentScanner reuses a configuration class the container created early")
class ComponentScannerConfigurationReuseTest {

    private static final String FIXTURE_PACKAGE = "com.ultikits.testfixtures.firstassembly.configreuse";
    private static final String PARENT_PACKAGE = "com.ultikits.testfixtures.firstassembly";

    @TempDir
    File tempDir;

    /** Answers each mapped package path with its own JAR; everything else as the parent does. */
    private static final class PackageJarLoader extends ClassLoader {
        private final Map<String, URL> jarByPath = new HashMap<>();

        PackageJarLoader(ClassLoader parent) {
            super(parent);
        }

        PackageJarLoader map(String packageName, File jar) throws MalformedURLException {
            jarByPath.put(packageName.replace('.', '/'), new URL("jar:" + jar.toURI().toURL() + "!/"));
            return this;
        }

        @Override
        public URL getResource(String name) {
            URL jar = jarByPath.get(name);
            return jar != null ? jar : super.getResource(name);
        }
    }

    @BeforeEach
    void resetRecords() {
        DirectConfigB.RECORD.reset();
        ServiceConfigB.RECORD.reset();
    }

    private File jarOf(String name, Class<?>... classes) throws Exception {
        File jar = new File(tempDir, name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            for (Class<?> clazz : classes) {
                String resourceName = clazz.getName().replace('.', '/') + ".class";
                output.putNextEntry(new JarEntry(resourceName));
                try (InputStream input = clazz.getResourceAsStream("/" + resourceName)) {
                    assertNotNull(input, "compiled class resource for " + clazz.getName());
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                }
                output.closeEntry();
            }
        }
        return jar;
    }

    private SimpleContainer containerWith(PackageJarLoader loader) {
        SimpleContainer container = new SimpleContainer();
        container.setClassLoader(loader);
        return container;
    }

    private static void assertOneManagedInstance(Recorder record, Object held, Object containerInstance) {
        assertThat(record.constructed()).as("constructions of the second configuration class").hasSize(1);
        Object only = record.constructed().get(0);
        assertThat(record.postConstructed()).as("@PostConstruct calls").containsExactly(only);
        assertThat(held).as("the instance the first configuration class holds").isSameAs(only);
        assertThat(containerInstance).as("the instance the container returns").isSameAs(only);
        assertThat(record.beanMethodCalls()).as("@Bean method calls").containsExactly(only);
    }

    @Test
    @DisplayName("a configuration class injected directly by an earlier one is constructed and destroyed once")
    void directlyInjectedConfigurationIsReused() throws Exception {
        File jar = jarOf("direct.jar", DirectConfigA.class, DirectConfigB.class);
        SimpleContainer container = containerWith(new PackageJarLoader(getClass().getClassLoader())
                .map(FIXTURE_PACKAGE, jar));

        container.scanComponents(FIXTURE_PACKAGE);

        DirectConfigA first = container.getBean(DirectConfigA.class);
        assertThat(first).as("the first configuration class").isNotNull();
        assertOneManagedInstance(DirectConfigB.RECORD, first.getSecond(), container.getBean(DirectConfigB.class));
        assertThat(container.getBean("directConfigB")).as("the bean named after the class")
                .isSameAs(first.getSecond());
        assertThat(container.getBean(DirectProduct.class)).as("the @Bean product").isNotNull();

        container.close();

        assertThat(DirectConfigB.RECORD.preDestroyed()).as("@PreDestroy calls at close")
                .containsExactly(first.getSecond());
    }

    @Test
    @DisplayName("a configuration class reached through a service of an earlier one is constructed and destroyed once")
    void configurationReachedThroughServiceIsReused() throws Exception {
        File jar = jarOf("through-service.jar", ServiceConfigA.class, BridgeService.class, ServiceConfigB.class);
        SimpleContainer container = containerWith(new PackageJarLoader(getClass().getClassLoader())
                .map(FIXTURE_PACKAGE, jar));

        container.scanComponents(FIXTURE_PACKAGE);

        ServiceConfigA first = container.getBean(ServiceConfigA.class);
        assertThat(first).as("the first configuration class").isNotNull();
        assertThat(first.getBridge()).as("the service between them").isNotNull();
        assertOneManagedInstance(ServiceConfigB.RECORD, first.getBridge().getSecond(),
                container.getBean(ServiceConfigB.class));
        assertThat(container.getBean(ServiceProduct.class)).as("the @Bean product").isNotNull();

        container.close();

        assertThat(ServiceConfigB.RECORD.preDestroyed()).as("@PreDestroy calls at close")
                .containsExactly(first.getBridge().getSecond());
    }

    @Test
    @DisplayName("a configuration class discovered twice, through a package and its parent, is assembled once")
    void configurationDiscoveredTwiceIsAssembledOnce() throws Exception {
        // A module whose requested packages overlap (a package and its own parent) discovers the
        // same configuration class once per requested package.
        File jar = jarOf("overlapping.jar", DirectConfigB.class);
        SimpleContainer container = containerWith(new PackageJarLoader(getClass().getClassLoader())
                .map(FIXTURE_PACKAGE, jar)
                .map(PARENT_PACKAGE, jar));

        container.scanComponents(PARENT_PACKAGE, FIXTURE_PACKAGE);

        Object managed = container.getBean(DirectConfigB.class);
        assertOneManagedInstance(DirectConfigB.RECORD, managed, managed);

        container.close();

        assertThat(DirectConfigB.RECORD.preDestroyed()).as("@PreDestroy calls at close").containsExactly(managed);
    }

    @Test
    @DisplayName("control: a configuration class nothing needs early is constructed and destroyed once")
    void independentConfigurationIsAssembledOnce() throws Exception {
        File jar = jarOf("independent.jar", DirectConfigB.class);
        SimpleContainer container = containerWith(new PackageJarLoader(getClass().getClassLoader())
                .map(FIXTURE_PACKAGE, jar));

        container.scanComponents(FIXTURE_PACKAGE);
        container.refresh();

        Object managed = container.getBean(DirectConfigB.class);
        assertOneManagedInstance(DirectConfigB.RECORD, managed, managed);

        container.close();

        assertThat(DirectConfigB.RECORD.preDestroyed()).as("@PreDestroy calls at close").containsExactly(managed);
    }
}
