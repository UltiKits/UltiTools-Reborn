package com.ultikits.ultitools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.bootstrap.BootstrapResult;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.manager.ModuleJarAdmission;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.utils.ModuleFileTransactions;
import com.ultikits.ultitools.utils.PluginInitiationUtils;

class BootstrapEnableHandoffTest {
    @TempDir Path temporary;

    @Test
    void enableConsumesTheOriginalStageIdentities() throws Exception {
        BootstrapResult prepared = mock(BootstrapResult.class);
        ModuleFileTransactions transactions = mock(ModuleFileTransactions.class);
        ModuleJarAdmission admission = new ModuleJarAdmission();
        ModuleClassLoader loader = mock(ModuleClassLoader.class);
        when(prepared.transactions()).thenReturn(transactions);
        when(prepared.admission()).thenReturn(admission);
        when(prepared.classLoader()).thenReturn(loader);
        UltiTools framework = mock(UltiTools.class, CALLS_REAL_METHODS);

        framework.consumeBootstrap(prepared);

        assertThat(field(framework, "moduleFileTransactions")).isSameAs(transactions);
        assertThat(field(framework, "moduleJarAdmission")).isSameAs(admission);
        assertThat(field(framework, "ultiToolsClassLoader")).isSameAs(loader);
    }

    @Test
    void onEnableReportsTheBootstrapFailureBeforeReadingAnyStageResources() throws Exception {
        BootstrapResult prepared = mock(BootstrapResult.class);
        IOException failure = new IOException("bootstrap failed before transactions existed");
        when(prepared.classPathFailure()).thenReturn(failure);
        UltiTools framework = mock(UltiTools.class, CALLS_REAL_METHODS);
        setField(framework, "bootstrap", prepared);
        try (MockedStatic<PluginInitiationUtils> lifecycle = mockStatic(PluginInitiationUtils.class)) {
            assertThatThrownBy(framework::onEnable).isInstanceOf(PluginModuleException.class).hasCause(failure);
            lifecycle.verify(PluginInitiationUtils::startAcceptingPanelMessages);
            verify(prepared, never()).transactions();
            verify(prepared, never()).admission();
            verify(prepared, never()).classLoader();
            assertThat(field(framework, "moduleFileTransactions")).isNull();
        }
    }

    @Test
    void dependencyLookupIgnoresTheFrameworkAndAbsentPlugins() throws Exception {
        UltiTools framework = mock(UltiTools.class, CALLS_REAL_METHODS);
        BootstrapResult prepared = mock(BootstrapResult.class);
        ModuleClassLoader loader = mock(ModuleClassLoader.class);
        ModuleJarAdmission admission = new ModuleJarAdmission();
        when(prepared.classLoader()).thenReturn(loader);
        setField(framework, "bootstrap", prepared);
        setField(framework, "moduleJarAdmission", admission);
        org.bukkit.plugin.PluginManager plugins = mock(org.bukkit.plugin.PluginManager.class);
        Plugin dependency = mock(Plugin.class);
        when(plugins.getPlugin("Dependency")).thenReturn(dependency);
        when(plugins.getPlugin("UltiTools")).thenReturn(framework);
        AtomicReference<Function<String, ClassLoader>> lookup = new AtomicReference<>();
        doAnswer(call -> {
            lookup.set(call.getArgument(1));
            return null;
        }).when(loader).addDeclaredDependencies(eq(admission), any());
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            framework.registerModuleDependencyLoaders();
            assertThat(lookup.get()).isNotNull();
            assertThat(lookup.get().apply("Dependency")).isSameAs(dependency.getClass().getClassLoader());
            assertThat(lookup.get().apply("UltiTools")).isNull();
            assertThat(lookup.get().apply("Absent")).isNull();
            verify(framework, never()).initPluginModules();
        }
    }

    @Test
    void moduleLoadingObservesTheSameTransactionsThatBootstrapApplied() throws Exception {
        BootstrapResult prepared = mock(BootstrapResult.class);
        ModuleFileTransactions transactions = mock(ModuleFileTransactions.class);
        ModuleClassLoader loader = mock(ModuleClassLoader.class);
        when(prepared.transactions()).thenReturn(transactions);
        when(prepared.admission()).thenReturn(new ModuleJarAdmission());
        when(prepared.classLoader()).thenReturn(loader);
        UltiTools framework = mock(UltiTools.class, CALLS_REAL_METHODS);
        when(framework.getDataFolder()).thenReturn(temporary.resolve("plugins/UltiTools").toFile());
        when(framework.getLogger()).thenReturn(Logger.getAnonymousLogger());
        framework.consumeBootstrap(prepared);
        try (MockedConstruction<PluginManager> managers = mockConstruction(PluginManager.class,
                (manager, context) -> when(manager.getPluginList()).thenReturn(Collections.emptyList()))) {
            framework.initPluginModules();
            verify(managers.constructed().get(0)).init(loader);
            verify(transactions).observeAfterLoad(eq(Collections.emptyList()), any());
            verify(transactions).flushReports(any(), any());
            assertThat(field(framework, "moduleFileTransactions")).isSameAs(transactions);
        }
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static Object field(UltiTools framework, String name) throws ReflectiveOperationException {
        Field field = UltiTools.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(framework);
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static void setField(UltiTools framework, String name, Object value) throws ReflectiveOperationException {
        Field field = UltiTools.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(framework, value);
    }
}
