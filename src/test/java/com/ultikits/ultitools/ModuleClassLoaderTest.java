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
import java.security.MessageDigest;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/** Real-JAR tests: no Bukkit bootstrap and no classes accidentally supplied by the test parent. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ModuleClassLoaderTest {
    @TempDir
    Path temp;

    interface Rule extends ClassRewriteRule {
        @Override
        default boolean wouldChange(String name, byte[] bytes) throws Exception {
            return true;
        }

        @Override
        default boolean covers(ServerApiReference reference) {
            return false;
        }
    }

    private URLClassLoader loader(URL[] urls, URL server, List<Rule> rules,
            BiConsumer<String, byte[]> observer, ClassLoader parent) {
        return new ModuleClassLoader(urls, parent, server, new ArrayList<ClassRewriteRule>(rules), observer);
    }

    private URLClassLoader loader(Path jar, List<Rule> rules, BiConsumer<String, byte[]> observer) throws Exception {
        return loader(new URL[]{jar.toUri().toURL()}, null, rules, observer, getClass().getClassLoader());
    }

    private URLClassLoader loader(Path jar) throws Exception {
        return loader(jar, Collections.emptyList(), (name, bytes) -> { });
    }

    @Test
    void internalContractsHaveNoPublicTypes() {
        for (Class<?> type : Arrays.asList(ModuleClassLoader.class, ClassRewriteRule.class,
                ServerApiProbe.class, ServerApiReference.class, ModuleCompatibilityException.class)) {
            assertThat(java.lang.reflect.Modifier.isPublic(type.getModifiers())).as(type.getName()).isFalse();
            for (Class<?> nested : type.getDeclaredClasses()) {
                assertThat(java.lang.reflect.Modifier.isPublic(nested.getModifiers())).as(nested.getName()).isFalse();
            }
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                Class<?> result = method.getReturnType();
                if (result.getName().startsWith("com.ultikits.ultitools.")) {
                    assertThat(java.lang.reflect.Modifier.isPublic(result.getModifiers())).as(method.toString()).isFalse();
                }
            }
        }
    }

    @Test
    void zeroRules_defineExactlyTheSelectedJarBytes() throws Exception {
        byte[] original = type("fixture.Plain", "original");
        Path jar = jar("plain.jar", null, entries("fixture/Plain.class", original));
        AtomicReference<byte[]> defined = new AtomicReference<>();
        try (URLClassLoader loader = loader(jar, Collections.emptyList(), (name, bytes) -> defined.set(bytes))) {
            assertThat(value(loader.loadClass("fixture.Plain"))).isEqualTo("original");
            assertThat(defined.get()).as("bytes immediately before the actual defineClass").isNotNull();
            assertThat(sha256(defined.get())).isEqualTo(sha256(original));
            assertThat(loader.loadClass("fixture.Plain").getClassLoader()).isSameAs(loader);
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
            assertThat(expected).as("plain URLClassLoader keeps the already-open JAR").isEqualTo("old-B");
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
            assertThat(expected).as("plain URLClassLoader keeps the already-open JAR").isEqualTo("old-B");
            assertThat(value(loader.loadClass("fixture.B"))).isEqualTo(expected);
        }
    }

    @Test
    void changingRule_changesTheLoadedBehaviorExactlyOnce() throws Exception {
        Path jar = jar("rewrite.jar", null, entries("fixture/Changed.class", type("fixture.Changed", "old")));
        AtomicInteger rewrites = new AtomicInteger();
        Rule rule = (name, bytes) -> {
            rewrites.incrementAndGet();
            return replace(bytes, "old", "new");
        };
        try (URLClassLoader loader = loader(jar, Collections.singletonList(rule), (name, bytes) -> { })) {
            Class<?> first = loader.loadClass("fixture.Changed");
            assertThat(value(first)).isEqualTo("new");
            assertThat(loader.loadClass("fixture.Changed")).isSameAs(first);
            assertThat(rewrites.get()).isEqualTo(1);
        }
    }

    @Test
    void orderedRules_inspectAndRewriteThePreviousRulesOutput() throws Exception {
        Path jar = jar("ordered.jar", null, entries("fixture/Ordered.class", type("fixture.Ordered", "first")));
        List<String> order = new ArrayList<>();
        Rule first = (name, bytes) -> {
            order.add("first");
            return replace(bytes, "first", "second");
        };
        Rule second = new Rule() {
            @Override
            public boolean wouldChange(String name, byte[] bytes) {
                order.add("inspect-second");
                return constants(bytes).contains("second");
            }

            @Override
            public byte[] rewrite(String name, byte[] bytes) {
                order.add("second");
                return replace(bytes, "second", "third");
            }
        };
        try (URLClassLoader loader = loader(jar, Arrays.asList(first, second), (name, bytes) -> { })) {
            assertThat(value(loader.loadClass("fixture.Ordered"))).isEqualTo("third");
            assertThat(order).containsExactly("first", "inspect-second", "second");
        }
    }

    @Test
    void falsePredicate_neverRewritesOrReencodes() throws Exception {
        byte[] original = type("fixture.Unchanged", "original");
        Path jar = jar("false.jar", null, entries("fixture/Unchanged.class", original));
        AtomicInteger checks = new AtomicInteger();
        AtomicReference<byte[]> defined = new AtomicReference<>();
        Rule rule = new Rule() {
            @Override
            public boolean wouldChange(String name, byte[] bytes) {
                checks.incrementAndGet();
                return false;
            }

            @Override
            public byte[] rewrite(String name, byte[] bytes) {
                throw new AssertionError("rewrite must not run");
            }
        };
        try (URLClassLoader loader = loader(jar, Collections.singletonList(rule), (name, bytes) -> defined.set(bytes))) {
            assertThat(value(loader.loadClass("fixture.Unchanged"))).isEqualTo("original");
            assertThat(checks.get()).isEqualTo(1);
            assertThat(sha256(defined.get())).isEqualTo(sha256(original));
        }
    }

    @Test
    void throwingRewrite_failsWithClassAndCauseWithoutDefiningOriginal() throws Exception {
        verifyRuleFailure((name, bytes) -> { throw new IOException("deliberate rewrite failure"); }, IOException.class);
    }

    @Test
    void throwingPredicate_failsWithClassAndCauseWithoutDefiningOriginal() throws Exception {
        verifyRuleFailure(new Rule() {
            @Override
            public boolean wouldChange(String name, byte[] bytes) {
                throw new IllegalArgumentException("deliberate predicate failure");
            }

            @Override
            public byte[] rewrite(String name, byte[] bytes) {
                return bytes;
            }
        }, IllegalArgumentException.class);
    }

    @Test
    void nullRewrite_isLoudAndNeverFallsBack() throws Exception {
        verifyRuleFailure((name, bytes) -> null, NullPointerException.class);
    }

    private void verifyRuleFailure(Rule rule, Class<? extends Throwable> cause) throws Exception {
        Path jar = jar("failure.jar", null, entries("fixture/Failure.class", type("fixture.Failure", "valid-original")));
        AtomicInteger definitions = new AtomicInteger();
        try (URLClassLoader baseline = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            assertThat(value(baseline.loadClass("fixture.Failure"))).isEqualTo("valid-original");
        }
        try (URLClassLoader loader = loader(jar, Collections.singletonList(rule), (name, bytes) -> definitions.incrementAndGet())) {
            assertThatThrownBy(() -> loader.loadClass("fixture.Failure"))
                    .isInstanceOf(LinkageError.class).hasMessageContaining("fixture.Failure").hasCauseInstanceOf(cause);
            assertThat(definitions.get()).isZero();
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
                URLClassLoader child = loader(new URL[]{childJar.toUri().toURL()}, null, Collections.emptyList(),
                        (name, bytes) -> { }, parent)) {
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
                URLClassLoader loader = loader(urls, null, Collections.emptyList(), (name, bytes) -> { }, null)) {
            assertThat(value(baseline.loadClass("fixture.First"))).isEqualTo("first");
            assertThat(value(loader.loadClass("fixture.First"))).isEqualTo("first");
            assertThatThrownBy(() -> baseline.loadClass("fixture.Second")).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> loader.loadClass("fixture.Second")).isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void resourcesRemainOriginalAfterAClassIsRewritten() throws Exception {
        byte[] original = type("fixture.Resource", "old");
        byte[] resource = "original-resource".getBytes(StandardCharsets.UTF_8);
        Path jar = jar("resource.jar", null, entries("fixture/Resource.class", original, "config.txt", resource));
        try (URLClassLoader loader = loader(jar, Collections.singletonList((name, bytes) -> replace(bytes, "old", "new")),
                (name, bytes) -> { })) {
            assertThat(value(loader.loadClass("fixture.Resource"))).isEqualTo("new");
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
        try (URLClassLoader loader = loader(urls, null, Collections.emptyList(), (name, bytes) -> { }, null)) {
            assertThat(loader.getURLs()).containsExactly(urls);
            assertThat(value(loader.loadClass("fixture.Duplicate"))).isEqualTo("first");
        }
        try (URLClassLoader parent = new URLClassLoader(new URL[]{second.toUri().toURL()}, null);
                URLClassLoader child = loader(urls, null, Collections.singletonList((name, bytes) -> {
                    throw new AssertionError("parent class must not reach rules");
                }), (name, bytes) -> { }, parent)) {
            assertThat(child.getParent()).isSameAs(parent);
            assertThat(child.loadClass("fixture.Duplicate")).isSameAs(parent.loadClass("fixture.Duplicate"));
        }
    }

    @Test
    void nonModuleServerJar_isNeverRewrittenEvenWhenModuleContainsSameClass() throws Exception {
        Path server = jar("server.jar", null, entries("fixture/Server.class", type("fixture.Server", "server")));
        Path module = jar("module.jar", null, entries("fixture/Server.class", type("fixture.Server", "module")));
        AtomicInteger rules = new AtomicInteger();
        try (URLClassLoader loader = loader(new URL[]{server.toUri().toURL(), module.toUri().toURL()},
                server.toUri().toURL(), Collections.singletonList((name, bytes) -> {
                    rules.incrementAndGet();
                    return replace(bytes, "server", "wrong");
                }), (name, bytes) -> { throw new AssertionError("server uses super.findClass"); }, null)) {
            assertThat(value(loader.loadClass("fixture.Server"))).isEqualTo("server");
            assertThat(rules.get()).isZero();
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
        try (URLClassLoader loader = loader(new URL[]{directory.toUri().toURL(), module.toUri().toURL()}, null,
                Collections.singletonList((name, bytes) -> { throw new AssertionError("not a module JAR class"); }),
                (name, bytes) -> { throw new AssertionError("not custom-defined"); }, null)) {
            assertThat(value(loader.loadClass("fixture.Directory"))).isEqualTo("directory");
            assertThat(value(loader.loadClass("fixture.Auxiliary"))).isEqualTo("auxiliary");
        }
    }

    @Test
    void differentClassesActuallyLoadInParallel_andDefineOnePackageSafely() throws Exception {
        Path jar = jar("parallel.jar", manifest(), entries("fixture/Left.class", type("fixture.Left", "left"),
                "fixture/Right.class", type("fixture.Right", "right")));
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger calls = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (URLClassLoader loader = loader(jar, Collections.singletonList((name, bytes) -> {
            calls.incrementAndGet();
            barrier.await(10, TimeUnit.SECONDS);
            return bytes;
        }), (name, bytes) -> { })) {
            Future<Class<?>> left = pool.submit(() -> loader.loadClass("fixture.Left"));
            Future<Class<?>> right = pool.submit(() -> loader.loadClass("fixture.Right"));
            assertThat(value(left.get(15, TimeUnit.SECONDS))).isEqualTo("left");
            assertThat(value(right.get(15, TimeUnit.SECONDS))).isEqualTo("right");
            assertThat(left.get().getPackage()).isSameAs(right.get().getPackage());
            assertThat(calls.get()).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameClass_isDefinedAndRewrittenOnlyOnce() throws Exception {
        Path jar = jar("same.jar", null, entries("fixture/Same.class", type("fixture.Same", "old")));
        AtomicInteger rewrites = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CyclicBarrier start = new CyclicBarrier(8);
        try (URLClassLoader loader = loader(jar, Collections.singletonList((name, bytes) -> {
            rewrites.incrementAndGet();
            return replace(bytes, "old", "new");
        }), (name, bytes) -> { })) {
            List<Future<Class<?>>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> { start.await(); return loader.loadClass("fixture.Same"); }));
            }
            Class<?> first = results.get(0).get(15, TimeUnit.SECONDS);
            for (Future<Class<?>> result : results) {
                assertThat(result.get(15, TimeUnit.SECONDS)).isSameAs(first);
            }
            assertThat(value(first)).isEqualTo("new");
            assertThat(rewrites.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void close_releasesJarHandlesAndPreventsNewDefinitions() throws Exception {
        Path jar = jar("close.jar", null, entries("fixture/Loaded.class", type("fixture.Loaded", "loaded"),
                "fixture/NotLoaded.class", type("fixture.NotLoaded", "not-loaded"), "resource.txt", new byte[]{1}));
        Class<?> loaded;
        URLClassLoader loader = loader(jar);
        InputStream resource = null;
        try {
            loaded = loader.loadClass("fixture.Loaded");
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
            if (resource != null) {
                resource.close();
            }
            loader.close();
        }
    }

    @Test
    void multiReleaseJar_usesTheSameSelectedEntryAsTheOldLoader() throws Exception {
        Manifest manifest = manifest();
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        byte[] selected = type("fixture.Versioned", "version-nine");
        Path jar = jar("mr.jar", manifest, entries("fixture/Versioned.class", type("fixture.Versioned", "base"),
                "META-INF/versions/9/fixture/Versioned.class", selected));
        AtomicReference<byte[]> defined = new AtomicReference<>();
        try (URLClassLoader baseline = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null);
                URLClassLoader loader = loader(jar, Collections.emptyList(), (name, bytes) -> defined.set(bytes))) {
            assertThat(value(loader.loadClass("fixture.Versioned"))).isEqualTo(value(baseline.loadClass("fixture.Versioned")));
            assertThat(defined.get()).as("selected multi-release definition bytes").isNotNull();
            assertThat(sha256(defined.get())).isEqualTo(sha256(selected));
        }
    }

    @Test
    void lineNumbersSurviveRewriting() throws Exception {
        Path jar = jar("lines.jar", null, entries("fixture/Lines.class", type("fixture.Lines", "old")));
        try (URLClassLoader loader = loader(jar, Collections.singletonList((name, bytes) -> replace(bytes, "old", "new")),
                (name, bytes) -> { })) {
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
        // The public fixture contains only fixture.Signed.value() returning "signed", a self-signed
        // test certificate and its JAR signature, never a private key. Base64 preserves its bytes
        // as a text resource. Tests need neither external commands nor access to any keystore.
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
                    if (Files.readSymbolicLink(fd).equals(jar.toAbsolutePath())) {
                        count++;
                    }
                } catch (IOException vanishedWhileListing) {
                    // The directory stream's descriptor may vanish while /proc is enumerated.
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

    private static byte[] sha256(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
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
        for (int i = 0; i < entries.length; i += 2) {
            result.put((String) entries[i], (byte[]) entries[i + 1]);
        }
        return result;
    }

    private static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int length;
        while ((length = input.read(buffer)) != -1) {
            out.write(buffer, 0, length);
        }
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

    private static List<Object> constants(byte[] bytes) {
        List<Object> constants = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        constants.add(value);
                    }
                };
            }
        }, 0);
        return constants;
    }

    private static byte[] replace(byte[] bytes, String from, String to) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        super.visitLdcInsn(from.equals(value) ? to : value);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }
}
