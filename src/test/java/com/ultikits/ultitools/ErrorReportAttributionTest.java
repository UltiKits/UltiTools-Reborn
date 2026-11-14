package com.ultikits.ultitools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.ToolProvider;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.ExceptionCatch;
import com.ultikits.ultitools.aop.ExceptionInterceptor;
import com.ultikits.ultitools.aop.MethodInvocation;
import com.ultikits.ultitools.aop.ProxyFactory;
import com.ultikits.ultitools.manager.ErrorReportCollector;
import com.ultikits.ultitools.manager.PluginManager;

/** Real JAR provenance, including one loader and overlapping packages (#887). */
public class ErrorReportAttributionTest {
    private static final String PACKAGE = "org.example.errorfixture.shared";

    @TempDir
    Path temporary;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commandsInTwoJarsWithTheSamePackageReportTheirOwnModule(boolean proxied) throws Exception {
        try (ModuleClassLoader loader = modules(); MockedStatic<UltiTools> framework = mockStatic(UltiTools.class);
                MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            ErrorReportCollector collector = install(framework, loader);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            for (String suffix : new String[] {"A", "B"}) {
                Class<?> type = loader.loadClass(PACKAGE + ".Command" + suffix);
                if (proxied) {
                    type = new ProxyFactory(Collections.emptyList()).createProxyClass(type, Collections.emptySet());
                }
                BaseCommandExecutor executor = (BaseCommandExecutor) type.getDeclaredConstructor().newInstance();
                Command command = mock(Command.class);
                when(command.getName()).thenReturn("probe");
                assertThat(executor.onCommand(mock(CommandSender.class), command, "probe", new String[0])).isTrue();
                assertModule(collector, "module-" + suffix.toLowerCase(java.util.Locale.ROOT));
            }
        }
    }

    @Test
    void inheritedAopMethodsUseTheConcreteProxiedBeansJar() throws Throwable {
        try (ModuleClassLoader loader = modules(); MockedStatic<UltiTools> framework = mockStatic(UltiTools.class)) {
            ErrorReportCollector collector = install(framework, loader);
            for (String suffix : new String[] {"A", "B"}) {
                Class<?> bean = loader.loadClass(PACKAGE + ".Bean" + suffix);
                Method method = bean.getMethod("fail");
                Class<?> proxy = new ProxyFactory(Collections.emptyList())
                        .createProxyClass(bean, Collections.singleton(method));
                Object target = proxy.getDeclaredConstructor().newInstance();
                MethodInvocation invocation = mock(MethodInvocation.class);
                when(invocation.getTarget()).thenReturn(target);
                when(invocation.getMethod()).thenReturn(method);
                when(invocation.proceed()).thenThrow(failure("inherited"));
                int occurrences = "A".equals(suffix) ? 2 : 3;
                for (int i = 0; i < occurrences; i++) {
                    assertThat(new ExceptionInterceptor().invoke(invocation)).isNull();
                }
            }
            List<JsonObject> reports = payloads(collector);
            assertThat(reports).extracting(report -> report.get("moduleName").getAsString()
                    + ":" + report.get("occurrences").getAsInt())
                    .containsExactlyInAnyOrder("module-a:2", "module-b:3");
            assertThat(reports).extracting(report -> report.get("fingerprint").getAsString())
                    .doesNotHaveDuplicates();
        }
    }

    @Test
    void aClassOutsideEveryLoadedModuleDoesNotGuessByPackageOrBeanName() throws Exception {
        try (ModuleClassLoader loader = modules(); MockedStatic<UltiTools> framework = mockStatic(UltiTools.class)) {
            ErrorReportCollector collector = install(framework, loader);
            for (Class<?> outside : new Class<?>[] {InheritedBean.class, String.class, null}) {
                collector.reportErrorFromClass(failure("unknown-" + outside), outside, null);
                assertThat(payload(collector).get("moduleName").isJsonNull()).isTrue();
            }
        }
    }

    @Test
    void anUnloadedModuleIsNotAttributedEvenWhenItsJarIsStillOnTheLoader() throws Exception {
        try (ModuleClassLoader loader = modules(); MockedStatic<UltiTools> framework = mockStatic(UltiTools.class)) {
            ErrorReportCollector collector = install(framework, loader);
            when(UltiTools.getInstance().getPluginManager().getPluginList()).thenReturn(Collections.emptyList());
            collector.reportErrorFromClass(failure("unloaded"), loader.loadClass(PACKAGE + ".BeanA"), null);
            assertThat(payload(collector).get("moduleName").isJsonNull()).isTrue();
        }
    }

    @Test
    void moreThanOneModuleFromOneJarRemainsUnattributed() throws Exception {
        try (ModuleClassLoader loader = modules(); MockedStatic<UltiTools> framework = mockStatic(UltiTools.class)) {
            ErrorReportCollector collector = install(framework, loader);
            List<UltiToolsPlugin> ambiguous = Arrays.asList(module(loader, "A"), module(loader, "A"));
            when(UltiTools.getInstance().getPluginManager().getPluginList()).thenReturn(ambiguous);
            collector.reportErrorFromClass(failure("ambiguous"), loader.loadClass(PACKAGE + ".BeanA"), null);
            assertThat(payload(collector).get("moduleName").isJsonNull()).isTrue();
        }
    }

