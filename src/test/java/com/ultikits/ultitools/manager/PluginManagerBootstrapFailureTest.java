package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.ModuleClassLoader;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.bootstrap.BootstrapFixtures;

class PluginManagerBootstrapFailureTest {
    @TempDir Path modules;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"en", "zh"})
    void bootstrapFailedJar_isRefusedAndCountedFailed(String code) throws Exception {
        Path failed = BootstrapFixtures.jar(modules.resolve("failed.jar"), BootstrapFixtures.descriptor("Failed", null),
                Collections.singletonMap("fixture.FailedMain", BootstrapFixtures.mainClass("fixture.FailedMain")));
        Path good = BootstrapFixtures.jar(modules.resolve("good.jar"), BootstrapFixtures.descriptor("Good", null),
                Collections.singletonMap("fixture.GoodMain", BootstrapFixtures.mainClass("fixture.GoodMain")));
        ModuleJarAdmission admission = new ModuleJarAdmission();
        admission.inspect(failed.toFile());
        admission.inspect(good.toFile());
        admission.markBootstrapFailed(failed, new NoClassDefFoundError("fixture bootstrap failure"));
        List<String> requested = new ArrayList<>();
        ClassLoader parent = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                requested.add(name);
                return super.loadClass(name, resolve);
            }
        };
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        List<LogRecord> logs = new ArrayList<>();
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logs.add(record); }
            @Override public void flush() { /* No buffered output. */ }
            @Override public void close() { /* No owned resource. */ }
        });
        UltiTools framework = mock(UltiTools.class);
        try (var support = new com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport(code);
                var scope = com.ultikits.ultitools.text.internal.FrameworkMessages.overrideForTests(
                        support.messages(), java.util.Locale.forLanguageTag(code));
                ModuleClassLoader loader = ModuleClassLoader.create(new URL[] {failed.toUri().toURL(), good.toUri().toURL()}, parent);
                MockedStatic<UltiTools> tools = mockStatic(UltiTools.class);
                MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            tools.when(UltiTools::getInstance).thenReturn(framework);
            tools.when(UltiTools::getJavaPluginClassLoader).thenReturn(loader);
            bukkit.when(Bukkit::getLogger).thenReturn(logger);
            PluginManager manager = spy(new PluginManager(admission));
            doReturn(true).when(manager).register(org.mockito.ArgumentMatchers.<Class<? extends UltiToolsPlugin>>any());
            assertThat(manager.discoverModuleClasses(modules.toFile())).isTrue();
            manager.loadDiscoveredModules();
            assertThat(requested).contains("fixture.GoodMain").doesNotContain("fixture.FailedMain");
            assertThat(logs).anyMatch(record -> record.getMessage().equals("[UltiTools-API] Succeeded loaded 1, Failed 1."));
            assertThat(logs.stream().filter(record -> Level.SEVERE.equals(record.getLevel()))).singleElement()
                    .satisfies(record -> assertThat(record.getMessage()).isEqualTo(code.equals("en")
                            ? "[UltiTools-API] Module 'failed.jar' is not loaded because its bootstrap phase failed at this start; see the earlier bootstrap error."
                            : "[UltiTools-API] 模块 'failed.jar' 在本次启动的引导阶段失败，未加载；原因见前面的引导错误。"));
        }
    }
}
