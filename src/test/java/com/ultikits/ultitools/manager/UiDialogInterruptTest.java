package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.item.ItemRenderer;
import com.ultikits.ultitools.scheduling.ManualScheduler;
import com.ultikits.ultitools.text.FixedLocaleResolver;
import com.ultikits.ultitools.text.InMemoryMessages;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.Choice;
import com.ultikits.ultitools.ui.CloseReason;
import com.ultikits.ultitools.ui.Confirm;
import com.ultikits.ultitools.ui.ContainerSpec;
import com.ultikits.ultitools.ui.Disposition;
import com.ultikits.ultitools.ui.Form;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.Notice;
import com.ultikits.ultitools.ui.RecordingScheduler;
import com.ultikits.ultitools.ui.ShowOptions;
import com.ultikits.ultitools.ui.UiIntent;
import com.ultikits.ultitools.ui.UiResult;
import com.ultikits.ultitools.ui.UiService;
import com.ultikits.ultitools.ui.UiSession;
import com.ultikits.ultitools.ui.BindingMode;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.TextInput;
import com.ultikits.ultitools.ui.dialog.ButtonRole;
import com.ultikits.ultitools.ui.dialog.DialogModel;
import com.ultikits.ultitools.ui.dialog.DialogPresenter;
import com.ultikits.ultitools.ui.dialog.DialogTicket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Timeout(20)
class UiDialogInterruptTest {
    private ServerMock server;
    private PlayerMock viewer;
    private ManualScheduler.Engine clock;
    private UiManager manager;
    private UltiToolsPlugin module;
    private UiService service;
    private final List<DialogTicket> tickets = new ArrayList<>();
    private final AtomicInteger dismissals = new AtomicInteger();

    @BeforeEach
    void prepare() {
        server = MockBukkit.mock();
        viewer = server.addPlayer("Alice");
        clock = new ManualScheduler.Engine();
        RecordingScheduler scheduler = new RecordingScheduler(clock.scheduler("DialogInterrupt"));
        InMemoryMessages messages = new InMemoryMessages("test", new FixedLocaleResolver(Locale.ENGLISH, Optional.empty()));
        DialogPresenter presenter = new DialogPresenter() {
            @Override public void show(Player player, DialogModel model, DialogTicket ticket) { tickets.add(ticket); }
            @Override public void dismiss(Player player) { dismissals.incrementAndGet(); }
        };
        manager = UiManager.create(scheduler, messages, mock(ItemRenderer.class),
                Logger.getAnonymousLogger(), Thread.currentThread(), presenter);
        manager.registerListeners(MockBukkit.createMockPlugin());
        module = mock(UltiToolsPlugin.class);
        when(module.getPluginName()).thenReturn("interrupt-test");
        service = manager.serviceFor(module);
    }

    @AfterEach
    void dispose() {
        try {
            if (manager != null) { manager.close(); }
            if (clock != null) { clock.close(); }
        } finally {
            MockBukkit.unmock();
        }
    }