    /** Declared outside either module: attribution must use the target, not this method's owner. */
    public static class InheritedBean {
        @ExceptionCatch(silent = true)
        public void fail() {
            throw failure("inherited");
        }
    }

    private ModuleClassLoader modules() throws IOException {
        URL first = jar("A").toUri().toURL();
        URL second = jar("B").toUri().toURL();
        return new ModuleClassLoader(new URL[] {first, second}, getClass().getClassLoader(),
                null, Collections.emptyList(), null);
    }

    private Path jar(String suffix) throws IOException {
        Path root = Files.createDirectory(temporary.resolve("module " + suffix + "+"));
        Path source = root.resolve("Module" + suffix + ".java");
        String code = "package " + PACKAGE + "; public class Module" + suffix
                + " extends com.ultikits.ultitools.abstracts.UltiToolsPlugin {"
                + "public boolean registerSelf() {return true;} }";
        Files.write(source, code.getBytes(StandardCharsets.UTF_8));
        Path command = root.resolve("Command" + suffix + ".java");
        String commandCode = "package " + PACKAGE + ";"
                + "@com.ultikits.ultitools.annotations.command.CmdExecutor(alias={\"probe\"},"
                + "description=\"unrelated description\", requireOp=false) public class Command" + suffix
                + " extends com.ultikits.ultitools.abstracts.command.BaseCommandExecutor {"
                + "public void handleHelp(org.bukkit.command.CommandSender sender) {}"
                + "@com.ultikits.ultitools.annotations.command.CmdMapping(format=\"\") public void fail" + suffix
                + "() {throw new IllegalStateException(\"command " + suffix + "\");} }";
        Files.write(command, commandCode.getBytes(StandardCharsets.UTF_8));
        Path bean = root.resolve("Bean" + suffix + ".java");
        Files.write(bean, ("package " + PACKAGE + "; public class Bean" + suffix
                + " extends com.ultikits.ultitools.ErrorReportAttributionTest.InheritedBean {}")
                .getBytes(StandardCharsets.UTF_8));
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", root.toString(), source.toString(), command.toString(),
                bean.toString());
        assertThat(result).as("compile the real module fixtures").isZero();
        Path jar = root.resolve("module.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String name : new String[] {"Module", "Command", "Bean"}) {
                String entry = PACKAGE.replace('.', '/') + "/" + name + suffix + ".class";
                out.putNextEntry(new JarEntry(entry));
                out.write(Files.readAllBytes(root.resolve(entry)));
                out.closeEntry();
            }
        }
        return jar;
    }

    private static ErrorReportCollector install(MockedStatic<UltiTools> framework, ClassLoader loader)
            throws ClassNotFoundException {
        ErrorReportCollector collector = new ErrorReportCollector();
        UltiTools instance = mock(UltiTools.class);
        PluginManager manager = mock(PluginManager.class);
        List<UltiToolsPlugin> modules = Arrays.asList(module(loader, "A"), module(loader, "B"));
        when(manager.getPluginList()).thenReturn(modules);
        when(instance.getPluginManager()).thenReturn(manager);
        when(instance.getErrorReportCollector()).thenReturn(collector);
        framework.when(UltiTools::getInstance).thenReturn(instance);
        return collector;
    }

    private static UltiToolsPlugin module(ClassLoader loader, String suffix) throws ClassNotFoundException {
        Class<? extends UltiToolsPlugin> type = loader.loadClass(PACKAGE + ".Module" + suffix)
                .asSubclass(UltiToolsPlugin.class);
        UltiToolsPlugin module = mock(type);
        assertThat(module.getClass()).isEqualTo(type);
        when(module.getPluginName()).thenReturn("Module-" + suffix);
        when(module.getIdentifyString()).thenReturn("module-" + suffix.toLowerCase(java.util.Locale.ROOT));
        return module;
    }

    private static void assertModule(ErrorReportCollector collector, String expected) throws Exception {
        assertThat(payload(collector).get("moduleName")).isEqualTo(new com.google.gson.JsonPrimitive(expected));
    }

    private static JsonObject payload(ErrorReportCollector collector) throws Exception {
        List<JsonObject> reports = payloads(collector);
        assertThat(reports).hasSize(1);
        return reports.get(0);
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Read the actual v2 payloads from the root-package loader test.
    private static List<JsonObject> payloads(ErrorReportCollector collector) throws Exception {
        Method drain = ErrorReportCollector.class.getDeclaredMethod("drainErrorReportPayloads", int.class);
        drain.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<JsonObject> reports = (List<JsonObject>) drain.invoke(collector, 10);
        return reports;
    }

    private static IllegalStateException failure(String method) {
        IllegalStateException failure = new IllegalStateException("boom");
        failure.setStackTrace(new StackTraceElement[] {new StackTraceElement("fixture", method, "Fixture.java", 7)});
        return failure;
    }
}
