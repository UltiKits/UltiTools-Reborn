package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.ModuleClassLoader;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.bootstrap.BootstrapFixtures;

class PluginManagerBootstrapFailureTest {
    @TempDir Path modules;

    @Test
    void bootstrapFailedJar_isRefusedAndCountedFailed() throws Exception {
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
        when(framework.i18n(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(framework.i18n("module.bootstrap.failed")).thenReturn(
                "Module '%s' is not loaded because its bootstrap phase failed at this start; see the earlier bootstrap error.");
        try (ModuleClassLoader loader = ModuleClassLoader.create(new URL[] {failed.toUri().toURL(), good.toUri().toURL()}, parent);
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
                    .satisfies(record -> assertThat(record.getMessage()).contains("failed.jar", "bootstrap phase failed"));
        }
    }
}
