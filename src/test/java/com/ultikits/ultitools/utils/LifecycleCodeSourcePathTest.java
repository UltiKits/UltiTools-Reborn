package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objenesis.ObjenesisStd;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Component;
import com.ultikits.ultitools.context.ComponentScanner;
import com.ultikits.ultitools.context.SimpleContainer;

/** Exercises the actual lifecycle readers and scanner, not just the shared URL resolver (#692). */
class LifecycleCodeSourcePathTest {
    @TempDir
    File root;

    @Component
    public static class ComponentFixture {
    }

    public static class PluginFixture extends UltiToolsPlugin {
        @Override
        public boolean registerSelf() {
            return true;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "with space", "with+plus", "with#hash", "中文"})
    void explodedPackageRegistersItsComponent(String folderName) throws Exception {
        File folder = new File(root, folderName);
        String entry = ComponentFixture.class.getName().replace('.', '/') + ".class";
        File target = new File(folder, entry);
        Files.createDirectories(target.getParentFile().toPath());
        try (InputStream input = getClass().getResourceAsStream("/" + entry)) {
            assertThat(input).isNotNull();
            Files.copy(input, target.toPath());
        }
        String packageName = ComponentFixture.class.getPackage().getName();
        URL packageUrl = target.getParentFile().toURI().toURL();
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public URL getResource(String name) {
                return name.equals(packageName.replace('.', '/')) ? packageUrl : super.getResource(name);
            }
        };
        SimpleContainer container = new SimpleContainer();
        container.setClassLoader(loader);
        try {
            new ComponentScanner(container).scanPackage(packageName);
            assertThat(container.getBean(ComponentFixture.class)).isInstanceOf(ComponentFixture.class);
        } finally {
            container.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "with space", "with+plus", "with#hash", "中文"})
    void jarMetadataReadersKeepEscapedPathsWorking(String folderName) throws Exception {
        File folder = new File(root, folderName);
        Files.createDirectories(folder.toPath());
        File jar = new File(folder, folderName + ".jar");
        String entry = PluginFixture.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            output.putNextEntry(new JarEntry(entry));
            try (InputStream input = getClass().getResourceAsStream("/" + entry)) {
                assertThat(input).isNotNull();
                byte[] bytes = new byte[4096];
                int length;
                while ((length = input.read(bytes)) != -1) {
                    output.write(bytes, 0, length);
                }
            }
            output.closeEntry();
            output.putNextEntry(new JarEntry("plugin.yml"));
            output.write("name: PathFixture\nversion: '1.0'\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        try (FixtureLoader loader = new FixtureLoader(jar.toURI().toURL(), getClass().getClassLoader())) {
            Class<?> type = loader.loadClass(PluginFixture.class.getName());
            assertThat(PluginYmlReader.read(type).getName()).isEqualTo("PathFixture");
            Object plugin = new ObjenesisStd().newInstance(type);
            try (InputStream input = (InputStream) invoke(plugin, "getInputStream")) {
                assertThat(input).isNotNull();
                assertThat(new String(readBytes(input), StandardCharsets.UTF_8)).contains("name: PathFixture");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "with space", "with+plus", "with#hash", "中文"})
    void directoryMetadataReaderKeepsEscapedPathsWorking(String folderName) throws Exception {
        File folder = new File(root, folderName);
        String entry = PluginFixture.class.getName().replace('.', '/') + ".class";
        File target = new File(folder, entry);
        Files.createDirectories(target.getParentFile().toPath());
        try (InputStream input = getClass().getResourceAsStream("/" + entry)) {
            assertThat(input).isNotNull();
            Files.copy(input, target.toPath());
        }
        File metadata = new File(folder, "plugin.yml");
        Files.write(metadata.toPath(), "name: DirectoryFixture\nversion: '1.0'\n".getBytes(StandardCharsets.UTF_8));
        try (FixtureLoader loader = new FixtureLoader(folder.toURI().toURL(), getClass().getClassLoader())) {
            Class<?> type = loader.loadClass(PluginFixture.class.getName());
            assertThat(PluginYmlReader.read(type).getName()).isEqualTo("DirectoryFixture");
            Files.write(metadata.toPath(), "name: [unclosed\n".getBytes(StandardCharsets.UTF_8));
            assertThat(PluginYmlReader.read(type)).isSameAs(PluginYmlReader.PluginYmlInfo.EMPTY);
            Files.delete(metadata.toPath());
            assertThat(PluginYmlReader.read(type)).isSameAs(PluginYmlReader.PluginYmlInfo.EMPTY);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "my module", "my+module", "my#module", "中文"})
    void refusalFilenameIsTheActualFilename(String filename) throws Exception {
        File jar = new File(root, filename + ".jar");
        String entry = PluginFixture.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar.toPath()));
             InputStream input = getClass().getResourceAsStream("/" + entry)) {
            assertThat(input).isNotNull();
            output.putNextEntry(new JarEntry(entry));
            output.write(readBytes(input));
            output.closeEntry();
        }
        try (FixtureLoader loader = new FixtureLoader(jar.toURI().toURL(), getClass().getClassLoader())) {
            Object plugin = new ObjenesisStd().newInstance(loader.loadClass(PluginFixture.class.getName()));
            assertThat(invoke(plugin, "resolveJarFileNameForError")).isEqualTo(jar.getName());
        }
    }

    private static byte[] readBytes(InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int length;
        while ((length = input.read(buffer)) != -1) {
            bytes.write(buffer, 0, length);
        }
        return bytes.toByteArray();
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Exercise the exact private lifecycle entry points.
    private static Object invoke(Object target, String name) throws Exception {
        Method method = UltiToolsPlugin.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    /** Only the fixture itself is child-first, preserving the framework's type identity. */
    private static final class FixtureLoader extends URLClassLoader {
        FixtureLoader(URL location, ClassLoader parent) {
            super(new URL[]{location}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(PluginFixture.class.getName())) {
                return super.loadClass(name, resolve);
            }
            Class<?> type = findLoadedClass(name);
            if (type == null) {
                type = findClass(name);
            }
            if (resolve) {
                resolveClass(type);
            }
            return type;
        }
    }
}
