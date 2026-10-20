package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.aop.AopEligibility;

/**
 * Reproduces #451: a server with no Vault plugin installed used to crash the whole framework
 * bootstrap, because {@link UltiTools} carried exactly one Vault-typed signature
 * ({@code getEconomy(): net.milkbowl.vault.economy.Economy}), and
 * {@link AopEligibility#findAopAnnotatedMethods(Class)} — called from
 * {@code SimpleContainer.registerSingleton} the instant the core plugin bean is registered
 * ({@code DependenceManagers.<init>:34}) — walks {@link Class#getDeclaredMethods()}, which the
 * JVM resolves eagerly: every declared method's return and parameter types are resolved while
 * building the {@code Method[]} array, not lazily on first invocation of that specific method.
 * With Vault's classes absent from the classpath, that eager resolution throws
 * {@link NoClassDefFoundError} before a single module loads.
 * <p>
 * {@link VaultHidingClassLoader} defines a fresh, isolated copy of {@link UltiTools} itself
 * (reading the exact same {@code .class} bytes the test classpath already has, so nothing else
 * about the class changes) while refusing to load anything under {@code net.milkbowl.vault} —
 * simulating exactly what a server with no Vault plugin looks like from {@code UltiTools}'s own
 * defining classloader's point of view. Everything else (Bukkit, the rest of the framework, JDK
 * classes) delegates to the parent classloader as normal — this is not a full classloading
 * sandbox, only Vault's own package is hidden.
 * <p>
 * <b>Run command:</b> this class needs no special test-group handling and runs in the ordinary
 * suite: {@code mvn test -Dtest=HiddenVaultBootstrapTest} (or plain {@code mvn test}). Unlike
 * {@code manager.TaskManagerTest} (the repository's one {@code @Tag("isolated")} precedent, which
 * mutates the live Bukkit scheduler singleton — global state shared by every other test in the
 * same JVM), this test only ever touches a private, throwaway {@link ClassLoader} and the two
 * {@link Class} objects it defines. Nothing it does is visible to any other test class, so it
 * does not need isolation and is deliberately NOT tagged {@code @Tag("isolated")}.
 */
@DisplayName("Hidden-Vault bootstrap reproduction (#451)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class HiddenVaultBootstrapTest {

    /** Taken from the import line UltiTools.java carried for its (removed, post-fix) Vault accessor. */
    private static final String VAULT_PACKAGE_PREFIX = "net.milkbowl.vault";
    private static final String TARGET_CLASS_NAME = "com.ultikits.ultitools.UltiTools";

    @Test
    @DisplayName("enumerating UltiTools's declared methods under a Vault-hiding classloader does not raise a linkage error")
    void enumeratingDeclaredMethods_underHiddenVaultClassloader_doesNotThrow() {
        VaultHidingClassLoader hidingLoader =
                new VaultHidingClassLoader(HiddenVaultBootstrapTest.class.getClassLoader());
        Class<?>[] loaded = new Class<?>[1];

        assertThatCode(() -> {
            loaded[0] = Class.forName(TARGET_CLASS_NAME, false, hidingLoader);
            AopEligibility.findAopAnnotatedMethods(loaded[0]);
        })
                .as("AopEligibility.findAopAnnotatedMethods walks Class#getDeclaredMethods(), which "
                        + "the JVM resolves eagerly — a return or parameter type from a hidden "
                        + "package must not raise a linkage error here (this is the #451 crash)")
                .doesNotThrowAnyException();

        assertThat(loaded[0].getClassLoader())
                .as("the loaded class must actually be defined by the hiding loader, not silently "
                        + "answered by the parent's already-loaded copy — otherwise this test would "
                        + "prove nothing about the hidden-Vault scenario it claims to reproduce")
                .isSameAs(hidingLoader);
    }

    @Test
    @DisplayName("the same enumeration under the normal classloader is unchanged — this is not an artefact of the hiding harness")
    void enumeratingDeclaredMethods_underNormalClassloader_alsoDoesNotThrow() {
        assertThatCode(() -> AopEligibility.findAopAnnotatedMethods(UltiTools.class))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("no method, field or constructor declared on UltiTools mentions a type from Vault's own package")
    void ultiTools_declaresNoVaultTypeInAnySignature() {
        for (Method method : UltiTools.class.getDeclaredMethods()) {
            assertThat(method.getReturnType().getName())
                    .as("return type of " + method)
                    .doesNotStartWith(VAULT_PACKAGE_PREFIX);
            for (Class<?> paramType : method.getParameterTypes()) {
                assertThat(paramType.getName())
                        .as("parameter type of " + method)
                        .doesNotStartWith(VAULT_PACKAGE_PREFIX);
            }
        }
        for (Field field : UltiTools.class.getDeclaredFields()) {
            assertThat(field.getType().getName())
                    .as("type of field " + field)
                    .doesNotStartWith(VAULT_PACKAGE_PREFIX);
        }
        for (Constructor<?> constructor : UltiTools.class.getDeclaredConstructors()) {
            for (Class<?> paramType : constructor.getParameterTypes()) {
                assertThat(paramType.getName())
                        .as("parameter type of " + constructor)
                        .doesNotStartWith(VAULT_PACKAGE_PREFIX);
            }
        }
    }

    /**
     * Hides {@code net.milkbowl.vault.*} from a single, freshly-defined copy of {@link UltiTools},
     * simulating a server with no Vault plugin installed from that class's own defining
     * classloader's point of view. Everything else delegates to the parent classloader unchanged
     * — this is a targeted hide, not a full classloading sandbox.
     */
    private static final class VaultHidingClassLoader extends ClassLoader {

        VaultHidingClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(VAULT_PACKAGE_PREFIX)) {
                throw new ClassNotFoundException(
                        "Vault is hidden by " + VaultHidingClassLoader.class.getSimpleName() + ": " + name);
            }
            if (TARGET_CLASS_NAME.equals(name)) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> alreadyDefined = findLoadedClass(name);
                    Class<?> defined = alreadyDefined != null ? alreadyDefined : findClass(name);
                    if (resolve) {
                        resolveClass(defined);
                    }
                    return defined;
                }
            }
            return super.loadClass(name, resolve);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            String resourcePath = name.replace('.', '/') + ".class";
            try (InputStream in = getParent().getResourceAsStream(resourcePath)) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = readAllBytes(in);
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }

        // Java 8 bytecode target — InputStream#readAllBytes is a Java 9+ API.
        private static byte[] readAllBytes(InputStream in) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }
}
