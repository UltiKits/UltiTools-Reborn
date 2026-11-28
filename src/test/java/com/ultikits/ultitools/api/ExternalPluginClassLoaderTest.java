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
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.internal.ConfiguredLocaleResolver;
import com.ultikits.ultitools.text.internal.TextEngine;
import com.ultikits.ultitools.utils.ModuleScanDiagnostics;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
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
    private static final MessageKey GREETING = MessageKey.of(MessageKey.namespaceFor(PLUGIN), "greeting");
    private static final ClassLoader FRAMEWORK_LOADER = ExternalPluginClassLoaderTest.class.getClassLoader();

    @TempDir
    Path tempDir;

    @Test
    void connectRegistersTheEntityAndCatalogueFromTheExternalJar() throws Exception {
        URL jar = createPluginJar();
        assertThatThrownBy(() -> FRAMEWORK_LOADER.loadClass(ENTITY)).isInstanceOf(ClassNotFoundException.class);
        try (URLClassLoader owner = new URLClassLoader(new URL[]{jar}, FRAMEWORK_LOADER);
             TextEngine engine = textEngine();
             MockedStatic<UltiTools> ultiTools = mockStatic(UltiTools.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            JavaPlugin plugin = mockExternalPlugin(owner);
            Class<?> entity = owner.loadClass(ENTITY);
            assertThat(plugin.getClass().getProtectionDomain().getCodeSource().getLocation()).isEqualTo(jar);
            assertThat(entity.getClassLoader()).isSameAs(owner);
            PluginManager manager = new PluginManager();
            UltiTools framework = framework(manager, engine);
            ultiTools.when(UltiTools::getInstance).thenReturn(framework);
            ultiTools.when(UltiTools::getJavaPluginClassLoader).thenReturn(FRAMEWORK_LOADER);
            bukkit.when(Bukkit::getLogger).thenReturn(Logger.getLogger(getClass().getName()));

            List<String> scanSummaries = new ArrayList<>();
            Handler capture = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    if (Level.SEVERE.equals(record.getLevel())
                            && record.getMessage().matches("(?s).*\\bskipped \\d+ class\\(es\\).*")) {
                        scanSummaries.add(record.getMessage());
                    }
                }

                @Override
                public void flush() {
                    // Nothing buffered.
                }

                @Override
                public void close() {
                    // No resources to release.
                }
            };
            Logger diagnostics = Logger.getLogger(ModuleScanDiagnostics.class.getName());
            diagnostics.addHandler(capture);
            try {
                UltiToolsAPI.connect(plugin);
            } finally {
                diagnostics.removeHandler(capture);
            }
            try {
                ExternalPluginAdapter adapter = UltiToolsAPI.getAdapter(plugin);
                assertThat(adapter.getDataScope().getOwnedEntities()).containsExactly(entity);
                assertThat(adapter.getContext().getBean(Messages.class).isDefined(GREETING, Locale.ENGLISH)).isTrue();
                assertThat(scanSummaries).as("skipped-class summaries while connecting the external JAR").isEmpty();
            } finally {
                UltiToolsAPI.disconnect(plugin);
            }
            assertThat(manager.findScopeForDataFolder(plugin.getDataFolder())).isNull();
            assertThat(engine.messagesFor(GREETING.namespace()).isDefined(GREETING, Locale.ENGLISH)).isFalse();
            assertThat(plugin.getDataFolder().toPath().resolve("messages/reference/en.yml")).exists();
        }
    }

    @Test
    void catalogueConflictDoesNotRegisterTheExternalEntity() throws Exception {
        URL jar = createPluginJar();
        try (URLClassLoader owner = new URLClassLoader(new URL[]{jar}, FRAMEWORK_LOADER);
             TextEngine engine = textEngine();
             MockedStatic<UltiTools> ultiTools = mockStatic(UltiTools.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            JavaPlugin plugin = mockExternalPlugin(owner);
            Class<?> entity = owner.loadClass(ENTITY);
            PluginManager manager = new PluginManager();
            UltiTools framework = framework(manager, engine);
            ultiTools.when(UltiTools::getInstance).thenReturn(framework);
            bukkit.when(Bukkit::getLogger).thenReturn(Logger.getLogger(getClass().getName()));
            engine.publish(engine.prepare(GREETING.namespace(), PLUGIN, jar,
                    tempDir.resolve("module-overrides"), Locale.ENGLISH), this);

            assertThatThrownBy(() -> UltiToolsAPI.connect(plugin))
                    .isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(PluginModuleException.class)
                    .hasRootCauseMessage("External plugin '" + PLUGIN + "' cannot use message namespace '"
                            + GREETING.namespace() + "': it is already used by module '" + PLUGIN + "'");
            assertThat(UltiToolsAPI.isConnected(plugin)).isFalse();
            assertThat(manager.findScopeForDataFolder(plugin.getDataFolder())).isNull();
            assertThat(manager.findOwningPlugin(entity)).isNull();
            assertThat(manager.getConnectedExternalScanPackages()).isEmpty();
            assertThat(plugin.getDataFolder()).doesNotExist();
            assertThat(engine.messagesFor(GREETING.namespace()).isDefined(GREETING, Locale.ENGLISH)).isTrue();
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
        try (var contents = FileSystems.newFileSystem(jar)) {
            Path messages = Files.createDirectory(contents.getPath("/messages"));
            Files.writeString(messages.resolve("en.yml"), "greeting: Hello from the isolated plugin\n");
        }
        return jar.toUri().toURL();
    }

    private JavaPlugin mockExternalPlugin(ClassLoader owner) throws ClassNotFoundException {
        JavaPlugin plugin = mock(owner.loadClass(MAIN).asSubclass(JavaPlugin.class));
        when(plugin.getName()).thenReturn(PLUGIN);
        when(plugin.getDescription()).thenReturn(new PluginDescriptionFile(PLUGIN, "1.0.0", MAIN));
        when(plugin.getDataFolder()).thenReturn(tempDir.resolve(PLUGIN).toFile());
        return plugin;
    }

    private static TextEngine textEngine() {
        Logger logger = Logger.getLogger(ExternalPluginClassLoaderTest.class.getName());
        return new TextEngine(new ConfiguredLocaleResolver(() -> "en", () -> "en", logger), logger);
    }

    private static UltiTools framework(PluginManager manager, TextEngine engine) {
        SimpleContainer parent = new SimpleContainer();
        parent.registerSingleton(TextEngine.class.getName(), engine);
        parent.refresh();
        UltiTools framework = mock(UltiTools.class);
        DependenceManagers dependencies = mock(DependenceManagers.class);
        when(dependencies.getContext()).thenReturn(parent);
        when(framework.getDependenceManagers()).thenReturn(dependencies);
        when(framework.getPluginManager()).thenReturn(manager);
        when(framework.getCommandManager()).thenReturn(mock(CommandManager.class));
        when(framework.getListenerManager()).thenReturn(mock(ListenerManager.class));
        when(framework.getDataStore()).thenReturn(mock(DataStore.class, CALLS_REAL_METHODS));
        return framework;
    }
}
