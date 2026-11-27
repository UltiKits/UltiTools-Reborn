package com.ultikits.ultitools.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.ultikits.testfixtures.externalmessages.MessagesProbePlugin;
import com.ultikits.testfixtures.externalmessages.MessagesProbePlugin.ProbeBinding;
import com.ultikits.ultitools.UltiTools;
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
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.objenesis.ObjenesisStd;

class ExternalPluginMessagesTest {
    private static final String MAIN = MessagesProbePlugin.class.getName();
    private static final Map<String, String> CATALOGUE = Map.of(
            "messages/en.yml", "greeting: Hello from the plugin\n",
            "messages/zh.yml", "greeting: 插件向你问好\n");
    @TempDir Path temporary;
    private final List<URLClassLoader> loaders = new ArrayList<>();
    private MockedStatic<UltiTools> tools;
    private MockedStatic<Bukkit> bukkit;
    private TextEngine engine;
    private SimpleContainer parent;
    private PluginManager manager;
    private Messages frameworkMessages;
    private CommandManager commands;
    private ListenerManager listeners;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getAnonymousLogger();
        engine = new TextEngine(new ConfiguredLocaleResolver(() -> "auto", () -> "en", logger), logger);
        frameworkMessages = engine.messagesFor(MessageKey.FRAMEWORK_NAMESPACE);
        parent = new SimpleContainer();
        parent.registerSingleton(Messages.class.getName(), frameworkMessages);
        parent.registerSingleton(TextEngine.class.getName(), engine);
        parent.refresh();