    @ParameterizedTest
    @MethodSource("dialogsAndInterruptions")
    void gameInterruptionEndsEachDialogOnItsEntityLaneWithoutDismissingAnotherScreen(UiIntent<?> intent, String event) {
        UiSession<?> session = service.show(viewer, intent);
        List<RecordingScheduler.Scope> scopes = new ArrayList<>();
        session.result().thenAccept(result -> scopes.add(RecordingScheduler.current().orElseThrow()));
        tick();
        fire(viewer, event);
        assertThat(session.isOpen()).isFalse();
        tick();
        assertClosed(session, "INTERRUPTED");
        assertThat(scopes).singleElement().satisfies(scope -> {
            assertThat(scope.kind()).isEqualTo(RecordingScheduler.Scope.Kind.ENTITY);
            assertThat(scope.entity()).isEqualTo(viewer.getUniqueId());
        });
        assertThat(dismissals).hasValue(0);
        tickets.getFirst().accept(new ButtonRole.Yes(), null, viewer);
        tick();
        assertThat(scopes).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEATH", "WORLD", "INVENTORY"})
    void anotherPlayersInterruptionLeavesThisPlayersDialogAlone(String event) {
        UiSession<?> untouched = service.show(viewer, confirmation());
        PlayerMock other = server.addPlayer("Bob");
        UiSession<?> interrupted = service.show(other, confirmation());
        tick();
        fire(other, event);
        tick();
        assertThat(untouched.isOpen()).isTrue();
        assertThat(untouched.result().toCompletableFuture()).isNotDone();
        assertClosed(interrupted, "INTERRUPTED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEATH", "INVENTORY"})
    void cancelledEventsDoNotInterruptDialogs(String event) {
        UiSession<?> session = service.show(viewer, confirmation());
        tick();
        Event cancelled = event.equals("DEATH") ? death(viewer) : new InventoryOpenEvent(viewer.getOpenInventory());
        ((Cancellable) cancelled).setCancelled(true);
        server.getPluginManager().callEvent(cancelled);
        tick();
        assertThat(session.isOpen()).isTrue();
        assertThat(session.result().toCompletableFuture()).isNotDone();
        assertThat(dismissals).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void frameworkReplacementKeepsItsOriginalReasonAndOnlyEndsThePreviousDialogOnce(boolean chest) {
        UiSession<?> previous = service.show(viewer, confirmation());
        AtomicInteger results = new AtomicInteger();
        previous.result().thenAccept(result -> results.incrementAndGet());
        tick();
        DialogTicket oldTicket = tickets.getFirst();
        UiSession<?> next = chest ? service.show(viewer, Grid.builder(Text.plain("Next"), 1).build())
                : service.show(viewer, confirmation());
        tick();
        oldTicket.accept(new ButtonRole.Yes(), null, viewer);
        tick();
        assertClosed(previous, "REPLACED");
        assertThat(results).hasValue(1);
        assertThat(next.isOpen()).isTrue();
        assertThat(dismissals).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({"DEATH,DEATH,false", "WORLD,TELEPORT,false", "INVENTORY,OPEN_NEW,false",
            "DEATH,DEATH,true", "WORLD,TELEPORT,true", "INVENTORY,OPEN_NEW,true"})
    void gridAndBoundContainerKeepTheirExistingPlayerCloseReason(String event, InventoryCloseEvent.Reason reason,
                                                                boolean bound) {
        Grid.Builder grid = Grid.builder(Text.plain("Chest"), 1);
        if (bound) {
            SlotContainer container = service.container(ContainerSpec.storage(1)
                    .disposition(Disposition.RETURN_TO_CONTRIBUTORS).undelivered((id, items) -> { }).build());
            grid.bindRange(0, 0, 1, container, 0, BindingMode.EDITABLE);
        }
        UiSession<?> session = service.show(viewer, grid.build());
        tick();
        InventoryCloseEvent closed = new InventoryCloseEvent(viewer.getOpenInventory(), reason);
        fire(viewer, event);
        server.getPluginManager().callEvent(closed);
        tick();
        assertClosed(session, CloseReason.PLAYER.name());
        assertThat(dismissals).hasValue(0);
    }

    @Test
    void interruptionCleanupLeavesTheForeignInventoryOpen() {
        UiSession<?> session = service.show(viewer, confirmation());
        tick();
        Inventory foreign = server.createInventory(null, 9);
        viewer.openInventory(foreign);
        tick();
        assertClosed(session, "INTERRUPTED");
        assertThat(viewer.getOpenInventory().getTopInventory()).isSameAs(foreign);
        assertThat(dismissals).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"interruption", "answer", "timeout", "unload"})
    void firstTerminalSignalWinsAgainstInterruptionAnswerTimeoutAndUnload(String first) {
        UiSession<Boolean> session = service.show(viewer, confirmation(),
                ShowOptions.builder().timeout(Duration.ofMillis(50)).build());
        AtomicInteger results = new AtomicInteger();
        session.result().thenAccept(result -> results.incrementAndGet());
        tick();
        DialogTicket ticket = tickets.getFirst();
        switch (first) {
            case "answer" -> ticket.accept(new ButtonRole.Yes(), null, viewer);
            case "timeout" -> tick();
            case "unload" -> manager.unload(module);
            default -> fire(viewer, "DEATH");
        }
        fire(viewer, "DEATH");
        fire(viewer, "WORLD");
        ticket.accept(new ButtonRole.Yes(), null, viewer);
        manager.unload(module);
        tick();
        tick();
        assertThat(results).hasValue(1);
        switch (first) {
            case "answer" -> assertThat(session.result().toCompletableFuture())
                    .isCompletedWithValue(new UiResult.Completed<>(true));
            case "timeout" -> assertClosed(session, "TIMED_OUT");
            case "unload" -> assertClosed(session, "OWNER_UNLOADED");
            default -> assertClosed(session, "INTERRUPTED");
        }
    }

    @Test
    void closeUnregistersAllUiListenersTogether() {
        assertThat(Arrays.stream(InventoryOpenEvent.getHandlerList().getRegisteredListeners())
                .filter(listener -> listener.getListener().getClass().getSimpleName().equals("UiDialogInterruptListener")))
                .hasSize(1);
        manager.close();
        assertThat(Arrays.stream(InventoryOpenEvent.getHandlerList().getRegisteredListeners())
                .filter(listener -> listener.getListener().getClass().getSimpleName().equals("UiDialogInterruptListener")))
                .isEmpty();
        assertThat(Arrays.stream(InventoryClickEvent.getHandlerList().getRegisteredListeners())
                .filter(listener -> listener.getListener().getClass().getSimpleName().equals("ChestListener")))
                .isEmpty();
    }

    private void fire(Player player, String event) {
        switch (event) {
            case "DEATH" -> server.getPluginManager().callEvent(death(player));
            case "WORLD" -> server.getPluginManager().callEvent(new PlayerChangedWorldEvent(player, player.getWorld()));
            case "INVENTORY" -> player.openInventory(server.createInventory(null, 9));
            default -> throw new IllegalArgumentException(event);
        }
    }

    private static PlayerDeathEvent death(Player player) {
        return new PlayerDeathEvent(player, mock(DamageSource.class), new ArrayList<>(), 0, Component.empty(), false);
    }

    private static void assertClosed(UiSession<?> session, String reason) {
        assertThat(session.result().toCompletableFuture()).isDone();
        UiResult<?> result = session.result().toCompletableFuture().join();
        assertThat(result).isInstanceOf(UiResult.Closed.class);
        assertThat(((UiResult.Closed<?>) result).reason().name()).isEqualTo(reason);
    }

    private static Confirm confirmation() { return Confirm.builder(Text.plain("Confirm")).build(); }
    private void tick() { clock.advanceTicks(1); }

    private static Stream<Arguments> dialogsAndInterruptions() {
        List<UiIntent<?>> intents = List.of(Notice.builder(Text.plain("Notice")).build(), confirmation(),
                Choice.<String>builder(Text.plain("Choice")).option("a", Text.plain("A")).build(),
                Form.builder(Text.plain("Form")).input(TextInput.builder("name", Text.plain("Name")).build()).build());
        return intents.stream().flatMap(intent -> Stream.of("DEATH", "WORLD", "INVENTORY")
                .map(event -> Arguments.of(intent, event)));
    }
}
