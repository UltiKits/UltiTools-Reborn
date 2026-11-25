package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.item.ItemRenderer;
import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.item.impl.PaperItemRenderer;
import com.ultikits.ultitools.scheduling.ManualScheduler;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.internal.FrameworkSchedulerBean;
import com.ultikits.ultitools.scheduling.internal.FrameworkScheduling;
import com.ultikits.ultitools.scheduling.internal.PaperSchedulerEngine;
import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.text.internal.Catalogue;
import com.ultikits.ultitools.text.internal.ConfiguredLocaleResolver;
import com.ultikits.ultitools.text.internal.TextEngine;
import com.ultikits.ultitools.ui.Ack;
import com.ultikits.ultitools.ui.BindingMode;
import com.ultikits.ultitools.ui.Choice;
import com.ultikits.ultitools.ui.CloseReason;
import com.ultikits.ultitools.ui.Confirm;
import com.ultikits.ultitools.ui.ContainerSpec;
import com.ultikits.ultitools.ui.Disposition;
import com.ultikits.ultitools.ui.Form;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridSession;
import com.ultikits.ultitools.ui.Notice;
import com.ultikits.ultitools.ui.RecordingScheduler;
import com.ultikits.ultitools.ui.SliderInput;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.TextInput;
import com.ultikits.ultitools.ui.UiResult;
import com.ultikits.ultitools.ui.UiService;
import com.ultikits.ultitools.ui.UiSession;
import com.ultikits.ultitools.ui.dialog.DialogModel;
import com.ultikits.ultitools.ui.dialog.DialogPresenter;
import com.ultikits.ultitools.ui.dialog.DialogTicket;
import com.ultikits.ultitools.utils.ReflectionUtil;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.stream.Stream;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(20)
class UiManagerWiringTest {
    private ServerMock server;
    private PlayerMock viewer;
    private ManualScheduler.Engine clock;
    private RecordingScheduler scheduler;
    private UiManager manager;
    private UltiToolsPlugin module;
    private UiService service;
    private Messages messages;
    private TextEngine textEngine;
    private final List<DialogModel> dialogs = new ArrayList<>();
    private final List<ItemStack> renderedItems = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        viewer = new PlayerMock(server, "Alice") {
            @Override public void updateInventory() { /* Client resynchronization has no transport in MockBukkit. */ }
        };
        server.addPlayer(viewer);
        clock = new ManualScheduler.Engine();
        scheduler = new RecordingScheduler(clock.scheduler("FrameworkUi"));
        Logger logger = Logger.getAnonymousLogger();
        ConfiguredLocaleResolver locales = new ConfiguredLocaleResolver(() -> "auto", () -> "en", logger);
        textEngine = new TextEngine(locales, logger);
        textEngine.publish(new Catalogue.Builder("ui-wiring", Locale.ENGLISH)
                .put(Locale.ENGLISH, "icon", "Diamond").put(Locale.CHINESE, "icon", "钻石").build(), this);
        messages = textEngine.messagesFor("ui-wiring");
        ItemRenderer items = spy(new PaperItemRenderer(messages, locales));
        doAnswer(call -> {
            ItemStack rendered = (ItemStack) call.callRealMethod();
            renderedItems.add(rendered);
            return rendered;
        }).when(items).render(any(ItemSpec.class), any(Audience.class));
        DialogPresenter presenter = new DialogPresenter() {
            @Override public void show(Player player, DialogModel model, DialogTicket ticket) { dialogs.add(model); }
            @Override public void dismiss(Player player) { /* The recording presenter owns no client screen. */ }
        };
        manager = UiManager.create(scheduler, messages, items, logger, Thread.currentThread(), presenter);
        module = module("alpha");
        service = manager.serviceFor(module);
    }

    @AfterEach
    void tearDown() {
        try {
            if (manager != null) { manager.close(); }
            if (textEngine != null) { textEngine.close(); }
            if (clock != null) { clock.close(); }
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void cachesByModuleIdentityAndUnloadsOnlyThatCopy() {
        UiService replacement = manager.serviceFor(module("alpha"));
        assertThat(manager.serviceFor(module)).isSameAs(service);
        assertThat(replacement).isNotSameAs(service);
        assertThat(replacement.owner()).isEqualTo("alpha");
        UiSession<?> old = service.show(viewer, Confirm.builder(Text.plain("Old")).build());
        manager.unload(module);
        manager.unload(module);
        manager.unload(module("never issued"));
        assertThat(old.result().toCompletableFuture()).isCompletedWithValue(new UiResult.Closed<>(CloseReason.OWNER_UNLOADED));
        UiSession<?> next = replacement.show(viewer, Confirm.builder(Text.plain("Next")).build());
        tick();
        assertThat(next.isOpen()).isTrue();
    }

    @Test
    void selectsDialogForAllFourIntentsAndChestForGridButRejectsUnrepresentableForm() {
        service.show(viewer, Notice.builder(Text.plain("Notice")).build());
        tick();
        service.show(viewer, Confirm.builder(Text.plain("Confirm")).build());
        tick();
        service.show(viewer, Choice.<String>builder(Text.plain("Choice")).option("a", Text.plain("A")).build());
        tick();
        service.show(viewer, Form.builder(Text.plain("Form"))
                .input(TextInput.builder("name", Text.plain("Name")).build()).build());
        tick();
        assertThat(dialogs).hasSize(4);
        GridSession grid = service.show(viewer, Grid.builder(Text.plain("Grid"), 1).build());
        tick();
        assertThat(viewer.getOpenInventory().getTopInventory().getSize()).isEqualTo(9);
        UiSession<?> unsupported = service.show(viewer, Form.builder(Text.plain("Large"))
                .input(SliderInput.builder("large", Text.plain("Large"), 1_000_000, 1_000_001).step(0.01).build()).build());
        tick();
        assertThat(unsupported.result().toCompletableFuture()).isCompletedWithValue(new UiResult.Closed<>(CloseReason.UNSUPPORTED));
        assertThat(grid.isOpen()).isTrue();
        assertThat(dialogs).hasSize(4);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void registeredQuitListenerEndsEachScreenOnceAndLeavesAnotherViewerAlone(boolean chest) {
        manager.registerListeners(MockBukkit.createMockPlugin());
        UiSession<?> session = chest ? service.show(viewer, Grid.builder(Text.plain("Grid"), 1).build())
                : service.show(viewer, Confirm.builder(Text.plain("Confirm")).build());
        PlayerMock other = server.addPlayer("Bob");
        UiSession<?> untouched = service.show(other, Confirm.builder(Text.plain("Other")).build());
        AtomicInteger completed = new AtomicInteger();
        session.result().thenAccept(result -> completed.incrementAndGet());
        tick();
        server.getPluginManager().callEvent(new PlayerQuitEvent(viewer, Component.empty()));
        tick();
        tick();
        assertThat(session.result().toCompletableFuture()).isCompletedWithValue(new UiResult.Closed<>(CloseReason.DISCONNECTED));
        assertThat(completed).hasValue(1);
        assertThat(untouched.isOpen()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void containerClickRequiresTheRegisteredChestListener(boolean registered) {
        if (registered) { manager.registerListeners(MockBukkit.createMockPlugin()); }
        AtomicInteger changes = new AtomicInteger();
        SlotContainer container = service.container(ContainerSpec.storage(1)
                .disposition(Disposition.RETURN_TO_CONTRIBUTORS).undelivered((id, contents) -> { })
                .onChange(change -> changes.incrementAndGet()).baseline(List.of(new ItemStack(Material.STONE, 8))).build());
        service.show(viewer, Grid.builder(Text.plain("Storage"), 1).bindRange(0, 0, 1, container, 0, BindingMode.EDITABLE).build());
        tick();
        InventoryClickEvent click = new InventoryClickEvent(viewer.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(click);
        assertThat(changes).hasValue(registered ? 1 : 0);
        assertThat(container.get(0).getAmount()).isEqualTo(registered ? 0 : 8);
        assertThat(click.isCancelled()).isEqualTo(registered);
    }

    @ParameterizedTest
    @CsvSource({"en-US,Diamond", "zh-CN,钻石"})
    void realRendererLocalizesForTheViewerAndHandsTheRenderedItemToInventory(String locale, String name) {
        viewer.setLocale(Locale.forLanguageTag(locale));
        try (MockedStatic<Bukkit> factory = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            factory.when(() -> Bukkit.createInventory(any(InventoryHolder.class), anyInt(), any(Component.class)))
                    .thenAnswer(call -> spy(server.createInventory(call.getArgument(0), call.getArgument(1, Integer.class),
                            call.getArgument(2, Component.class))));
            ItemSpec icon = ItemSpec.builder(Material.DIAMOND).name(messages.key("icon")).build();
            service.show(viewer, Grid.builder(Text.plain("Localized"), 1).set(0, 0, icon, null).build());
            tick();
            assertThat(renderedItems).singleElement().satisfies(item ->
                    assertThat(item.getData(DataComponentTypes.ITEM_NAME)).isEqualTo(Component.text(name)));
            ArgumentCaptor<ItemStack> handoff = ArgumentCaptor.forClass(ItemStack.class);
            verify(viewer.getOpenInventory().getTopInventory(), atLeastOnce()).setItem(eq(0), handoff.capture());
            assertThat(handoff.getValue().getType()).isEqualTo(Material.DIAMOND);
            assertThat(handoff.getValue().getAmount()).isEqualTo(renderedItems.getFirst().getAmount());
        }
    }

    @Test
    void closePassesTheOriginalCursorToTheServersCloseOperation() {
        PlayerMock observed = spy(viewer);
        GridSession session = service.show(observed, Grid.builder(Text.plain("Cursor"), 1).build());
        tick();
        observed.setItemOnCursor(new ItemStack(Material.DIRT, 3));
        clearInvocations(observed);
        AtomicReference<ItemStack> cursorAtClose = new AtomicReference<>();
        doAnswer(call -> {
            cursorAtClose.set(observed.getItemOnCursor().clone());
            return call.callRealMethod();
        }).when(observed).closeInventory();
        session.close();
        tick();
        verify(observed).closeInventory();
        assertThat(cursorAtClose.get()).isEqualTo(new ItemStack(Material.DIRT, 3));
        // MockBukkit clears the cursor on close; real-server return to storage is covered by UAT.
        assertThat(session.result().toCompletableFuture()).isDone();
    }

    @Test
    void closeContextEndsUiBeforeTheFrameworkSchedulersPreDestroy() {
        List<String> order = new ArrayList<>();
        UiSession<?> session = service.show(viewer, Confirm.builder(Text.plain("Stop")).build());
        session.result().thenAccept(result -> order.add("ui"));
        tick();
        SimpleContainer context = new SimpleContainer();
        PaperSchedulerEngine engine = mock(PaperSchedulerEngine.class);
        doAnswer(call -> { order.add("scheduler"); return null; }).when(engine).retire(scheduler);
        context.registerSingleton("frameworkScheduler", new FrameworkSchedulerBean(engine, scheduler));
        DependenceManagers dependencies = mock(DependenceManagers.class, CALLS_REAL_METHODS);
        ReflectionUtil.setFieldValue(dependencies, "context", context);
        ReflectionUtil.setFieldValue(dependencies, "uiManager", manager);
        dependencies.closeContext();
        assertThat(order).containsExactly("ui", "scheduler");
        UiSession<Ack> rejected = manager.runtime().service("late").show(viewer, Notice.builder(Text.plain("Late")).build());
        assertThat(rejected.result().toCompletableFuture()).isCompletedWithValue(new UiResult.Closed<>(CloseReason.OWNER_UNLOADED));
        manager.close();
        assertThat(order).containsExactly("ui", "scheduler");
    }

    @ParameterizedTest
    @MethodSource("closeFailures")
    void closeContextRetiresTheFrameworkSchedulerEvenWhenUiCloseThrows(Throwable failure) {
        SimpleContainer context = new SimpleContainer();
        PaperSchedulerEngine engine = mock(PaperSchedulerEngine.class);
        context.registerSingleton("frameworkScheduler", new FrameworkSchedulerBean(engine, scheduler));
        UiManager failing = mock(UiManager.class);
        doThrow(failure).when(failing).close();
        DependenceManagers dependencies = mock(DependenceManagers.class, CALLS_REAL_METHODS);
        ReflectionUtil.setFieldValue(dependencies, "context", context);
        ReflectionUtil.setFieldValue(dependencies, "uiManager", failing);
        try {
            assertThatThrownBy(dependencies::closeContext).isSameAs(failure);
            verify(engine).retire(scheduler);
        } finally {
            context.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initUiFailureRetiresTheSchedulerAndClosesAnyPartialUiManager(boolean created, @TempDir Path data) {
        UltiTools plugin = mock(UltiTools.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        UiManager partial = mock(UiManager.class);
        IllegalStateException failure = new IllegalStateException("UI initialization failed");
        doThrow(failure).when(partial).registerListeners(plugin);
        try (MockedConstruction<PaperSchedulerEngine> engines = mockConstruction(PaperSchedulerEngine.class,
                     (engine, creation) -> when(engine.newOwner("UltiTools")).thenReturn(scheduler));
             MockedStatic<UiManager> factory = mockStatic(UiManager.class)) {
            factory.when(() -> UiManager.create(any(Scheduler.class), any(Messages.class), any(ItemRenderer.class),
                    any(Logger.class), any(Thread.class), any(DialogPresenter.class))).thenAnswer(call -> {
                        if (!created) { throw failure; }
                        return partial;
                    });
            assertThatThrownBy(() -> new DependenceManagers(plugin, getClass().getClassLoader())).isSameAs(failure);
            assertThat(engines.constructed()).hasSize(1);
            verify(engines.constructed().getFirst()).retire(scheduler);
            verify(partial, times(created ? 1 : 0)).close();
            assertThatThrownBy(FrameworkScheduling::scheduler).isInstanceOf(IllegalStateException.class);
        } finally {
            FrameworkScheduling.uninstall(scheduler);
        }
    }

    private static Stream<Throwable> closeFailures() {
        return Stream.of(new IllegalStateException("UI close"), new AssertionError("UI close"),
                new StackOverflowError("UI close"));
    }

    private void tick() { clock.advanceTicks(1); }

    private static UltiToolsPlugin module(String name) {
        UltiToolsPlugin module = mock(UltiToolsPlugin.class);
        when(module.getPluginName()).thenReturn(name);
        return module;
    }
}
