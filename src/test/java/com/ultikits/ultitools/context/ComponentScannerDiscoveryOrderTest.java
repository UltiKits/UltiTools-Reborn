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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.testfixtures.firstassembly.factory.FactoryOrderFixtureModule.FactoryConfiguration;
import com.ultikits.testfixtures.firstassembly.factory.FactoryOrderFixtureModule.FactoryProduct;
import com.ultikits.testfixtures.firstassembly.factory.FactoryOrderFixtureModule.FactoryService;

/**
 * C51 (decision P-07): every component definition in the requested packages is registered before
 * any {@code @Configuration} class or {@code @Bean} product is created, so a factory product that
 * needs a {@code @Service} loads whatever order the JAR lists the two classes in.
 * <p>
 * Each JAR holds exactly the two nested classes of
 * {@code com.ultikits.testfixtures.firstassembly.factory.FactoryOrderFixtureModule} named above,
 * in the order the test writes them. The loader answers a requested package's resource lookup with
 * the JAR mapped to it, which sends {@code ComponentScanner} down its JAR branch; that branch reads
 * only entry names from the JAR, and the classes themselves load from the test class path.
 */
@DisplayName("ComponentScanner registers every definition before configuration classes (C51)")
class ComponentScannerDiscoveryOrderTest {

    private static final String FACTORY_PACKAGE = "com.ultikits.testfixtures.firstassembly.factory";
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

    private void assertProductHoldsService(SimpleContainer container) {
        FactoryProduct product = container.getBean(FactoryProduct.class);
        assertThat(product).as("the @Bean product").isNotNull();
        assertThat(product.getService()).as("the product's injected service")
                .isSameAs(container.getBean(FactoryService.class));
    }

    @Test
    @DisplayName("a JAR listing the configuration class before the service scans and injects the product")
    void configurationFirstJarLoads() throws Exception {
        File jar = jarOf("configuration-first.jar", FactoryConfiguration.class, FactoryService.class);
        SimpleContainer container = containerWith(new PackageJarLoader(getClass().getClassLoader())
                .map(FACTORY_PACKAGE, jar));

        container.scanComponents(FACTORY_PACKAGE);

        assertProductHoldsService(container);
    }

    @Test
    @DisplayName("control: a JAR listing the service before the configuration class scans and injects the product")
    void serviceFirstJarLoads() throws Exception {
        File jar = jarOf("service-first.jar", FactoryService.class, FactoryConfiguration.class);
        SimpleContainer container = containerWith(new PackageJarLoader(getClass().getClassLoader())
                .map(FACTORY_PACKAGE, jar));

        container.scanComponents(FACTORY_PACKAGE);

        assertProductHoldsService(container);
    }

    @Test
    @DisplayName("two requested packages: a configuration class in the first, the service it needs in the second")
    void definitionsOfEveryRequestedPackageComeFirst() throws Exception {
        // Each requested package resolves to its own JAR, as two class-path roots would: the first
        // holds only the configuration class, the second only the service.
        File configurationJar = jarOf("first-package.jar", FactoryConfiguration.class);
        File serviceJar = jarOf("second-package.jar", FactoryService.class);
        SimpleContainer container = containerWith(new PackageJarLoader(getClass().getClassLoader())
                .map(FACTORY_PACKAGE, configurationJar)
                .map(PARENT_PACKAGE, serviceJar));

        container.scanComponents(FACTORY_PACKAGE, PARENT_PACKAGE);

        assertProductHoldsService(container);
    }
}
