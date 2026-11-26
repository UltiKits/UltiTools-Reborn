package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.testfixtures.textwiring.TextFixtureModule;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.manager.ManagerKeys;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.LocaleResolver;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.internal.ConfiguredLocaleResolver;
import com.ultikits.ultitools.text.internal.TextEngine;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import org.junit.jupiter.api.AfterEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.nio.file.Path;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UltiToolsPluginTextWiringTest {
    @TempDir Path temporary;
    private TextEngine engine;
    private SimpleContainer root;
    private YamlConfiguration settings;
    private Logger frameworkLogger;
    private final List<URLClassLoader> loaders = new ArrayList<>();
    private final List<SimpleContainer> registeredContexts = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (SimpleContainer context : registeredContexts) {
            context.close();
        }
        if (engine != null) {
            engine.close();
            root.close();
        }
        for (URLClassLoader loader : loaders) {
            loader.close();
        }
        if (MockBukkit.isMocked()) {
            MockBukkitHelper.safeUnmock();
        }
    }

    private void setUpModuleEnvironment() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        settings = new YamlConfiguration();
        settings.set("language", "auto");
        settings.set("fallback-language", "zh");
        frameworkLogger = Logger.getAnonymousLogger();
        ConfiguredLocaleResolver resolver = ConfiguredLocaleResolver.forConfig(() -> settings, frameworkLogger);
        engine = new TextEngine(resolver, frameworkLogger,
                UltiTools.class.getProtectionDomain().getCodeSource().getLocation(), temporary.resolve("messages"));
        root = new SimpleContainer();
        root.registerSingleton("textEngine", engine);
        root.registerSingleton(LocaleResolver.class.getName(), resolver);
        root.registerSingleton(Messages.class.getName(), engine.messagesFor("ultitools"));
        DependenceManagers managers = mock(DependenceManagers.class);
        doReturn(root).when(managers).getContext();
        ConfigManager configs = mock(ConfigManager.class);
        CommandManager commands = mock(CommandManager.class);
        ListenerManager listeners = mock(ListenerManager.class);
        TestHelper.mockUltiToolsInstance(core -> {
            doReturn(settings).when(core).getConfig();
            doReturn(temporary.toFile()).when(core).getDataFolder();
            doReturn(frameworkLogger).when(core).getLogger();
            doReturn(managers).when(core).getDependenceManagers();
            doReturn(configs).when(core).getConfigManager();
            doReturn(commands).when(core).getCommandManager();
            doReturn(listeners).when(core).getListenerManager();
            doReturn(mock(DataStore.class, CALLS_REAL_METHODS)).when(core).getDataStore();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"zh", "en"})
    void scaffoldMessagesOnlyModuleLoadsAndReloadsWithoutLegacyLanguageWarnings(String fallback) throws Exception {
        setUpModuleEnvironment();
        settings.set("fallback-language", fallback);
        // Capture both the module facade's framework logger and the reload logger, from construction onward.
        try (CapturedLogs logs = new CapturedLogs(frameworkLogger)) {
            UltiToolsPlugin plugin = scaffoldModule();
            plugin.publishMessageCatalogue();
            assertThat(plugin.reloadWithReport().isPartial()).isFalse();
            assertThat(logs.records).extracting(LogRecord::getMessage)
                    .noneMatch(text -> text.contains("has no loadable language file"));
            assertThat(logs.at(Level.WARNING)).noneMatch(text -> text.contains("lang/"));
            Path data = Path.of(plugin.getResourceFolderPath());
            assertThat(data.resolve("lang")).doesNotExist();
            assertThat(data.resolve("messages/reference/en.yml")).exists();
            assertThat(data.resolve("messages/reference/zh.yml")).exists();
            assertThat(logs.at(Level.INFO)).anyMatch(text -> text.contains("ScaffoldProbe"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"zh", "en"})
    void successfulReloadLogsTheCatalogueLineInTheFallbackLanguage(String fallback) throws Exception {
        setUpReloadLanguages(fallback);
        String runningLanguage = settings.getString("language");
        Files.writeString(temporary.resolve("config.yml"), "language: " + runningLanguage + "\n");
        try (CapturedLogs logs = new CapturedLogs(frameworkLogger)) {
            ReloadReport report = scaffoldModule().reloadWithReport();
            assertThat(report.isPartial()).isFalse();
            assertThat(report.getPartialReasons()).isEmpty();
            assertThat(logs.at(Level.INFO)).containsExactly(renderFallback(ManagerKeys.RELOAD_MODULE_DONE,
                    Arg.string("module", "ScaffoldProbe")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"zh", "en"})
    void partialReloadLogsTheCatalogueLineAndNamesTheUnappliedLanguage(String fallback) throws Exception {
        setUpReloadLanguages(fallback);
        // The raw running value must be reported, not its resolved fallback locale.
        settings.set("language", "auto");
        Files.writeString(temporary.resolve("config.yml"), "language: " + fallback + "\n");
        String pending = renderFallback(ManagerKeys.RELOAD_LANGUAGE_PENDING,
                Arg.string("from", "auto"), Arg.string("to", fallback));
        try (CapturedLogs logs = new CapturedLogs(frameworkLogger)) {
            ReloadReport report = scaffoldModule().reloadWithReport();
            assertThat(report.isPartial()).isTrue();
            assertThat(report.getPartialReasons()).containsExactly(pending);
            assertThat(logs.at(Level.WARNING)).contains(renderFallback(ManagerKeys.RELOAD_PARTIAL,
                    Arg.string("module", "ScaffoldProbe"), Arg.string("reasons", pending)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"zh", "en"})
    void failedReloadLogsTheCatalogueLineAndPropagatesTheFailure(String fallback) throws Exception {
        setUpReloadLanguages(fallback);
        UltiToolsPlugin failing = spy(scaffoldModule());
        IllegalStateException failure = new IllegalStateException("hook refused reload");
        doThrow(failure).when(failing).onReload();
        try (CapturedLogs logs = new CapturedLogs(frameworkLogger)) {
            assertThat(assertThrows(IllegalStateException.class, failing::reloadWithReport)).isSameAs(failure);
            assertThat(logs.at(Level.SEVERE)).containsExactly(renderFallback(ManagerKeys.RELOAD_MODULE_FAILED,
                    Arg.string("module", "ScaffoldProbe"), Arg.string("error", failure.getMessage())));
            assertThat(logs.records).filteredOn(record -> record.getLevel() == Level.SEVERE)
                    .extracting(LogRecord::getThrown).containsExactly(failure);
        }
    }

    private void setUpReloadLanguages(String fallback) {
        setUpModuleEnvironment();
        // Make the player override differ, so using it for console logs is observable in both directions.
        settings.set("language", "en".equals(fallback) ? "zh" : "en");
        settings.set("fallback-language", fallback);
    }

    private String renderFallback(MessageKey key, Arg... args) {
        Component rendered = root.getBean(Messages.class).render(Text.of(key, args), engine.fallback());
        String result = PlainTextComponentSerializer.plainText().serialize(rendered);
        assertThat(result).isNotEqualTo(key.toString());
        return result;
    }

    @Test
    void rejectedCandidate_neverPublishesOrWritesReferenceCopies() throws Exception {
        setUpModuleEnvironment();
        UltiToolsPlugin rejected = module("Rejected", "greeting: Rejected\n");
        assertThat(rejected.getPluginName()).isEqualTo("Rejected");
        assertThat(shared("rejected:greeting")).isEqualTo("rejected:greeting");
        assertThat(temporary.resolve("pluginConfig/Rejected/messages/reference")).doesNotExist();
    }

    @Test
    void acceptedModule_injectsOwnMessagesAndWithdraws() throws Exception {
        setUpModuleEnvironment();
        UltiToolsPlugin module = module("Sample", "greeting: Hello\n");
        module.publishMessageCatalogue();
        assertThat(temporary.resolve("pluginConfig/Sample/messages/reference/en.yml")).exists();
        SimpleContainer child = new SimpleContainer();
        child.setParent(root);
        module.setContext(child);
        MessageBean bean = new MessageBean();
        child.registerSingleton("messageBean", bean);
        assertThat(bean.messages.namespace()).isEqualTo("sample");
        assertThat(child.getBean(Messages.class)).isSameAs(bean.messages);
        assertThat(root.getBean(Messages.class).namespace()).isEqualTo("ultitools");
        assertThat(shared("sample:greeting")).isEqualTo("Hello");
        module.unregisterSelf();
        assertThat(shared("sample:greeting")).isEqualTo("sample:greeting");
        child.close();
    }

    @Test
    void failedContainerAssembly_withdrawsThePublishedCatalogue() throws Exception {
        setUpModuleEnvironment();
        UltiToolsPlugin incoming = spy(module("AssemblyFailure", "greeting: Failed\n"));
        doThrow(new IllegalStateException("assembly failed")).when(incoming).setContext(any(SimpleContainer.class));
        assertThat(register(new PluginManager(), incoming)).isFalse();
        verify(incoming).setContext(any(SimpleContainer.class));
        assertThat(shared("assemblyfailure:greeting")).isEqualTo("assemblyfailure:greeting");
    }

    @Test
    void declinedRegistration_withdrawsThePublishedCatalogue() throws Exception {
        setUpModuleEnvironment();
        UltiToolsPlugin incoming = spy(module("Declined", "greeting: Failed\n"));
        doReturn(false).when(incoming).registerSelf();
        assertThat(register(new PluginManager(), incoming)).isFalse();
        verify(incoming).registerSelf();
        assertThat(shared("declined:greeting")).isEqualTo("declined:greeting");
    }

    @Test
    void failedReplacement_restoresTheRunningCopyAndItsOwnership() throws Exception {
        setUpModuleEnvironment();
        PluginManager manager = new PluginManager();
        UltiToolsPlugin older = module("Replacement", "greeting: Old\n", "1.0.0");
        assertThat(register(manager, older)).isTrue();
        assertThat(shared("replacement:greeting")).isEqualTo("Old");
        UltiToolsPlugin newer = spy(module("Replacement", "greeting: New\n", "2.0.0"));
        doReturn(false).when(newer).registerSelf();
        assertThat(register(manager, newer)).isFalse();
        verify(newer).registerSelf();
        assertThat(manager.getPluginList()).containsExactly(older);
        assertThat(shared("replacement:greeting")).isEqualTo("Old");
        older.unregisterSelf();
        assertThat(shared("replacement:greeting")).isEqualTo("replacement:greeting");
    }

    @Test
    void throwingRegistration_alsoWithdrawsThePublishedCatalogue() throws Exception {
        setUpModuleEnvironment();
        UltiToolsPlugin incoming = spy(module("Thrown", "greeting: Failed\n"));
        doThrow(new IllegalStateException("registration failed")).when(incoming).registerSelf();
        assertThat(register(new PluginManager(), incoming)).isFalse();
        verify(incoming).registerSelf();
        assertThat(shared("thrown:greeting")).isEqualTo("thrown:greeting");
    }

    private boolean register(PluginManager manager, UltiToolsPlugin module) {
        UltiTools core = UltiTools.getInstance();
        try (var singleton = mockStatic(UltiTools.class, CALLS_REAL_METHODS)) {
            singleton.when(UltiTools::getInstance).thenReturn(core);
            singleton.when(UltiTools::getPluginVersion).thenReturn(700);
            boolean registered = manager.register(module);
            if (module.getContext() != null) {
                registeredContexts.add(module.getContext());
            }
            return registered;
        }
    }

    public static class MessageBean {
        @Autowired Messages messages;
    }

    private UltiToolsPlugin module(String name, String catalogue) throws Exception {
        return module(name, catalogue, "1.0.0");
    }

    private UltiToolsPlugin scaffoldModule() throws Exception {
        return module("ScaffoldProbe", "greeting: Hello\n", "1.0.0", "greeting: 你好\n");
    }

    private UltiToolsPlugin module(String name, String catalogue, String version) throws Exception {
        return module(name, catalogue, version, null);
    }

    private UltiToolsPlugin module(String name, String catalogue, String version, String chinese) throws Exception {
        String className = TextFixtureModule.class.getName();
        String resource = className.replace('.', '/') + ".class";
        Path jar = temporary.resolve(name + "-" + loaders.size() + ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
                InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            output.putNextEntry(new JarEntry(resource));
            output.write(input.readAllBytes());
            output.closeEntry();
            output.putNextEntry(new JarEntry("plugin.yml"));
            output.write(("name: '" + name + "'\nversion: " + version + "\napi-version: 700\nmain: " + className + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry("messages/en.yml"));
            output.write(catalogue.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            if (chinese != null) {
                output.putNextEntry(new JarEntry("messages/zh.yml"));
                output.write(chinese.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
                if (!requested.equals(className)) {
                    return super.loadClass(requested, resolve);
                }
                synchronized (getClassLoadingLock(requested)) {
                    Class<?> type = findLoadedClass(requested);
                    if (type == null) {
                        type = findClass(requested);
                    }
                    if (resolve) {
                        resolveClass(type);
                    }
                    return type;
                }
            }
        };
        loaders.add(loader);
        return (UltiToolsPlugin) loader.loadClass(className).getConstructor().newInstance();
    }

    private String shared(String key) {
        return PlainTextComponentSerializer.plainText().serialize(
                GlobalTranslator.render(Component.translatable(key).fallback(key), Locale.ENGLISH));
    }

    private static final class CapturedLogs extends Handler implements AutoCloseable {
        private final Logger module = Logger.getLogger(UltiToolsPlugin.class.getName());
        private final Logger framework;
        private final List<LogRecord> records = new ArrayList<>();
        private final Level moduleLevel;
        private final Level frameworkLevel;

        CapturedLogs(Logger framework) {
            this.framework = framework;
            moduleLevel = module.getLevel();
            frameworkLevel = framework.getLevel();
            module.setLevel(Level.ALL);
            framework.setLevel(Level.ALL);
            module.addHandler(this);
            framework.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        List<String> at(Level level) {
            return records.stream().filter(record -> record.getLevel().equals(level)).map(LogRecord::getMessage).toList();
        }

        @Override
        public void flush() {
            // Records are captured synchronously in memory.
        }

        @Override
        public void close() {
            module.removeHandler(this);
            framework.removeHandler(this);
            module.setLevel(moduleLevel);
            framework.setLevel(frameworkLevel);
        }
    }
}
