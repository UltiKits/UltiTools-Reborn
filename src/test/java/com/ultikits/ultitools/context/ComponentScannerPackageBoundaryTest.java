package com.ultikits.ultitools.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.description.annotation.AnnotationDescription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.annotations.Service;

class ComponentScannerPackageBoundaryTest {
    @TempDir
    Path tempDir;

    @Test
    void jarScanIncludesExactPackageButNotSiblingPrefix() throws Exception {
        Path jar = createJar("a.b.c.X", "a.b.cx.Y");
        assertScan(jar, "x");
    }

    @Test
    void jarScanIncludesSubpackagesButNotSiblingPrefix() throws Exception {
        Path jar = createJar("a.b.c.X", "a.b.c.child.Z", "a.b.cx.Y");
        assertScan(jar, "x", "z");
    }

    @Test
    void directoryScanIncludesExactPackageAndSubpackagesButNotSiblingPrefix() throws Exception {
        for (String name : new String[] {"a.b.c.X", "a.b.c.child.Z", "a.b.cx.Y"}) {
            Path output = tempDir.resolve(name.replace('.', '/') + ".class");
            Files.createDirectories(output.getParent());
            Files.write(output, componentBytes(name));
        }
        assertScan(tempDir, "x", "z");
    }

    private Path createJar(String... names) throws Exception {
        Path jar = tempDir.resolve("components.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            // A real package resource sends the public scanning entry point down its JAR branch.
            output.putNextEntry(new JarEntry("a/b/c/"));
            output.closeEntry();
            for (String name : names) {
                output.putNextEntry(new JarEntry(name.replace('.', '/') + ".class"));
                output.write(componentBytes(name));
                output.closeEntry();
            }
        }
        return jar;
    }

    private byte[] componentBytes(String name) {
        return new ByteBuddy(ClassFileVersion.JAVA_V8).subclass(Object.class).name(name)
                .annotateType(AnnotationDescription.Builder.ofType(Service.class).build())
                .make().getBytes();
    }

    private void assertScan(Path classPath, String... expectedBeans) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classPath.toUri().toURL()},
                getClass().getClassLoader())) {
            assertThat(loader.getResource("a/b/c")).as("the real scan-root resource").isNotNull();
            assertThat(loader.loadClass("a.b.cx.Y").getName()).isEqualTo("a.b.cx.Y");
            SimpleContainer container = new SimpleContainer();
            container.setClassLoader(loader);

            new ComponentScanner(container).scanPackage("a.b.c");

            assertThat(container.getBeanDefinitionNames()).containsExactlyInAnyOrder(expectedBeans);
        }
    }
}
