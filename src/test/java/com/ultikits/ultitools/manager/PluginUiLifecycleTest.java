package com.ultikits.ultitools.manager;

import com.ultikits.testfixtures.uiwiring.UiWiringBean;
import com.ultikits.testfixtures.uiwiring.UiWiringModule;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.item.ItemRenderer;
import com.ultikits.ultitools.scheduling.ManualScheduler;
import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.BindingMode;
import com.ultikits.ultitools.ui.CloseReason;
import com.ultikits.ultitools.ui.Confirm;
import com.ultikits.ultitools.ui.ContainerSpec;
import com.ultikits.ultitools.ui.Disposition;
import com.ultikits.ultitools.ui.EndOutcome;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridSession;
import com.ultikits.ultitools.ui.RecordingScheduler;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.StoreAnswer;
import com.ultikits.ultitools.ui.UiResult;
import com.ultikits.ultitools.ui.UiService;
import com.ultikits.ultitools.ui.UiSession;
import com.ultikits.ultitools.ui.container.UiUnloadTestAccess;
import com.ultikits.ultitools.ui.dialog.DialogPresenter;
import com.ultikits.ultitools.utils.ReflectionUtil;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@Timeout(value = 25, unit = TimeUnit.SECONDS)
class PluginUiLifecycleTest {
    @TempDir Path temporary;
    private ServerMock server;
    private ManualScheduler.Engine clock;
    private SimpleContainer parent;
    private UiManager ui;
    private PluginManager plugins;
    private TaskManager tasks;
    private MockedStatic<UltiTools> singleton;
    private final List<PluginManagerFirstAssemblyTest.FixtureJarClassLoader> loaders = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        clock = new ManualScheduler.Engine();
        parent = new SimpleContainer();
        UltiTools framework = mock(UltiTools.class);
        DependenceManagers dependencies = mock(DependenceManagers.class);
        Messages messages = mock(Messages.class);
        when(messages.render(any(Text.class), any(Audience.class))).thenReturn(Component.text("UI"));
        ui = UiManager.create(new RecordingScheduler(clock.scheduler("FrameworkUi")), messages, mock(ItemRenderer.class),
                Logger.getAnonymousLogger(), Thread.currentThread(), mock(DialogPresenter.class));
        when(dependencies.getContext()).thenReturn(parent);
        when(dependencies.getUiManager()).thenReturn(ui);
        when(framework.getDependenceManagers()).thenReturn(dependencies);
        when(framework.getDataFolder()).thenReturn(temporary.toFile());
        when(framework.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(framework.getDataStore()).thenReturn(mock(DataStore.class, CALLS_REAL_METHODS));
        when(framework.getConfigManager()).thenReturn(mock(ConfigManager.class));
        when(framework.getCommandManager()).thenReturn(mock(CommandManager.class));
        when(framework.getListenerManager()).thenReturn(mock(ListenerManager.class));
        when(framework.getConfig()).thenReturn(new YamlConfiguration());
        singleton = mockStatic(UltiTools.class, CALLS_REAL_METHODS);
        singleton.when(UltiTools::getInstance).thenReturn(framework);
        singleton.when(UltiTools::getPluginVersion).thenReturn(700);
        plugins = new PluginManager();
        tasks = spy(new TaskManager(MockBukkit.createMockPlugin()));
        ReflectionUtil.setFieldValue(plugins, "taskManager", tasks);
        ReflectionUtil.setFieldValue(plugins, "uiManager", ui);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            ui.close();
            parent.close();
            for (PluginManagerFirstAssemblyTest.FixtureJarClassLoader loader : loaders) { loader.close(); }
            clock.close();
        } finally {
            if (singleton != null) { singleton.close(); }
            MockBukkit.unmock();
        }
    }

    @Test
    void realAssemblyInjectsBeforePostConstructIntoModuleAndScannedBean() throws Exception {
        UltiToolsPlugin module = load(null, false);
        UiService service = injected(module);
        assertThat(service.owner()).isEqualTo("UiWiring");
        assertThat(ui.serviceFor(module)).isSameAs(service);
        Object bean = module.getContext().getBean(module.getClass().getClassLoader().loadClass(UiWiringBean.class.getName()));
        assertThat(bean.getClass().getField("ui").get(bean)).isSameAs(service);
        assertThat(bean.getClass().getField("ownerAtConstruction").get(bean)).isEqualTo("UiWiring");
        assertThat((List<?>) module.getClass().getField("events").get(module))
                .extracting(Object::toString).containsExactly("injected:UiWiring");
        plugins.unregister(module);
    }

    @Test
    void unregisterEndsBothScreensAndCommitBeforeTasksHookAndPreDestroyWithoutTouchingOtherCopies() throws Exception {
        UltiToolsPlugin alpha = load(null, false);
        UiService service = injected(alpha);
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");
        List<String> order = new ArrayList<>();
        UiSession<?> dialog = service.show(alice, Confirm.builder(Text.plain("Dialog")).build());
        SlotContainer container = committed(service, bob, CompletableFuture.completedFuture(StoreAnswer.written()));
        GridSession grid = service.show(bob, bound(container));
        dialog.result().thenAccept(result -> order.add("dialog"));
        grid.result().thenAccept(result -> {
            assertThat(container.end().toCompletableFuture()).isCompletedWithValue(EndOutcome.COMMITTED);
            order.add("grid");
        });
        UiSession<?> beta = copySession("beta", "Charlie");
        UiSession<?> sameName = copySession("UiWiring", "Dave");
        alpha.getClass().getField("hookObserver").set(alpha, (Runnable) () -> order.add("hook"));
        alpha.getClass().getField("destroyObserver").set(alpha, (Runnable) () -> order.add("destroy"));
        doAnswer(call -> { order.add("tasks"); return call.callRealMethod(); }).when(tasks).cancelAll(alpha);
        clock.advanceTicks(1);
        plugins.unregister(alpha);
        assertThat(dialog.result().toCompletableFuture()).isCompletedWithValue(new UiResult.Closed<>(CloseReason.OWNER_UNLOADED));
        assertThat(grid.result().toCompletableFuture()).isCompletedWithValue(new UiResult.Closed<>(CloseReason.OWNER_UNLOADED));
        assertThat(order).containsExactly("dialog", "grid", "tasks", "hook", "destroy");
        assertThat(beta.isOpen()).isTrue();
        assertThat(sameName.isOpen()).isTrue();
    }

    @Test
    void unregisterAwaitsAnUnansweredStoreOnlyUntilItsBoundAndNeverReturnsItsItems() throws Exception {
        UltiToolsPlugin module = load(null, false);
        PlayerMock viewer = server.addPlayer("Alice");
        UiUnloadTestAccess.shortenWait(ui.runtime(), Duration.ofMillis(100));
        SlotContainer container = committed(injected(module), viewer, new CompletableFuture<>());
        injected(module).show(viewer, bound(container));
        clock.advanceTicks(1);
        long start = System.nanoTime();
        plugins.unregister(module);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        assertThat(container.end().toCompletableFuture()).isCompletedWithValue(EndOutcome.UNKNOWN_KEPT_BY_STORE);
        assertThat(viewer.getInventory().isEmpty()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"assembly,true", "assembly,false", "activation,false", "refused,false"})
    void everyPreListingRollbackUnloadsTheAlreadyIssuedUiService(String failure, boolean instance) throws Exception {
        UltiToolsPlugin module = load(failure, instance);
        UiService issued = injected(module);
        assertThat(issued).isNotNull();
        UiSession<?> rejected = issued.show(server.addPlayer(), Confirm.builder(Text.plain("Late")).build());
        assertThat(rejected.result().toCompletableFuture()).isCompletedWithValue(new UiResult.Closed<>(CloseReason.OWNER_UNLOADED));
        assertThat(plugins.getPluginList()).doesNotContain(module);
    }

    @Test
    void eachTaskCancellationSiteHasAnEarlierUiUnloadInTheSameCleanupPath() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/ultikits/ultitools/manager/PluginManager.java"));
        String tasks = "taskManager.cancelAll(plugin)";
        String unload = "uiManager.unload(plugin)";
        assertThat(source.split(java.util.regex.Pattern.quote(tasks), -1)).hasSize(6);
        assertThat(source.split(java.util.regex.Pattern.quote(unload), -1)).hasSize(6);
        int start = 0;
        for (int index = 0; index < 5; index++) {
            int cancelAt = source.indexOf(tasks, start);
            assertThat(source.substring(start, cancelAt)).contains(unload);
            start = cancelAt + tasks.length();
        }
    }

    private UiSession<?> copySession(String name, String viewer) {
        UltiToolsPlugin copy = mock(UltiToolsPlugin.class);
        when(copy.getPluginName()).thenReturn(name);
        return ui.serviceFor(copy).show(server.addPlayer(viewer), Confirm.builder(Text.plain(name)).build());
    }

    private static UiService injected(UltiToolsPlugin module) throws Exception {
        return (UiService) module.getClass().getField("ui").get(module);
    }

    private static SlotContainer committed(UiService service, PlayerMock viewer, CompletableFuture<StoreAnswer> answer) {
        return service.container(ContainerSpec.storage(1).disposition(Disposition.COMMIT)
                .store(snapshot -> answer).undelivered((id, items) -> { })
                .initial(viewer.getUniqueId(), List.of(new ItemStack(Material.STONE, 8))).build());
    }

    private static Grid bound(SlotContainer container) {
        return Grid.builder(Text.plain("Storage"), 1).bindRange(0, 0, 1, container, 0, BindingMode.EDITABLE).build();
    }

    private UltiToolsPlugin load(String failure, boolean instance) throws Exception {
        PluginManagerFirstAssemblyTest.FixtureJarClassLoader loader = new PluginManagerFirstAssemblyTest.FixtureJarClassLoader(
                buildJar().toFile(), getClass().getClassLoader(), "com.ultikits.testfixtures.uiwiring.");
        loaders.add(loader);
        Class<? extends UltiToolsPlugin> entry = loader.loadClass(UiWiringModule.class.getName()).asSubclass(UltiToolsPlugin.class);
        entry.getField("failurePoint").set(null, failure);
        ReflectionUtil.setFieldValue(plugins, "classLoader", loader);
        boolean registered = instance ? plugins.register(entry.getDeclaredConstructor().newInstance()) : plugins.register(entry);
        assertThat(registered).isEqualTo(failure == null);
        UltiToolsPlugin module = (UltiToolsPlugin) entry.getField("constructed").get(null);
        assertThat(module).isNotNull();
        return module;
    }

    private Path buildJar() throws Exception {
        Path jar = temporary.resolve("ui-wiring-" + loaders.size() + ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Class<?> type : List.of(UiWiringModule.class, UiWiringBean.class)) {
                String resource = type.getName().replace('.', '/') + ".class";
                output.putNextEntry(new JarEntry(resource));
                try (InputStream input = type.getResourceAsStream("/" + resource)) {
                    assertThat(input).isNotNull();
                    input.transferTo(output);
                }
                output.closeEntry();
            }
            output.putNextEntry(new JarEntry("plugin.yml"));
            output.write(("name: UiWiring\nversion: 1.0.0\napi-version: 700\nmain: "
                    + UiWiringModule.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }
}