        manager = new PluginManager();
        commands = mock(CommandManager.class);
        listeners = mock(ListenerManager.class);
        DependenceManagers dependencies = mock(DependenceManagers.class);
        when(dependencies.getContext()).thenReturn(parent);
        UltiTools framework = mock(UltiTools.class);
        when(framework.getPluginManager()).thenReturn(manager);
        when(framework.getDependenceManagers()).thenReturn(dependencies);
        when(framework.getCommandManager()).thenReturn(commands);
        when(framework.getListenerManager()).thenReturn(listeners);
        when(framework.getDataStore()).thenReturn(mock(DataStore.class, CALLS_REAL_METHODS));
        tools = mockStatic(UltiTools.class);
        tools.when(UltiTools::getInstance).thenReturn(framework);
        tools.when(UltiTools::getJavaPluginClassLoader).thenReturn(getClass().getClassLoader());
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getLogger).thenReturn(logger);
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var activeTools = tools; var activeBukkit = bukkit; var activeEngine = engine) {
            try {
                UltiToolsAPI.disconnectAll();
            } finally {
                parent.close();
            }
        } finally {
            UltiToolsAPI.reset();
            for (URLClassLoader loader : loaders) {
                loader.close();
            }
        }
    }

    @Test
    void ownCatalogueIsAvailableDuringScanAndRendersForEachRecipient() throws Exception {
        JavaPlugin plugin = plugin("GreetingProbe", CATALOGUE);
        Path overrides = Files.createDirectories(messagesDirectory(plugin));
        Files.writeString(overrides.resolve("zh.yml"), "greeting: 服主的问候\n");

        UltiToolsAPI.connect(plugin);

        ProbeBinding binding = binding(plugin);
        Messages messages = binding.messages();
        assertThat(messages.namespace()).isEqualTo("greetingprobe");
        assertThat(messages.key("greeting")).isEqualTo(MessageKey.of("greetingprobe", "greeting"));
        assertThat(binding.definedDuringScan()).isTrue();
        assertThat(renderFor(messages, Locale.US)).isEqualTo("Hello from the plugin");
        assertThat(renderFor(messages, Locale.SIMPLIFIED_CHINESE)).isEqualTo("服主的问候");
        assertThat(Files.readString(overrides.resolve("reference/zh.yml"))).contains("插件向你问好");
        assertThat(Files.readString(overrides.resolve("zh.yml"))).isEqualTo("greeting: 服主的问候\n");

        UltiToolsAPI.disconnect(plugin);

        assertThat(messages.isDefined(messages.key("greeting"), Locale.ENGLISH)).isFalse();
        assertThat(UltiToolsAPI.isConnected(plugin)).isFalse();
        assertThat(overrides.resolve("zh.yml")).exists();
    }

    @Test
    void jarWithoutTopLevelYmlKeepsTheParentInstanceAndCreatesNoDataDirectory() throws Exception {
        JavaPlugin plugin = plugin("NoCatalogueProbe", Map.of("messages/", "",
                "messages/en.yaml", "greeting: ignored\n", "messages/nested/en.yml", "greeting: ignored\n"));

        UltiToolsAPI.connect(plugin);

        assertThat(binding(plugin).messages()).isSameAs(frameworkMessages);
        UltiToolsAPI.reloadMessages(plugin);
        assertThat(plugin.getDataFolder()).doesNotExist();
    }

    @Test
    void reloadAndDuplicateConnectKeepTheSamePublishedView() throws Exception {
        JavaPlugin plugin = plugin("ReloadProbe", CATALOGUE);
        assertThatThrownBy(() -> UltiToolsAPI.reloadMessages(plugin)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Plugin ReloadProbe is not connected to UltiTools");
        UltiToolsAPI.connect(plugin);
        Messages messages = binding(plugin).messages();
        Path override = messagesDirectory(plugin).resolve("en.yml");
        Path reference = messagesDirectory(plugin).resolve("reference/en.yml");
        String referenceText = Files.readString(reference);
        Files.writeString(override, "greeting: Reloaded override\n");

        UltiToolsAPI.connect(plugin);

        assertThat(binding(plugin).messages()).isSameAs(messages);
        assertThat(renderFor(messages, Locale.US)).isEqualTo("Hello from the plugin");
        UltiToolsAPI.reloadMessages(plugin);
        assertThat(renderFor(messages, Locale.US)).isEqualTo("Reloaded override");
        assertThat(Files.readString(reference)).isEqualTo(referenceText);

        UltiToolsAPI.onPluginDisable(plugin);
        assertThat(messages.isDefined(messages.key("greeting"), Locale.ENGLISH)).isFalse();
        UltiToolsAPI.connect(plugin);
        assertThat(renderFor(binding(plugin).messages(), Locale.US)).isEqualTo("Reloaded override");
    }

    @ParameterizedTest
    @ValueSource(strings = {"plugin.yml", "paper-plugin.yml"})
    void readsSourceLanguageFromThePluginsOwnDescriptor(String descriptor) throws Exception {
        JavaPlugin plugin = plugin("FrenchProbe", Map.of(descriptor, "source-language: fr\n",
                "messages/fr.yml", "greeting: Bonjour\n"));

        UltiToolsAPI.connect(plugin);

        assertThat(renderFor(binding(plugin).messages(), Locale.JAPAN)).isEqualTo("Bonjour");
    }

    @Test
    void anAlreadyPublishedModuleWithTheExactSameNameRejectsTheExternalConnection() throws Exception {
        JavaPlugin plugin = plugin("MatchingProbe", CATALOGUE);
        var module = moduleCatalogue("MatchingProbe");
        engine.publish(module, this);

        assertThatThrownBy(() -> UltiToolsAPI.connect(plugin)).isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(PluginModuleException.class)
                .hasRootCauseMessage("External plugin 'MatchingProbe' cannot use message namespace 'matchingprobe': "
                        + "it is already used by module 'MatchingProbe'");
        assertThat(UltiToolsAPI.isConnected(plugin)).isFalse();
        assertThat(manager.findScopeForDataFolder(plugin.getDataFolder())).isNull();
        assertThat(plugin.getDataFolder()).doesNotExist();
        assertThat(renderFor(engine.messagesFor("matchingprobe"), Locale.US)).isEqualTo("Hello from the module");
    }

    @Test
    void externalNamespaceCannotBeTakenByAnotherExternalOrALaterModule() throws Exception {
        JavaPlugin owner = plugin("ConflictProbe", CATALOGUE);
        JavaPlugin other = plugin("CONFLICTPROBE", CATALOGUE);
        var preparedModule = moduleCatalogue("ConflictProbe");
        UltiToolsAPI.connect(owner);

        assertThatThrownBy(() -> UltiToolsAPI.connect(other)).isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("External plugin 'CONFLICTPROBE' cannot use message namespace 'conflictprobe': "
                        + "it is already used by external plugin 'ConflictProbe'");
        assertThatThrownBy(() -> moduleCatalogue("ConflictProbe")).isInstanceOf(PluginModuleException.class)
                .hasMessageContaining("already used by external plugin 'ConflictProbe'");
        assertThatThrownBy(() -> engine.publish(preparedModule, this)).isInstanceOf(PluginModuleException.class)
                .hasMessageContaining("already used by external plugin 'ConflictProbe'");
        assertThat(other.getDataFolder()).doesNotExist();
        assertThat(UltiToolsAPI.isConnected(other)).isFalse();
        assertThat(renderFor(binding(owner).messages(), Locale.US)).isEqualTo("Hello from the plugin");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rollsBackALateFailureWithoutWritingReferencesAndAllowsRetry(boolean existingOverride) throws Exception {
        JavaPlugin plugin = plugin("LateFailureProbe", CATALOGUE);
        Path messages = messagesDirectory(plugin);
        if (existingOverride) {
            Files.createDirectories(messages);
            Files.writeString(messages.resolve("en.yml"), "greeting: Keep this override\n");
        }
        LinkageError failure = new LinkageError("late registration failure");
        doAnswer(call -> {
            ExternalPluginAdapter adapter = call.getArgument(0);
            assertThat(adapter.getContext().getBean(ProbeBinding.class).definedDuringScan()).isTrue();
            assertThat(messages.resolve("reference")).doesNotExist();
            throw failure;
        }).doNothing().when(listeners).registerAllExternal(any());

        assertThatThrownBy(() -> UltiToolsAPI.connect(plugin)).isSameAs(failure);
        assertThat(UltiToolsAPI.isConnected(plugin)).isFalse();
        assertThat(manager.findScopeForDataFolder(plugin.getDataFolder())).isNull();
        assertThat(engine.messagesFor("latefailureprobe").isDefined(
                MessageKey.of("latefailureprobe", "greeting"), Locale.ENGLISH)).isFalse();
        assertThat(messages.resolve("reference")).doesNotExist();
        if (existingOverride) {
            assertThat(Files.readString(messages.resolve("en.yml"))).isEqualTo("greeting: Keep this override\n");
        } else {
            assertThat(plugin.getDataFolder()).doesNotExist();
        }

        UltiToolsAPI.connect(plugin);
        assertThat(messages.resolve("reference/en.yml")).exists();
    }

    @Test
    void frameworkShutdownWithdrawsAllCataloguesEvenWhenOneDisconnectFails() throws Exception {
        JavaPlugin first = plugin("FirstProbe", CATALOGUE);
        JavaPlugin second = plugin("SecondProbe", CATALOGUE);
        UltiToolsAPI.connect(first);
        UltiToolsAPI.connect(second);
        Messages firstMessages = binding(first).messages();
        Messages secondMessages = binding(second).messages();
        IllegalStateException error = new IllegalStateException("listener teardown failed");
        doThrow(error).when(listeners).unregisterAllExternal(first.getName());

        assertThatThrownBy(manager::close).isSameAs(error);
        assertThat(firstMessages.isDefined(firstMessages.key("greeting"), Locale.ENGLISH)).isFalse();
        assertThat(secondMessages.isDefined(secondMessages.key("greeting"), Locale.ENGLISH)).isFalse();
        assertThat(UltiToolsAPI.isConnected(first)).isFalse();
        assertThat(UltiToolsAPI.isConnected(second)).isFalse();
    }

    private TextEngine.Prepared moduleCatalogue(String name) throws IOException {
        Path source = temporary.resolve("module");
        Path messages = Files.createDirectories(source.resolve("messages"));
        Files.writeString(messages.resolve("en.yml"), "greeting: Hello from the module\n");
        return engine.prepare(MessageKey.namespaceFor(name), name, source.toUri().toURL(),
                temporary.resolve("module-overrides"), Locale.ENGLISH);
    }

    private static ProbeBinding binding(JavaPlugin plugin) {
        return UltiToolsAPI.getAdapter(plugin).getContext().getBean(ProbeBinding.class);
    }

    private static String renderFor(Messages messages, Locale locale) {
        Player recipient = mock(Player.class);
        when(recipient.locale()).thenReturn(locale);
        return PlainTextComponentSerializer.plainText().serialize(messages.render(messages.key("greeting"), recipient));
    }

    private static Path messagesDirectory(JavaPlugin plugin) {
        return plugin.getDataFolder().toPath().resolve("messages");
    }

    private JavaPlugin plugin(String name, Map<String, String> resources) throws Exception {
        Path jar = temporary.resolve(name + ".jar");
        String classEntry = MAIN.replace('.', '/') + ".class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
             var mainClass = MessagesProbePlugin.class.getResourceAsStream("/" + classEntry)) {
            output.putNextEntry(new JarEntry(classEntry));
            mainClass.transferTo(output);
            output.closeEntry();
            for (var resource : resources.entrySet()) {
                output.putNextEntry(new JarEntry(resource.getKey()));
                output.write(resource.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        class FixtureLoader extends URLClassLoader {
            FixtureLoader(URL source) {
                super(new URL[]{source}, ExternalPluginMessagesTest.class.getClassLoader());
            }

            Class<? extends JavaPlugin> definePlugin() throws ClassNotFoundException {
                return findClass(MAIN).asSubclass(JavaPlugin.class);
            }
        }
        FixtureLoader loader = new FixtureLoader(jar.toUri().toURL());
        loaders.add(loader);
        JavaPlugin plugin = new ObjenesisStd().newInstance(loader.definePlugin());
        PluginDescriptionFile description = new PluginDescriptionFile(name, "1.0.0", MAIN);
        PluginDescriptionFile legacy = resources.containsKey("paper-plugin.yml") ? null : description;
        plugin.init(mock(Server.class), legacy, temporary.resolve(name).toFile(), jar.toFile(), loader,
                description, Logger.getAnonymousLogger());
        return plugin;
    }
}
