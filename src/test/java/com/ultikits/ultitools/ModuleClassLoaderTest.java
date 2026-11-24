package com.ultikits.ultitools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/** Real-JAR tests preserve URLClassLoader behavior, with no Bukkit bootstrap. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ModuleClassLoaderTest {
    @TempDir
    Path temp;

    private URLClassLoader loader(URL[] urls, ClassLoader parent) {
        return new ModuleClassLoader(urls, parent);
    }

    private URLClassLoader loader(Path jar) throws Exception {
        return loader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader());
    }

    @Test
    void loaderRemainsAnInternalType() throws Exception {
        assertThat(java.lang.reflect.Modifier.isPublic(ModuleClassLoader.class.getModifiers())).isTrue();
        // ApiStatus is CLASS-retained, so inspect the compiled marker rather than runtime reflection.
        try (InputStream bytes = ModuleClassLoader.class.getResourceAsStream("ModuleClassLoader.class")) {
            assertThat(bytes).isNotNull();
            byte[] bytecode = read(bytes);
            assertThat(new String(bytecode, StandardCharsets.ISO_8859_1))
                    .contains("Lorg/jetbrains/annotations/ApiStatus$Internal;");
        }
    }

    @Test
    void originalClassAndResourceBytesAreUnchanged() throws Exception {
        byte[] original = type("fixture.Plain", "original");
        Path jar = jar("plain.jar", null, entries("fixture/Plain.class", original));
        try (URLClassLoader loader = loader(jar)) {
            Class<?> loaded = loader.loadClass("fixture.Plain");
            assertThat(value(loaded)).isEqualTo("original");
            assertThat(loaded.getClassLoader()).isSameAs(loader);
            try (InputStream bytes = loader.getResourceAsStream("fixture/Plain.class")) {
                assertThat(read(bytes)).isEqualTo(original);
            }
        }
    }

    @Test
    void replacedJar_keepsLazyClassesFromTheOriginalJar() throws Exception {
        Path jar = jar("original.jar", null, entries("fixture/A.class", type("fixture.A", "old-A"),
                "fixture/B.class", type("fixture.B", "old-B")));
        Path replacement = jar("replacement.jar", null, entries("fixture/A.class", type("fixture.A", "new-A"),
                "fixture/B.class", type("fixture.B", "new-B")));
        try (URLClassLoader baseline = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null);
                URLClassLoader loader = loader(jar)) {
            assertThat(value(baseline.loadClass("fixture.A"))).isEqualTo("old-A");
            assertThat(value(loader.loadClass("fixture.A"))).isEqualTo("old-A");
            Files.move(replacement, jar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            String expected = value(baseline.loadClass("fixture.B"));
            assertThat(expected).isEqualTo("old-B");
            assertThat(value(loader.loadClass("fixture.B"))).isEqualTo(expected);
        }
    }

    @Test
    void deletedJar_keepsLazyClassesFromTheOriginalJar() throws Exception {
        Path jar = jar("deleted.jar", null, entries("fixture/A.class", type("fixture.A", "old-A"),
                "fixture/B.class", type("fixture.B", "old-B")));
        try (URLClassLoader baseline = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null);
                URLClassLoader loader = loader(jar)) {
            assertThat(value(baseline.loadClass("fixture.A"))).isEqualTo("old-A");
            assertThat(value(loader.loadClass("fixture.A"))).isEqualTo("old-A");
            Files.delete(jar);
            String expected = value(baseline.loadClass("fixture.B"));
            assertThat(expected).isEqualTo("old-B");
            assertThat(value(loader.loadClass("fixture.B"))).isEqualTo(expected);
        }
    }

    @Test
    void manifestAndCodeSource_matchTheOldLoader() throws Exception {
        Manifest manifest = manifest();
        manifest.getMainAttributes().putValue("Specification-Title", "main-spec");
        manifest.getMainAttributes().putValue("Specification-Version", "1.2");
        manifest.getMainAttributes().putValue("Specification-Vendor", "spec-vendor");
        manifest.getMainAttributes().putValue("Implementation-Title", "main-impl");
        manifest.getMainAttributes().putValue("Implementation-Version", "3.4");
        manifest.getMainAttributes().putValue("Implementation-Vendor", "impl-vendor");
        Attributes section = new Attributes();
        section.putValue("Specification-Title", "section-spec");
        section.putValue("Sealed", "true");
        manifest.getEntries().put("fixture/", section);
        Path jar = jar("metadata.jar", manifest, entries("fixture/Metadata.class", type("fixture.Metadata", "value")));
        try (URLClassLoader baseline = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null);
                URLClassLoader loader = loader(jar)) {
            Class<?> old = baseline.loadClass("fixture.Metadata");
            Class<?> actual = loader.loadClass("fixture.Metadata");
            assertThat(metadata(actual.getPackage())).isEqualTo(metadata(old.getPackage()));
            assertThat(actual.getPackage().isSealed(jar.toUri().toURL())).isTrue();
            assertThat(actual.getProtectionDomain().getCodeSource()).isEqualTo(old.getProtectionDomain().getCodeSource());
            assertThat(Collections.list(actual.getProtectionDomain().getPermissions().elements()))
                    .containsExactlyInAnyOrderElementsOf(Collections.list(old.getProtectionDomain().getPermissions().elements()));
        }
    }

    @Test
    void childPackage_doesNotInheritParentsManifest() throws Exception {
        Manifest parentManifest = manifest();
        parentManifest.getMainAttributes().putValue("Specification-Title", "parent");
        Manifest childManifest = manifest();
        childManifest.getMainAttributes().putValue("Specification-Title", "child");
        Path parentJar = jar("parent.jar", parentManifest, entries("fixture/Parent.class", type("fixture.Parent", "parent")));
        Path childJar = jar("child.jar", childManifest, entries("fixture/Child.class", type("fixture.Child", "child")));
        try (URLClassLoader parent = new URLClassLoader(new URL[]{parentJar.toUri().toURL()}, null);
                URLClassLoader child = loader(new URL[]{childJar.toUri().toURL()}, parent)) {
            assertThat(parent.loadClass("fixture.Parent").getPackage().getSpecificationTitle()).isEqualTo("parent");
            assertThat(child.loadClass("fixture.Child").getPackage().getSpecificationTitle()).isEqualTo("child");
        }
    }

    @Test
    void noManifestAndDefaultPackage_keepTheOldBehavior() throws Exception {
        Path jar = jar("no-manifest.jar", null, entries("fixture/Bare.class", type("fixture.Bare", "bare"),
                "NoPackage.class", type("NoPackage", "default")));
        try (URLClassLoader baseline = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null);
                URLClassLoader loader = loader(jar)) {
            assertThat(metadata(loader.loadClass("fixture.Bare").getPackage()))
                    .isEqualTo(metadata(baseline.loadClass("fixture.Bare").getPackage()));
            assertThat(value(loader.loadClass("NoPackage"))).isEqualTo("default");
        }
    }

    @Test
    void sealedPackage_rejectsOtherJarLikeTheOldLoader() throws Exception {
        Manifest sealed = manifest();
        sealed.getMainAttributes().putValue("Sealed", "true");
        Path first = jar("sealed.jar", sealed, entries("fixture/First.class", type("fixture.First", "first")));
        Path second = jar("other.jar", null, entries("fixture/Second.class", type("fixture.Second", "second")));
        verifySealingFailure(first, second);
    }

    @Test
    void unsealedPackage_cannotBeSealedByALaterJar() throws Exception {
        Path first = jar("unsealed.jar", null, entries("fixture/First.class", type("fixture.First", "first")));
        Manifest sealed = manifest();
        sealed.getMainAttributes().putValue("Sealed", "true");
        Path second = jar("later-sealed.jar", sealed, entries("fixture/Second.class", type("fixture.Second", "second")));
        verifySealingFailure(first, second);
    }

    private void verifySealingFailure(Path first, Path second) throws Exception {
        URL[] urls = {first.toUri().toURL(), second.toUri().toURL()};
        try (URLClassLoader baseline = new URLClassLoader(urls, null);
                URLClassLoader loader = loader(urls, null)) {
            assertThat(value(baseline.loadClass("fixture.First"))).isEqualTo("first");
            assertThat(value(loader.loadClass("fixture.First"))).isEqualTo("first");
            assertThatThrownBy(() -> baseline.loadClass("fixture.Second")).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> loader.loadClass("fixture.Second")).isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void resourcesRemainOriginalAfterClassLoading() throws Exception {
        byte[] original = type("fixture.Resource", "original");
        byte[] resource = "original-resource".getBytes(StandardCharsets.UTF_8);
        Path jar = jar("resource.jar", null, entries("fixture/Resource.class", original, "config.txt", resource));
        try (URLClassLoader loader = loader(jar)) {
            assertThat(value(loader.loadClass("fixture.Resource"))).isEqualTo("original");
            try (InputStream input = loader.getResourceAsStream("fixture/Resource.class")) {
                assertThat(read(input)).isEqualTo(original);
            }
            try (InputStream input = loader.getResourceAsStream("config.txt")) {
                assertThat(read(input)).isEqualTo(resource);
            }
            assertThat(Collections.list(loader.getResources("config.txt"))).containsExactly(loader.getResource("config.txt"));
        }
    }

    @Test
    void parentFirstAndUrlOrder_areUnchanged() throws Exception {
        Path first = jar("first.jar", null, entries("fixture/Duplicate.class", type("fixture.Duplicate", "first")));
        Path second = jar("second.jar", null, entries("fixture/Duplicate.class", type("fixture.Duplicate", "second")));
        URL[] urls = {first.toUri().toURL(), second.toUri().toURL()};
        try (URLClassLoader loader = loader(urls, null)) {
            assertThat(loader.getURLs()).containsExactly(urls);
            assertThat(value(loader.loadClass("fixture.Duplicate"))).isEqualTo("first");
        }
        try (URLClassLoader parent = new URLClassLoader(new URL[]{second.toUri().toURL()}, null);
                URLClassLoader child = loader(urls, parent)) {
            assertThat(child.getParent()).isSameAs(parent);
            assertThat(child.loadClass("fixture.Duplicate")).isSameAs(parent.loadClass("fixture.Duplicate"));
        }
    }

    @Test
    void serverJarStillWinsWhenItPrecedesAModuleWithTheSameClass() throws Exception {
        Path server = jar("server.jar", null, entries("fixture/Server.class", type("fixture.Server", "server")));
        Path module = jar("module.jar", null, entries("fixture/Server.class", type("fixture.Server", "module")));
        try (URLClassLoader loader = loader(new URL[]{server.toUri().toURL(), module.toUri().toURL()}, null)) {
            assertThat(value(loader.loadClass("fixture.Server"))).isEqualTo("server");
        }
    }

    @Test
    void directoriesAndManifestClassPath_keepTheSuperclassDefinitionPath() throws Exception {
        Path directory = Files.createDirectory(temp.resolve("classes"));
        Files.createDirectories(directory.resolve("fixture"));
        Files.write(directory.resolve("fixture/Directory.class"), type("fixture.Directory", "directory"));
        Path auxiliary = jar("aux.jar", null, entries("fixture/Auxiliary.class", type("fixture.Auxiliary", "auxiliary")));
        Manifest main = manifest();
        main.getMainAttributes().putValue("Class-Path", auxiliary.getFileName().toString());
        Path module = jar("with-cp.jar", main, entries("config.txt", new byte[]{1}));
        try (URLClassLoader loader = loader(new URL[]{directory.toUri().toURL(), module.toUri().toURL()}, null)) {
            assertThat(value(loader.loadClass("fixture.Directory"))).isEqualTo("directory");
            assertThat(value(loader.loadClass("fixture.Auxiliary"))).isEqualTo("auxiliary");
        }
    }

    @Test
    void concurrentSameClass_isDefinedOnlyOnce() throws Exception {
        Path jar = jar("same.jar", null, entries("fixture/Same.class", type("fixture.Same", "original")));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CyclicBarrier start = new CyclicBarrier(8);
        try (URLClassLoader loader = loader(jar)) {
            List<Future<Class<?>>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> { start.await(); return loader.loadClass("fixture.Same"); }));
            }
            Class<?> first = results.get(0).get(15, TimeUnit.SECONDS);
            for (Future<Class<?>> result : results) {
                assertThat(result.get(15, TimeUnit.SECONDS)).isSameAs(first);
            }
            assertThat(value(first)).isEqualTo("original");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void close_releasesJarHandlesAndPreventsNewDefinitions() throws Exception {
        Path jar = jar("close.jar", null, entries("fixture/Loaded.class", type("fixture.Loaded", "loaded"),
                "fixture/NotLoaded.class", type("fixture.NotLoaded", "not-loaded"), "resource.txt", new byte[]{1}));
        URLClassLoader loader = loader(jar);
        InputStream resource = null;
        try {
            Class<?> loaded = loader.loadClass("fixture.Loaded");
            resource = loader.getResourceAsStream("resource.txt");
            assertThat(resource).isNotNull();
            if (Files.isDirectory(Paths.get("/proc/self/fd"))) {
                assertThat(openHandles(jar)).isGreaterThan(0);
            }
            loader.close();
            loader.close();
            assertThat(value(loaded)).isEqualTo("loaded");
            assertThatThrownBy(() -> loader.loadClass("fixture.NotLoaded")).isInstanceOf(ClassNotFoundException.class);
            assertThatThrownBy(resource::read).isInstanceOf(IOException.class);
            if (Files.isDirectory(Paths.get("/proc/self/fd"))) {
                assertThat(openHandles(jar)).isZero();
            }
        } finally {
            if (resource != null) { resource.close(); }
            loader.close();
        }
    }

    @Test
    void multiReleaseJar_usesTheSameSelectedEntryAsTheOldLoader() throws Exception {
        Manifest manifest = manifest();
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        Path jar = jar("mr.jar", manifest, entries("fixture/Versioned.class", type("fixture.Versioned", "base"),
                "META-INF/versions/9/fixture/Versioned.class", type("fixture.Versioned", "version-nine")));
        try (URLClassLoader baseline = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null);
                URLClassLoader loader = loader(jar)) {
            assertThat(value(loader.loadClass("fixture.Versioned"))).isEqualTo(value(baseline.loadClass("fixture.Versioned")));
        }
    }

    @Test
    void lineNumbersRemainOriginal() throws Exception {
        Path jar = jar("lines.jar", null, entries("fixture/Lines.class", type("fixture.Lines", "original")));
        try (URLClassLoader loader = loader(jar)) {
            Class<?> type = loader.loadClass("fixture.Lines");
            InvocationTargetException failure = org.junit.jupiter.api.Assertions.assertThrows(InvocationTargetException.class,
                    () -> type.getMethod("fail").invoke(null));
            assertThat(failure.getCause().getStackTrace()[0].getLineNumber()).isEqualTo(42);
            assertThat(failure.getCause().getStackTrace()[0].getFileName()).isEqualTo("Fixture.java");
        }
    }

    @Test
    void signedJar_preservesVerifiedSignersAndRejectsTampering() throws Exception {
        Path jar = temp.resolve("signed.jar");
        // Public signed fixture, with no private key; no keystore or external signing tool needed.
        try (InputStream input = getClass().getResourceAsStream("/classloader/signed-module.jar.base64")) {
            assertThat(input).as("public signed JAR fixture").isNotNull();
            Files.write(jar, java.util.Base64.getMimeDecoder().decode(read(input)));
        }
        try (URLClassLoader baseline = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null);
                URLClassLoader loader = loader(jar)) {
            Class<?> old = baseline.loadClass("fixture.Signed");
            Class<?> actual = loader.loadClass("fixture.Signed");
            assertThat(value(old)).isEqualTo("signed");
            assertThat(value(actual)).isEqualTo("signed");
            assertThat(old.getSigners()).isNotEmpty();
            assertThat(actual.getSigners()).containsExactly(old.getSigners());
            assertThat(actual.getProtectionDomain().getCodeSource()).isEqualTo(old.getProtectionDomain().getCodeSource());
        }
        Map<String, byte[]> tampered = new LinkedHashMap<>();
        try (java.util.jar.JarFile signed = new java.util.jar.JarFile(jar.toFile())) {
            java.util.Enumeration<JarEntry> entries = signed.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                try (InputStream in = signed.getInputStream(entry)) {
                    tampered.put(entry.getName(), read(in));
                }
            }
        }
        tampered.put("fixture/Signed.class", type("fixture.Signed", "tampered"));
        Path corrupt = jar("corrupt.jar", null, tampered);
        try (URLClassLoader baseline = new URLClassLoader(new URL[]{corrupt.toUri().toURL()}, null);
                URLClassLoader loader = loader(corrupt)) {
            assertThatThrownBy(() -> baseline.loadClass("fixture.Signed")).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> loader.loadClass("fixture.Signed")).isInstanceOf(SecurityException.class);
        }
    }

    private int openHandles(Path jar) throws IOException {
        int count = 0;
        try (Stream<Path> files = Files.list(Paths.get("/proc/self/fd"))) {
            for (Path fd : (Iterable<Path>) files::iterator) {
                try {
                    if (Files.readSymbolicLink(fd).equals(jar.toAbsolutePath())) { count++; }
                } catch (IOException vanishedWhileListing) {
                    // A descriptor may vanish while /proc is enumerated.
                }
            }
        }
        return count;
    }

    private static List<Object> metadata(Package pkg) {
        return Arrays.asList(pkg.getName(), pkg.getSpecificationTitle(), pkg.getSpecificationVersion(),
                pkg.getSpecificationVendor(), pkg.getImplementationTitle(), pkg.getImplementationVersion(),
                pkg.getImplementationVendor(), pkg.isSealed());
    }

    private static String value(Class<?> type) throws Exception {
        return (String) type.getMethod("value").invoke(null);
    }

    private static Manifest manifest() {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        return manifest;
    }

    private Path jar(String name, Manifest manifest, Map<String, byte[]> entries) throws IOException {
        Path path = temp.resolve(name);
        try (JarOutputStream out = manifest == null ? new JarOutputStream(Files.newOutputStream(path))
                : new JarOutputStream(Files.newOutputStream(path), manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return path;
    }

    private static Map<String, byte[]> entries(Object... entries) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) { result.put((String) entries[i], (byte[]) entries[i + 1]); }
        return result;
    }

    private static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int length;
        while ((length = input.read(buffer)) != -1) { out.write(buffer, 0, length); }
        return out.toByteArray();
    }

    private static byte[] type(String name, String value) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name.replace('.', '/'), null, "java/lang/Object", null);
        writer.visitSource("Fixture.java", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()Ljava/lang/String;", null, null);
        method.visitCode();
        method.visitLdcInsn(value);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        MethodVisitor fail = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fail", "()V", null, null);
        fail.visitCode();
        Label line = new Label();
        fail.visitLabel(line);
        fail.visitLineNumber(42, line);
        fail.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        fail.visitInsn(Opcodes.DUP);
        fail.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false);
        fail.visitInsn(Opcodes.ATHROW);
        fail.visitMaxs(2, 0);
        fail.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
