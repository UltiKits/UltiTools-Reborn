package com.ultikits.ultitools.api;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.PluginManager;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ExternalPluginClassLoaderTest {
    private static final String PLUGIN = "IsolatedExternal";
    private static final String MAIN = "fixture.isolatedexternal.ExternalMain";
    private static final String ENTITY = "fixture.isolatedexternal.VisitorRecord";
    private static final ClassLoader FRAMEWORK_LOADER = ExternalPluginClassLoaderTest.class.getClassLoader();

    @TempDir
    Path tempDir;

    @Test
    void connectRegistersTheEntityDefinedInTheExternalJar() throws Exception {
        URL jar = createPluginJar();
        assertThatThrownBy(() -> FRAMEWORK_LOADER.loadClass(ENTITY)).isInstanceOf(ClassNotFoundException.class);
        try (URLClassLoader owner = new URLClassLoader(new URL[]{jar}, FRAMEWORK_LOADER);
             MockedStatic<UltiTools> ultiTools = mockStatic(UltiTools.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            JavaPlugin plugin = mockExternalPlugin(owner);
            Class<?> entity = owner.loadClass(ENTITY);
            assertThat(plugin.getClass().getProtectionDomain().getCodeSource().getLocation()).isEqualTo(jar);
            assertThat(entity.getClassLoader()).isSameAs(owner);
            UltiTools framework = framework(new PluginManager());
            ultiTools.when(UltiTools::getInstance).thenReturn(framework);
            ultiTools.when(UltiTools::getJavaPluginClassLoader).thenReturn(FRAMEWORK_LOADER);
            bukkit.when(Bukkit::getLogger).thenReturn(Logger.getLogger(getClass().getName()));

            UltiToolsAPI.connect(plugin);
            try {
                assertThat(UltiToolsAPI.getAdapter(plugin).getDataScope().getOwnedEntities()).containsExactly(entity);
            } finally {
                UltiToolsAPI.disconnect(plugin);
            }
        }
    }

    @ParameterizedTest(name = "same-named class visible: {0}")
    @ValueSource(booleans = {false, true})
    void connectRefusesAnOwnerLoaderThatCannotResolveItsMainClass(boolean duplicateMain) throws Exception {
        URL jar = createPluginJar();
        try (RedirectableMainLoader owner = new RedirectableMainLoader(jar);
             URLClassLoader alternate = new URLClassLoader(new URL[]{jar}, FRAMEWORK_LOADER);
             MockedStatic<UltiTools> ultiTools = mockStatic(UltiTools.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            JavaPlugin plugin = mockExternalPlugin(owner);
            PluginManager manager = new PluginManager();
            UltiTools framework = framework(manager);
            ultiTools.when(UltiTools::getInstance).thenReturn(framework);
            bukkit.when(Bukkit::getLogger).thenReturn(Logger.getLogger(getClass().getName()));
            owner.mainLookupOverride = duplicateMain ? alternate : FRAMEWORK_LOADER;
            if (duplicateMain) {
                assertThat(owner.loadClass(MAIN)).isNotSameAs(plugin.getClass());
            }

            assertThatThrownBy(() -> UltiToolsAPI.connect(plugin))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Failed to connect plugin " + PLUGIN + " to UltiTools")
                    .cause().isInstanceOf(PluginModuleException.class)
                    .hasMessageContaining("the selected class loader cannot resolve main class '" + MAIN + "'");
            assertThat(UltiToolsAPI.getAdapter(plugin)).isNull();
            assertThat(manager.findScopeForDataFolder(plugin.getDataFolder())).isNull();
            assertThat(manager.getConnectedExternalScanPackages()).isEmpty();

            owner.mainLookupOverride = null;
            UltiToolsAPI.connect(plugin);
            try {
                assertThat(UltiToolsAPI.isConnected(plugin)).isTrue();
            } finally {
                UltiToolsAPI.disconnect(plugin);
            }
        }
    }

    private URL createPluginJar() throws Exception {
        Path jar = tempDir.resolve("isolated-external.jar");
        ByteBuddy byteBuddy = new ByteBuddy();
        byteBuddy.subclass(JavaPlugin.class).name(MAIN).make()
                .include(byteBuddy.subclass(Object.class).name(ENTITY)
                        .annotateType(AnnotationDescription.Builder.ofType(Table.class)
                                .define("value", "visitors").build())
                        .make())
                .toJar(jar.toFile());
        return jar.toUri().toURL();
    }

    private JavaPlugin mockExternalPlugin(ClassLoader owner) throws ClassNotFoundException {
        JavaPlugin plugin = mock(owner.loadClass(MAIN).asSubclass(JavaPlugin.class));
        when(plugin.getName()).thenReturn(PLUGIN);
        when(plugin.getDescription()).thenReturn(new PluginDescriptionFile(PLUGIN, "1.0.0", MAIN));
        when(plugin.getDataFolder()).thenReturn(tempDir.resolve(PLUGIN).toFile());
        return plugin;
    }

    private static UltiTools framework(PluginManager manager) {
        UltiTools framework = mock(UltiTools.class);
        DependenceManagers dependencies = mock(DependenceManagers.class);
        when(dependencies.getContext()).thenReturn(new SimpleContainer());
        when(framework.getDependenceManagers()).thenReturn(dependencies);
        when(framework.getPluginManager()).thenReturn(manager);
        when(framework.getCommandManager()).thenReturn(mock(CommandManager.class));
        when(framework.getListenerManager()).thenReturn(mock(ListenerManager.class));
        when(framework.getDataStore()).thenReturn(mock(DataStore.class, CALLS_REAL_METHODS));
        return framework;
    }

    private static final class RedirectableMainLoader extends URLClassLoader {
        private ClassLoader mainLookupOverride;

        private RedirectableMainLoader(URL jar) {
            super(new URL[]{jar}, FRAMEWORK_LOADER);
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            if (MAIN.equals(name) && mainLookupOverride != null) {
                return mainLookupOverride.loadClass(name);
            }
            return super.loadClass(name);
        }
    }
}
