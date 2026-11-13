package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the real fixture lifecycle, without relying on Surefire to pick an unlucky order. */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Invokes package-private test lifecycles and restores this probe's state.
class BukkitServerIsolationTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "com.ultikits.ultitools.interfaces.impl.data.json.SimpleJsonDataOperatorTest",
            "com.ultikits.ultitools.manager.JdbcTransactionalParticipationTest"
    })
    void bareServerFixtureRestoresThePreviousServer(String className) throws Exception {
        Class<?> owner = Class.forName(className);
        Server previous = Bukkit.getServer();
        List<Extension> extensions = extensions(owner);
        ExtensionContext context = context();
        try {
            for (Extension extension : extensions) {
                if (extension instanceof BeforeAllCallback) {
                    ((BeforeAllCallback) extension).beforeAll(context);
                }
            }
            lifecycle(owner, BeforeAll.class);
            assertThat(Bukkit.getServer()).as("control: the owner's fixture installed a server").isNotNull();
            lifecycle(owner, AfterAll.class);
            for (int index = extensions.size() - 1; index >= 0; index--) {
                Extension extension = extensions.get(index);
                if (extension instanceof AfterAllCallback) {
                    ((AfterAllCallback) extension).afterAll(context);
                }
            }
            assertThat(Bukkit.getServer()).as("the fixture must not poison the next config test").isSameAs(previous);
        } finally {
            Field server = Bukkit.class.getDeclaredField("server");
            server.setAccessible(true);
            server.set(null, previous);
        }
    }

    @Test
    void callbackRestoresNullAndBorrowedServersAndMayBeRepeated() throws Exception {
        Field server = Bukkit.class.getDeclaredField("server");
        server.setAccessible(true);
        Server outer = Bukkit.getServer();
        try {
            for (Server previous : new Server[] {null, mock(Server.class)}) {
                server.set(null, previous);
                MockBukkitHelper.ServerStateExtension extension = new MockBukkitHelper.ServerStateExtension();
                ExtensionContext context = context();
                extension.beforeAll(context);
                server.set(null, mock(Server.class));
                extension.afterAll(context);
                assertThat(Bukkit.getServer()).as("restore the owner's exact entry state").isSameAs(previous);
                extension.afterAll(context);
                assertThat(Bukkit.getServer()).as("a repeated callback does not clear a borrowed server").isSameAs(previous);
            }
        } finally {
            server.set(null, outer);
        }
    }

    @Test
    void everyBareServerOwnerRegistersTheRestoringLifecycle() throws Exception {
        Path sources = Paths.get("src/test/java");
        List<Path> owners;
        try (Stream<Path> files = Files.walk(sources)) {
            owners = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> installsServer(path)).collect(Collectors.toList());
        }
        assertThat(owners).as("positive control: the data fixtures are included in this scan").isNotEmpty();
        for (Path source : owners) {
            String className = sources.relativize(source).toString().replace(java.io.File.separatorChar, '.')
                    .replaceFirst("\\.java$", "");
            Class<?> owner = Class.forName(className);
            assertThat(extensions(owner)).as("%s must restore the server it installs", className)
                    .anyMatch(extension -> extension instanceof MockBukkitHelper.ServerStateExtension);
        }
    }

    private static boolean installsServer(Path source) {
        try {
            return new String(Files.readAllBytes(source), StandardCharsets.UTF_8).contains("Bukkit" + ".setServer(");
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static List<Extension> extensions(Class<?> owner) throws Exception {
        List<Extension> extensions = new ArrayList<>();
        for (ExtendWith annotation : owner.getAnnotationsByType(ExtendWith.class)) {
            for (Class<? extends Extension> type : annotation.value()) {
                extensions.add(type.getDeclaredConstructor().newInstance());
            }
        }
        return extensions;
    }

    private static void lifecycle(Class<?> owner, Class<? extends Annotation> annotation) throws Exception {
        for (Method method : owner.getDeclaredMethods()) {
            if (method.isAnnotationPresent(annotation)) {
                method.setAccessible(true);
                method.invoke(null);
            }
        }
    }

    private static ExtensionContext context() {
        ExtensionContext context = mock(ExtensionContext.class);
        ExtensionContext.Store store = mock(ExtensionContext.Store.class);
        Map<Object, Object> values = new HashMap<>();
        when(context.getStore(any())).thenReturn(store);
        doAnswer(call -> { values.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(store).put(any(), any());
        when(store.remove(any(), eq(Server[].class)))
                .thenAnswer(call -> values.remove(call.getArgument(0)));
        return context;
    }
}
