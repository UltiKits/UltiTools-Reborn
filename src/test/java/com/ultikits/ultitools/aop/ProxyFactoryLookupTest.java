package com.ultikits.ultitools.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.ultikits.ultitools.exceptions.ContainerException;
import com.ultikits.ultitools.exceptions.ErrorCode;

class ProxyFactoryLookupTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    @SuppressWarnings("unchecked")
    void privateLookupShouldDefineClassInForeignRuntimePackage() throws Exception {
        try (URLClassLoader loader = isolatedLoader(testClasses())) {
            Class<Object> target = (Class<Object>) loader.loadClass(IsolatedProxyTarget.class.getName());
            assertFalse(target.getModule().isNamed());
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(ProxyFactory.class.getName()));

            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(target, MethodHandles.lookup());
            assertSame(target, lookup.lookupClass());
            assertTrue((lookup.lookupModes() & MethodHandles.Lookup.PACKAGE) != 0);
            Class<?> generated = new ByteBuddy().subclass(target).make()
                    .load(loader, ClassLoadingStrategy.UsingLookup.of(lookup)).getLoaded();

            assertSameRuntimePackage(target, generated);
            assertEquals("original", target.getMethod("getValue")
                    .invoke(generated.getDeclaredConstructor().newInstance()));
        }
    }

    @Test
    void shouldKeepNestedTargetsInTheirRuntimePackage() throws Exception {
        Class<?> target = ProxyFactoryTest.SimpleTarget.class;
        assertTrue(target.isMemberClass());
        Class<?> proxy = new ProxyFactory(Collections.emptyList())
                .createProxyClass(target, Collections.emptySet());
        assertSameRuntimePackage(target, proxy);
        assertSame(target, proxy.getAnnotation(ProxyOf.class).value());
    }

    @Test
    void shouldKeepProxyOfProxyInOriginalRuntimePackage() {
        ProxyFactory factory = new ProxyFactory(Collections.emptyList());
        Class<?> target = ProxyFactoryTest.SimpleTarget.class;
        Class<?> first = factory.createProxyClass(target, Collections.emptySet());
        Class<?> second = factory.createProxyClass(first, Collections.emptySet());

        assertSameRuntimePackage(target, first);
        assertSameRuntimePackage(first, second);
        assertEquals(1, second.getAnnotationsByType(ProxyOf.class).length);
        assertSame(target, second.getAnnotation(ProxyOf.class).value());
    }

    @Test
    @SuppressWarnings({"unchecked", "PMD.AvoidAccessibilityAlteration"})
    void shouldKeepIsolatedDispatchAndTrampolinesPackagePrivate() throws Exception {
        try (URLClassLoader loader = isolatedLoader(testClasses())) {
            Class<Object> target = (Class<Object>) loader.loadClass(IsolatedProxyTarget.class.getName());
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(ProxyFactory.class.getName()));
            AtomicInteger calls = new AtomicInteger();
            ProxyFactory factory = new ProxyFactory(Collections.singletonList(invocation -> {
                calls.incrementAndGet();
                return "intercepted:" + invocation.proceed();
            }));
            Method method = target.getDeclaredMethod("packagePrivateMethod");
            method.setAccessible(true);
            Class<?> proxy = factory.createProxyClass(target, Collections.singleton(method));

            assertSameRuntimePackage(target, proxy);
            assertEquals("intercepted:pkg-original", method.invoke(proxy.getDeclaredConstructor().newInstance()));
            assertEquals(1, calls.get());
            Method[] trampolines = Arrays.stream(proxy.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().startsWith("ultitools$super$"))
                    .toArray(Method[]::new);
            assertEquals(1, trampolines.length);
            assertEquals(0, trampolines[0].getModifiers()
                    & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE));
            assertTrue(Arrays.stream(proxy.getDeclaredFields())
                    .anyMatch(field -> field.getType() == InvocationHandler.class));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldProceedToForeignPackagePrivateMethodWithoutPreopeningIt() throws Throwable {
        try (URLClassLoader loader = isolatedLoader(testClasses())) {
            Class<Object> target = (Class<Object>) loader.loadClass(IsolatedProxyTarget.class.getName());
            Object instance = target.getDeclaredConstructor().newInstance();
            Method method = target.getDeclaredMethod("packagePrivateMethod");
            assertFalse(method.canAccess(instance));

            ReflectiveMethodInvocation invocation = new ReflectiveMethodInvocation(
                    instance, method, new Object[0], Collections.emptyList());
            assertEquals("pkg-original", invocation.proceed());
        }
    }

    @Test
    void shouldWrapLookupAccessFailureWithoutUnsafeFallback() {
        ContainerException failure = assertThrows(ContainerException.class,
                () -> new ProxyFactory(Collections.emptyList())
                        .createProxyClass(ArrayList.class, Collections.emptySet()));
        assertEquals(ErrorCode.BEAN_CREATION_FAILED, failure.getErrorCode());
        assertTrue(failure.getMessage().contains(ArrayList.class.getName()));
        assertEquals(IllegalAccessException.class, failure.getCause().getClass());
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    @SuppressWarnings("unchecked")
    void shouldRetainTargetProtectionDomainForJarPackages(boolean sealed, boolean signed) throws Exception {
        Path targetJar = targetJar(sealed);
        if (signed) {
            signJar(targetJar);
        }
        URL jar = targetJar.toUri().toURL();
        try (URLClassLoader loader = isolatedLoader(jar)) {
            Class<Object> target = (Class<Object>) loader.loadClass(IsolatedProxyTarget.class.getName());
            assertEquals(sealed, target.getPackage().isSealed());
            assertEquals(jar, target.getProtectionDomain().getCodeSource().getLocation());
            if (signed) {
                assertEquals(1, target.getProtectionDomain().getCodeSource().getCertificates().length);
            }
            Class<?> proxy = new ProxyFactory(Collections.emptyList())
                    .createProxyClass(target, Collections.singleton(target.getMethod("getValue")));

            assertSameRuntimePackage(target, proxy);
            assertSame(target.getProtectionDomain(), proxy.getProtectionDomain());
            assertEquals(sealed, proxy.getPackage().isSealed());
            if (sealed) {
                assertTrue(proxy.getPackage().isSealed(jar));
            }
            assertEquals("original", target.getMethod("getValue")
                    .invoke(proxy.getDeclaredConstructor().newInstance()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"warn", "deny"})
    void shouldDefineProxyWithoutUnsafeInFreshJvm(String unsafeAccess) throws Exception {
        Path stdout = temporaryDirectory.resolve("probe-" + unsafeAccess + ".stdout");
        Path stderr = temporaryDirectory.resolve("probe-" + unsafeAccess + ".stderr");
        String classpath = String.join(File.pathSeparator,
                new File(testClasses().toURI()).getPath(),
                new File(ProxyFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath(),
                new File(ByteBuddy.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath());
        ProcessBuilder command = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--sun-misc-unsafe-memory-access=" + unsafeAccess,
                "-cp", classpath, ProxyFactoryDefinitionProbe.class.getName());
        command.environment().remove("JAVA_TOOL_OPTIONS");
        command.environment().remove("JDK_JAVA_OPTIONS");
        command.environment().remove("_JAVA_OPTIONS");
        Process child = command.redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "独立 JVM 应在期限内完成代理定义");
            String output = Files.readString(stdout, StandardCharsets.UTF_8);
            String errors = Files.readString(stderr, StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), output + errors);
            assertTrue(output.contains("proxy-loader=target-loader; package-private=intercepted:pkg-original"),
                    output + errors);
            assertFalse(errors.contains("sun.misc.Unsafe"), errors);
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }

    private static URL testClasses() {
        return IsolatedProxyTarget.class.getProtectionDomain().getCodeSource().getLocation();
    }

    private static URLClassLoader isolatedLoader(URL location) {
        return new URLClassLoader(new URL[]{location}, ClassLoader.getPlatformClassLoader());
    }

    private static void assertSameRuntimePackage(Class<?> target, Class<?> proxy) {
        assertSame(target.getClassLoader(), proxy.getClassLoader());
        assertSame(target.getModule(), proxy.getModule());
        assertSame(target.getPackage(), proxy.getPackage());
        assertEquals(target.getPackageName(), proxy.getPackageName());
    }

    private void signJar(Path jar) throws Exception {
        Path keystore = temporaryDirectory.resolve("test-signing-key.p12");
        runJdkTool("keytool", "-genkeypair", "-alias", "lookup-test", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=LookupTest", "-validity", "1", "-keystore", keystore.toString(),
                "-storetype", "PKCS12", "-storepass", "test-only", "-keypass", "test-only");
        runJdkTool("jarsigner", "-keystore", keystore.toString(), "-storepass", "test-only",
                jar.toString(), "lookup-test");
    }

    private void runJdkTool(String tool, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", tool).toString());
        command.addAll(Arrays.asList(arguments));
        Path output = temporaryDirectory.resolve(tool + ".log");
        Process child = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), tool + " 应在期限内完成");
            assertEquals(0, child.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }

    private Path targetJar(boolean sealed) throws Exception {
        Path jar = temporaryDirectory.resolve("target-" + sealed + ".jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.SEALED, Boolean.toString(sealed));
        String entry = IsolatedProxyTarget.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest);
             InputStream input = IsolatedProxyTarget.class.getClassLoader().getResourceAsStream(entry)) {
            assertNotNull(input);
            output.putNextEntry(new JarEntry(entry));
            input.transferTo(output);
            output.closeEntry();
        }
        return jar;
    }
}
