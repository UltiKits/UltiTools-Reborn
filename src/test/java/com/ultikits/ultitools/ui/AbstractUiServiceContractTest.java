package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Contract of a {@link UiService} implementation: backend selection, session lifecycle, threads and grid
 * snapshots, driven through registered probe backends. It does not prove what a real backend does with player
 * input (for example that a dismissed Confirm never yields {@code true}, or that inventory events are
 * cancelled: E1 event cancellation and its closed allowlist); each backend proves that in its own tests.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public abstract class AbstractUiServiceContractTest {
    protected static final long SETTLE_TICKS = 3;
    protected static final Text TITLE = Text.plain("Contract");
    protected static final ItemSpec ICON = ItemSpec.builder(Key.key("minecraft", "stone")).build();
    protected Fixture fixture;
    protected ServerMock server;
    protected PlayerMock viewer;
    protected UiService service;

    /**
     * Creates the implementation under test with its own registry, shared by all owners. The implementation must
     * submit every scheduled step (opening, outcome delivery, grid actions, update notifications, timeouts)
     * through one {@link RecordingScheduler} wrapping the scheduler it would use in production, and return that
     * wrapper from {@link Fixture#scheduler()}; thread assertions read the wrapper's record, never the
     * implementation. Called on the tick thread (the JUnit thread, which is MockBukkit's main thread).
     */
    protected abstract Fixture createFixture();

    /** Test-only lifecycle and observation controls, not additions to the public UiService API. */
    public interface Fixture extends AutoCloseable {
        /** The service owned by {@code owner}; the same instance for the same owner. */
        UiService service(String owner);
        /** Registers a backend as the framework does at start-up. @throws IllegalStateException if a registered backend has the same priority */
        void register(UiBackend backend);
        /**
         * Performs exactly {@code ticks} server ticks on the tick thread and returns when every task due in them
         * has finished. Every flow in this suite settles within {@code SETTLE_TICKS} ticks after its last action.
         */
        void advanceTicks(long ticks);
        /**
         * Makes {@code player} quit as the server does, on the tick thread: the player is offline afterwards, the
         * implementation's quit handling has run, and the player's entity tasks are retired (ManualScheduler:
         * {@code Engine#retire}; a MockBukkit fixture: {@code PlayerMock#disconnect}).
         */
        void disconnect(PlayerMock player);
        /** Unloads {@code owner} as the module lifecycle does, on the tick thread; returns when the unload has finished. */
        void unload(String owner);
        /** The scheduler wrapper the implementation submits through (see {@link #createFixture()}). */
        RecordingScheduler scheduler();
        /** The container engine, driven directly by the editable contract without event decoding. */
        GestureEngine engine();
        /** Drives input as a backend does: use its last rendered snapshot and refuse a terminal session. */
        boolean gesture(GridSession session, ViewerItems side, Gesture gesture);
        /**
         * What the viewer's screen of {@code session} currently shows at the bound slot {@code slot}, as last drawn by
         * the implementation (a real backend reads its open inventory view); {@code ItemStack.empty()} if nothing.
         * A copy. Called on the tick thread.
         */
        ItemStack shown(GridSession session, GridSlot slot);
        /** Thread-safe snapshot of WARNING-or-higher records the implementation logged so far. */
        List<LogRecord> warnings();
        /** Releases the scheduler and every resource of the fixture. */
        @Override void close();
    }

    /** A registerable backend that records screen operations and exposes typed late-answer controls. */
    public static final class ProbeBackend implements UiBackend {
        private final Key id;
        private final int priority;
        private final Set<Class<?>> kinds;
        private final Map<UiSession<?>, BackendCallbacks<?>> callbacks = new IdentityHashMap<>();
        private final List<UiSession<?>> opened = new ArrayList<>();
        private final List<UiSession<?>> closed = new ArrayList<>();
        private final List<String> operations;
        private final List<Update> updates = new ArrayList<>();
        private final Map<UiSession<?>, Grid> screens = new IdentityHashMap<>();
        private final Map<UiSession<?>, Map<GridSlot, ItemStack>> closingPictures = new IdentityHashMap<>();

        private static Map<GridSlot, ItemStack> picture(Grid grid) {
            Map<GridSlot, ItemStack> result = new LinkedHashMap<>();
            grid.bindings().forEach((slot, binding) -> result.put(slot, binding.container().get(binding.index())));
            return result;
        }
        public ItemStack atClose(GridSession session, GridSlot slot) { return closingPictures.get(session).get(slot).clone(); }
        private final List<Optional<RecordingScheduler.Scope>> openScopes = new ArrayList<>();
        private final List<Optional<RecordingScheduler.Scope>> closeScopes = new ArrayList<>();

        public record Update(GridSession session, Grid previous, Grid next, Optional<RecordingScheduler.Scope> scope) { }

        public ProbeBackend(String name, int priority, Class<?>... kinds) {
            this(name, priority, new ArrayList<>(), kinds);
        }

        public ProbeBackend(String name, int priority, List<String> operations, Class<?>... kinds) {
            id = Key.key("test", name);
            this.priority = priority;
            this.operations = operations;
            this.kinds = Set.of(kinds);
        }

        @Override public Key id() { return id; }
        @Override public int priority() { return priority; }
        @Override public boolean supports(UiIntent<?> intent, Player player) { return kinds.contains(intent.getClass()); }
        @Override public <R> void open(UiSession<R> session, BackendCallbacks<R> reports) {
            callbacks.put(session, reports);
            opened.add(session);
            openScopes.add(RecordingScheduler.current());
            operations.add("open");
            if (session instanceof GridSession grid) {
                screens.put(session, grid.current());
            }
        }
        @Override public void update(GridSession session, Grid previous, Grid next) {
            updates.add(new Update(session, previous, next, RecordingScheduler.current()));
            operations.add("update");
            screens.put(session, next);
        }
        @Override public void close(UiSession<?> session) {
            closed.add(session);
            closeScopes.add(RecordingScheduler.current());
            operations.add("close");
            if (screens.containsKey(session)) {
                closingPictures.put(session, picture(screens.remove(session)));
            }
        }
        public List<Update> updates() { return List.copyOf(updates); }
        public List<Optional<RecordingScheduler.Scope>> openScopes() { return List.copyOf(openScopes); }
        public List<Optional<RecordingScheduler.Scope>> closeScopes() { return List.copyOf(closeScopes); }
        public List<UiSession<?>> opened() { return List.copyOf(opened); }
        public List<UiSession<?>> closed() { return List.copyOf(closed); }
        @SuppressWarnings("unchecked") // The callback is stored under the very session supplied with its R.
        public <R> BackendCallbacks<R> callbacks(UiSession<R> session) { return (BackendCallbacks<R>) callbacks.get(session); }
    }

    @BeforeEach
    void setUpUiContract() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        viewer = server.addPlayer("Viewer");
        fixture = createFixture();
        service = fixture.service("FirstModule");
    }

    @AfterEach
    void tearDownUiContract() {
        try {
            if (fixture != null) {
                fixture.close();
            }
        } finally {
            MockBukkitHelper.safeUnmock();
        }
    }

    protected ProbeBackend backend(Class<?>... kinds) {
        ProbeBackend backend = new ProbeBackend("backend", 0, kinds);
        fixture.register(backend);
        return backend;
    }

    protected void settle() { fixture.advanceTicks(SETTLE_TICKS); }
    protected static <R> UiResult<R> outcome(UiSession<R> session) {
        CompletableFuture<UiResult<R>> future = session.result().toCompletableFuture();
        assertThat(future).as("result of a session showing %s", session.intent()).isDone();
        return future.getNow(null);
    }
    protected static Confirm confirm() { return Confirm.builder(TITLE).build(); }

    @Test
    void result_completesExactlyOnce() {
        ProbeBackend backend = backend(Confirm.class);
        UiSession<Boolean> session = service.show(viewer, confirm());
        AtomicInteger completions = new AtomicInteger();
        session.result().thenAccept(ignored -> completions.incrementAndGet());
        CompletableFuture<UiResult<Boolean>> callerCopy = session.result().toCompletableFuture();
        assertThat(callerCopy.complete(new UiResult.Completed<>(false))).isTrue();
        assertThat(session.result().toCompletableFuture()).isNotDone();
        settle();
        backend.callbacks(session).completed(true);
        settle();
        assertThat(outcome(session)).isEqualTo(new UiResult.Completed<>(true));
        backend.callbacks(session).completed(false);
        backend.callbacks(session).closed(CloseReason.PLAYER);
        session.close();
        settle();
        assertThat(outcome(session)).isEqualTo(new UiResult.Completed<>(true));
        assertThat(completions).hasValue(1);
        assertThat(backend.closed()).containsExactly(session);
        assertThat(session.isOpen()).isFalse();
    }

    @Test
    void result_completesOnViewerEntityThread() {
        ProbeBackend backend = backend(Confirm.class);
        UiSession<Boolean> session = service.show(viewer, confirm());
        AtomicReference<Boolean> entityThread = new AtomicReference<>();
        session.result().thenAccept(ignored -> entityThread.set(RecordingScheduler.inEntityTask(viewer)));
        settle();
        backend.callbacks(session).completed(true);
        assertThat(session.result().toCompletableFuture()).isNotDone();
        settle();
        assertThat(entityThread).hasValue(true);
        assertThat(backend.openScopes()).containsExactly(entityScope());
        assertThat(outcome(session)).isEqualTo(new UiResult.Completed<>(true));
    }

    @Test
    void backend_highestPriorityWins() {
        ProbeBackend a = new ProbeBackend("a", 50, Notice.class);
        ProbeBackend b = new ProbeBackend("b", 0, Confirm.class, Notice.class);
        ProbeBackend c = new ProbeBackend("c", 100, Confirm.class);
        fixture.register(a);
        fixture.register(b);
        fixture.register(c);
        UiSession<Boolean> confirm = service.show(viewer, confirm());
        UiSession<Ack> notice = service.show(server.addPlayer(), Notice.builder(TITLE).build());
        settle();
        assertThat(c.opened()).containsExactly(confirm);
        assertThat(a.opened()).containsExactly(notice);
        assertThat(b.opened()).isEmpty();
    }

    @Test
    void backend_equalPriority_failsRegistration() {
        ProbeBackend one = new ProbeBackend("one", 100, Confirm.class);
        ProbeBackend two = new ProbeBackend("two", 100, Grid.class);
        fixture.register(one);
        assertThatThrownBy(() -> fixture.register(two)).isInstanceOf(IllegalStateException.class);
        fixture.register(new ProbeBackend("three", 50, Notice.class));
        UiSession<Boolean> session = service.show(viewer, confirm());
        settle();
        assertThat(one.opened()).containsExactly(session);
        assertThat(two.opened()).isEmpty();
    }

    @Test
    void unsupported_closesUnsupported() {
        ProbeBackend backend = backend(Notice.class);
        UiSession<Boolean> session = service.show(viewer, confirm());
        settle();
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.UNSUPPORTED));
        assertThat(backend.opened()).isEmpty();
        assertThat(backend.closed()).isEmpty();
    }

    @Test
    void offlineViewer_closesDisconnected() {
        ProbeBackend backend = backend(Confirm.class);
        fixture.disconnect(viewer);
        UiSession<Boolean> session = service.show(viewer, confirm());
        AtomicReference<Boolean> globalThread = new AtomicReference<>();
        session.result().thenAccept(ignored -> globalThread.set(RecordingScheduler.inGlobalTask()));
        assertThat(session.isOpen()).isFalse();
        settle();
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.DISCONNECTED));
        assertThat(globalThread).hasValue(true);
        assertThat(backend.opened()).isEmpty();
    }

    @Test
    void viewerQuits_closesDisconnected() {
        ProbeBackend backend = backend(Confirm.class);
        UiSession<Boolean> session = service.show(viewer, confirm());
        settle();
        AtomicReference<Boolean> globalThread = new AtomicReference<>();
        session.result().thenAccept(ignored -> globalThread.set(RecordingScheduler.inGlobalTask()));
        fixture.disconnect(viewer);
        settle();
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.DISCONNECTED));
        assertThat(globalThread).hasValue(true);
        assertThat(backend.closed()).containsExactly(session);
        assertThat(backend.closeScopes()).containsExactly(Optional.of(new RecordingScheduler.Scope(RecordingScheduler.Scope.Kind.GLOBAL, null)));
    }

    @Test
    void secondShow_replacesFirst() {
        List<String> operations = new ArrayList<>();
        ProbeBackend backend = new ProbeBackend("backend", 0, operations, Confirm.class);
        fixture.register(backend);
        UiSession<Boolean> first = service.show(viewer, confirm());
        settle();
        UiSession<Boolean> second = fixture.service("OtherModule").show(viewer, confirm());
        settle();
        assertThat(outcome(first)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.REPLACED));
        assertThat(operations).containsExactly("open", "close", "open");
        assertThat(backend.closed()).containsExactly(first);
        assertThat(second.isOpen()).isTrue();
    }

    @Test
    void timeout_closesTimedOutAndClosesScreen() {
        ProbeBackend backend = backend(Confirm.class);
        UiSession<Boolean> session = service.show(viewer, confirm(), ShowOptions.builder().timeout(Duration.ofSeconds(1)).build());
        fixture.advanceTicks(1);
        fixture.advanceTicks(19);
        assertThat(session.isOpen()).isTrue();
        assertThat(session.result().toCompletableFuture()).isNotDone();
        fixture.advanceTicks(3);
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.TIMED_OUT));
        assertThat(backend.closed()).containsExactly(session);
    }

    @Test
    void lateBackendAnswer_afterTimeout_isIgnored() {
        ProbeBackend backend = backend(Confirm.class);
        UiSession<Boolean> session = service.show(viewer, confirm(), ShowOptions.builder().timeout(Duration.ofSeconds(1)).build());
        fixture.advanceTicks(25);
        backend.callbacks(session).completed(true);
        backend.callbacks(session).closed(CloseReason.PLAYER);
        settle();
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.TIMED_OUT));
        assertThat(backend.closed()).containsExactly(session);
    }

    @Test
    void ownerUnload_closesOwnSessionsOnly() {
        ProbeBackend backend = backend(Confirm.class);
        UiSession<Boolean> first = service.show(viewer, confirm());
        UiSession<Boolean> sameOwner = service.show(server.addPlayer(), confirm());
        UiSession<Boolean> other = fixture.service("OtherModule").show(server.addPlayer(), confirm());
        settle();
        fixture.unload(service.owner());
        assertThat(outcome(first)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.OWNER_UNLOADED));
        assertThat(outcome(sameOwner)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.OWNER_UNLOADED));
        assertThat(other.isOpen()).isTrue();
        assertThat(backend.closed()).containsExactlyInAnyOrder(first, sameOwner);
    }

    @Test
    void showAfterUnload_closesOwnerUnloaded() {
        ProbeBackend backend = backend(Confirm.class);
        fixture.unload(service.owner());
        UiSession<Boolean> session = service.show(viewer, confirm());
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.OWNER_UNLOADED));
        assertThat(backend.opened()).isEmpty();
    }

    @Test
    void close_isIdempotent() {
        ProbeBackend backend = backend(Confirm.class);
        UiSession<Boolean> session = service.show(viewer, confirm());
        settle();
        session.close();
        assertThat(session.isOpen()).isFalse();
        session.close();
        settle();
        session.close();
        settle();
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.CLOSED_BY_CODE));
        assertThat(backend.closed()).containsExactly(session);
        assertThat(backend.closeScopes()).containsExactly(entityScope());
    }


    @Test
    void grid_click_runsActionOnceOnEntityThread() {
        ProbeBackend backend = backend(Grid.class);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<GridClick> click = new AtomicReference<>();
        AtomicReference<Boolean> entityThread = new AtomicReference<>();
        GridSlot slot = GridSlot.of(0, 4);
        Grid grid = Grid.builder(TITLE, 1).set(slot, GridItem.of(ICON, event -> {
            calls.incrementAndGet();
            click.set(event);
            entityThread.set(RecordingScheduler.inEntityTask(viewer));
        })).build();
        GridSession session = service.show(viewer, grid);
        settle();
        backend.callbacks(session).clicked(slot, ClickKind.SHIFT_RIGHT);
        assertThat(calls).hasValue(0);
        settle();
        assertThat(calls).hasValue(1);
        assertThat(click.get().viewer()).isSameAs(viewer);
        assertThat(click.get().slot()).isEqualTo(slot);
        assertThat(click.get().kind()).isEqualTo(ClickKind.SHIFT_RIGHT);
        assertThat(click.get().session()).isSameAs(session);
        assertThat(entityThread).hasValue(true);
        assertThat(session.isOpen()).isTrue();
        assertThat(session.intent()).isSameAs(grid);
    }

    @Test
    void grid_clickEmptySlot_runsNothing() {
        ProbeBackend backend = backend(Grid.class);
        AtomicInteger calls = new AtomicInteger();
        GridSession session = service.show(viewer, Grid.builder(TITLE, 1)
                .set(0, 0, ICON, event -> calls.incrementAndGet()).set(0, 1, ICON, null).build());
        settle();
        backend.callbacks(session).clicked(GridSlot.of(0, 1), ClickKind.LEFT);
        backend.callbacks(session).clicked(GridSlot.of(0, 2), ClickKind.RIGHT);
        settle();
        assertThat(calls).hasValue(0);
        assertThat(session.isOpen()).isTrue();
    }

    @Test
    void grid_updateAfterClose_isNoop() {
        ProbeBackend backend = backend(Grid.class);
        Grid first = Grid.builder(TITLE, 1).build();
        GridSession session = service.show(viewer, first);
        settle();
        session.close();
        session.update(Grid.builder(Text.plain("Later"), 2).build());
        assertThat(session.current()).isSameAs(first);
        settle();
        assertThat(session.current()).isSameAs(first);
        assertThat(backend.closed()).containsExactly(session);
        assertThat(backend.updates()).isEmpty();
    }

    @Test
    void grid_updateWithOtherRowCount_throws() {
        backend(Grid.class);
        GridSession session = service.show(viewer, Grid.builder(TITLE, 1).build());
        settle();
        assertThatThrownBy(() -> session.update(Grid.builder(TITLE, 2).build())).isInstanceOf(IllegalArgumentException.class);
        Grid next = Grid.builder(Text.plain("Next"), 1).set(0, 1, ICON, null).build();
        session.update(next);
        settle();
        assertThat(session.current()).isSameAs(next);
    }

    @Test
    void grid_actionThrows_sessionStaysOpen() {
        ProbeBackend backend = backend(Grid.class);
        IllegalStateException thrown = new IllegalStateException("action failure");
        AtomicInteger calls = new AtomicInteger();
        GridSession session = service.show(viewer, Grid.builder(TITLE, 1).set(0, 0, ICON, click -> {
            calls.incrementAndGet();
            throw thrown;
        }).build());
        settle();
        backend.callbacks(session).clicked(GridSlot.of(0, 0), ClickKind.LEFT);
        settle();
        backend.callbacks(session).clicked(GridSlot.of(0, 0), ClickKind.RIGHT);
        settle();
        assertThat(calls).hasValue(2);
        assertThat(session.isOpen()).isTrue();
        assertThat(session.result().toCompletableFuture()).isNotDone();
        assertThat(fixture.warnings()).anySatisfy(record -> {
            assertThat(text(record)).contains(service.owner());
            assertThat(record.getThrown()).isSameAs(thrown);
        });
    }

    @Test
    void defaultTimeout_isTenMinutesForPromptsAndNoneForGrid() {
        ProbeBackend backend = backend(Notice.class, Confirm.class, Form.class, Choice.class, Grid.class);
        UiSession<Ack> notice = service.show(viewer, Notice.builder(TITLE).build());
        UiSession<Boolean> confirm = service.show(server.addPlayer(), confirm(), ShowOptions.builder().noTimeout().build());
        UiSession<FormResponse> form = service.show(server.addPlayer(), Form.builder(TITLE)
                .input(ToggleInput.of("flag", TITLE, false)).build());
        UiSession<String> choice = service.show(server.addPlayer(), Choice.<String>builder(TITLE).option("a", TITLE).build());
        GridSession grid = service.show(server.addPlayer(), Grid.builder(TITLE, 1).build());
        fixture.advanceTicks(12_005);
        assertThat(outcome(notice)).isEqualTo(new UiResult.Closed<Ack>(CloseReason.TIMED_OUT));
        assertThat(outcome(confirm)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.TIMED_OUT));
        assertThat(outcome(form)).isEqualTo(new UiResult.Closed<FormResponse>(CloseReason.TIMED_OUT));
        assertThat(outcome(choice)).isEqualTo(new UiResult.Closed<String>(CloseReason.TIMED_OUT));
        assertThat(grid.isOpen()).isTrue();
        assertThat(backend.closed()).hasSize(4);
    }


    @Test
    void grid_canTimeOutButNeverCompletesWithAnAnswer() {
        ProbeBackend backend = backend(Grid.class);
        GridSession timed = service.show(viewer, Grid.builder(TITLE, 1).build(),
                ShowOptions.builder().timeout(Duration.ofSeconds(1)).build());
        settle();
        backend.callbacks(timed).completed(Ack.ACK);
        settle();
        assertThat(timed.isOpen()).isTrue();
        fixture.advanceTicks(25);
        assertThat(outcome(timed)).isEqualTo(new UiResult.Closed<Ack>(CloseReason.TIMED_OUT));
        GridSession untimed = service.show(viewer, Grid.builder(TITLE, 1).build(), ShowOptions.builder().noTimeout().build());
        fixture.advanceTicks(20_000);
        assertThat(untimed.isOpen()).isTrue();
    }


    @Test
    void quitBetweenReportAndEntityTask_completesOnGlobalThread() {
        ProbeBackend backend = backend(Confirm.class);
        UiSession<Boolean> session = service.show(viewer, confirm());
        settle();
        AtomicReference<Boolean> globalThread = new AtomicReference<>();
        session.result().thenAccept(ignored -> globalThread.set(RecordingScheduler.inGlobalTask()));
        backend.callbacks(session).closed(CloseReason.DISCONNECTED);
        fixture.disconnect(viewer);
        settle();
        assertThat(globalThread).hasValue(true);
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.DISCONNECTED));
        assertThat(backend.closed()).containsExactly(session);
    }


    @Test
    void unloadBeforeOpen_doesNotOpenScreen() {
        ProbeBackend backend = backend(Confirm.class);
        UiSession<Boolean> session = service.show(viewer, confirm());
        fixture.unload(service.owner());
        settle();
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.OWNER_UNLOADED));
        assertThat(backend.opened()).isEmpty();
    }


    @Test
    void serviceAndGridSession_rejectNullArguments() {
        backend(Grid.class);
        Grid grid = Grid.builder(TITLE, 1).build();
        assertThatNullPointerException().isThrownBy(() -> service.show(null, grid));
        assertThatNullPointerException().isThrownBy(() -> service.show(viewer, (Grid) null));
        assertThatNullPointerException().isThrownBy(() -> service.show(viewer, (UiIntent<Boolean>) null));
        assertThatNullPointerException().isThrownBy(() -> service.show(viewer, grid, null));
        assertThatNullPointerException().isThrownBy(() -> service.show(viewer, confirm(), null));
        GridSession session = service.show(viewer, grid);
        assertThatNullPointerException().isThrownBy(() -> session.update(null));
        assertThat(session.viewer()).isSameAs(viewer);
        assertThat(session.current().title()).isEqualTo(Text.plain("Contract"));
    }

    @Test
    void result_asyncDependentOnEntityContext_runsInViewerEntityTask() throws Throwable {
        ProbeBackend backend = backend(Confirm.class);
        UiSession<Boolean> session = service.show(viewer, confirm());
        settle();
        backend.callbacks(session).completed(true);
        settle();
        AtomicReference<Boolean> seen = new AtomicReference<>();
        onFreshThread(() -> session.result().thenAcceptAsync(
                ignored -> seen.set(RecordingScheduler.inEntityTask(viewer)), fixture.scheduler().entity(viewer)));
        settle();
        assertThat(seen).hasValue(true);
    }

    @Test
    void close_choosesOutcomeAtOnce_completesLater() {
        ProbeBackend backend = backend(Confirm.class);
        UiSession<Boolean> session = service.show(viewer, confirm());
        settle();
        session.close();
        assertThat(session.isOpen()).isFalse();
        assertThat(session.result().toCompletableFuture()).isNotDone();
        assertThat(backend.closed()).isEmpty();
        backend.callbacks(session).completed(true);
        settle();
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.CLOSED_BY_CODE));
        assertThat(backend.closed()).containsExactly(session);
    }

    @Test
    void ownerUnload_completesBeforeUnloadReturns_onUnloadingThread() {
        ProbeBackend backend = backend(Confirm.class);
        UiSession<Boolean> session = service.show(viewer, confirm());
        settle();
        Thread unloadingThread = Thread.currentThread();
        AtomicReference<Thread> observedThread = new AtomicReference<>();
        AtomicReference<Optional<RecordingScheduler.Scope>> observedScope = new AtomicReference<>();
        session.result().thenAccept(ignored -> {
            observedThread.set(Thread.currentThread());
            observedScope.set(RecordingScheduler.current());
        });
        fixture.unload(service.owner());
        assertThat(observedThread).hasValue(unloadingThread);
        assertThat(observedScope).hasValue(Optional.empty());
        assertThat(backend.closeScopes()).containsExactly(Optional.empty());
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.OWNER_UNLOADED));
    }

    @Test
    void ownerUnload_deliversOutcomeAlreadyChosen() {
        ProbeBackend backend = backend(Confirm.class);
        UiSession<Boolean> session = service.show(viewer, confirm());
        settle();
        backend.callbacks(session).completed(true);
        fixture.unload(service.owner());
        assertThat(outcome(session)).isEqualTo(new UiResult.Completed<>(true));
        assertThat(backend.closed()).containsExactly(session);
    }

    @Test
    void viewerQuitsThenOwnerUnload_beforeNextTick_completesDisconnected() {
        ProbeBackend backend = backend(Confirm.class);
        UiSession<Boolean> session = service.show(viewer, confirm());
        settle();
        fixture.disconnect(viewer);
        fixture.unload(service.owner());
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.DISCONNECTED));
        assertThat(backend.closed()).containsExactly(session);
    }

    @Test
    void showFromAnotherThread_isDeferredToViewerTask() throws Throwable {
        ProbeBackend backend = backend(Confirm.class);
        AtomicReference<UiSession<Boolean>> reference = new AtomicReference<>();
        onFreshThread(() -> reference.set(service.show(viewer, confirm())));
        UiSession<Boolean> session = reference.get();
        assertThat(backend.opened()).isEmpty();
        AtomicReference<Boolean> entityThread = new AtomicReference<>();
        session.result().thenAccept(ignored -> entityThread.set(RecordingScheduler.inEntityTask(viewer)));
        settle();
        assertThat(backend.opened()).containsExactly(session);
        onFreshThread(session::close);
        settle();
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.CLOSED_BY_CODE));
        assertThat(entityThread).hasValue(true);
    }

    @Test
    void genericGridShow_returnsGridSession() {
        ProbeBackend backend = backend(Grid.class);
        AtomicInteger calls = new AtomicInteger();
        UiIntent<Ack> intent = Grid.builder(TITLE, 1).set(0, 0, ICON, click -> calls.incrementAndGet()).build();
        UiSession<Ack> shown = service.show(viewer, intent);
        assertThat(shown).isInstanceOf(GridSession.class);
        GridSession session = (GridSession) shown;
        settle();
        session.close();
        backend.callbacks(session).clicked(GridSlot.of(0, 0), ClickKind.LEFT);
        settle();
        assertThat(calls).hasValue(0);
    }

    @Test
    void unsupportedSecondShow_keepsPreviousSessionOpen() {
        ProbeBackend backend = backend(Confirm.class);
        UiSession<Boolean> first = service.show(viewer, confirm());
        settle();
        UiSession<Ack> unsupported = service.show(viewer, Notice.builder(TITLE).build());
        settle();
        assertThat(first.isOpen()).isTrue();
        assertThat(first.result().toCompletableFuture()).isNotDone();
        assertThat(outcome(unsupported)).isEqualTo(new UiResult.Closed<Ack>(CloseReason.UNSUPPORTED));
        assertThat(backend.closed()).isEmpty();
    }

    @Test
    void grid_clickReportedBeforeBackendUpdate_runsRenderedSnapshotAction() {
        ProbeBackend backend = backend(Grid.class);
        AtomicInteger oldCalls = new AtomicInteger();
        AtomicInteger newCalls = new AtomicInteger();
        GridSession session = service.show(viewer,
                Grid.builder(TITLE, 1).set(0, 0, ICON, click -> oldCalls.incrementAndGet()).build());
        settle();
        session.update(Grid.builder(TITLE, 1).set(0, 0, ICON, click -> newCalls.incrementAndGet()).build());
        backend.callbacks(session).clicked(GridSlot.of(0, 0), ClickKind.LEFT);
        settle();
        assertThat(oldCalls).hasValue(1);
        assertThat(newCalls).hasValue(0);
        backend.callbacks(session).clicked(GridSlot.of(0, 0), ClickKind.LEFT);
        settle();
        assertThat(newCalls).hasValue(1);
    }

    @Test
    void grid_update_notifiesBackendInViewerEntityTask() {
        ProbeBackend backend = backend(Grid.class);
        Grid first = Grid.builder(TITLE, 1).build();
        Grid next = Grid.builder(Text.plain("Next"), 1).build();
        GridSession session = service.show(viewer, first);
        settle();
        session.update(next);
        assertThat(session.current()).isSameAs(next);
        assertThat(backend.updates()).isEmpty();
        settle();
        assertThat(backend.updates()).containsExactly(new ProbeBackend.Update(session, first, next, entityScope()));
    }

    @Test
    void grid_updates_reachBackendAsChainEndingInLatest() {
        ProbeBackend backend = backend(Grid.class);
        Grid first = Grid.builder(TITLE, 1).build();
        GridSession session = service.show(viewer, first);
        settle();
        session.update(Grid.builder(Text.plain("B"), 1).build());
        session.update(Grid.builder(Text.plain("C"), 1).build());
        Grid last = Grid.builder(Text.plain("D"), 1).build();
        session.update(last);
        settle();
        List<ProbeBackend.Update> updates = backend.updates();
        assertThat(updates).isNotEmpty();
        assertThat(updates.getFirst().previous()).isSameAs(first);
        assertThat(updates.getLast().next()).isSameAs(last);
        for (int i = 1; i < updates.size(); i++) {
            assertThat(updates.get(i).previous()).isSameAs(updates.get(i - 1).next());
        }
    }

    private ContainerSpec.Builder editableStorage(int size) {
        return ContainerSpec.storage(size).disposition(Disposition.RETURN_TO_CONTRIBUTORS)
                .undelivered((player, items) -> { /* Individual tests install handlers when observing returns. */ });
    }
    private static Grid bound(SlotContainer container) {
        return Grid.builder(TITLE, 1).bind(GridSlot.ofIndex(0), container, 0, BindingMode.EDITABLE).build();
    }
    private static List<CloseReason> closingReasons() {
        return List.of(CloseReason.PLAYER, CloseReason.DISCONNECTED, CloseReason.REPLACED, CloseReason.TIMED_OUT,
                CloseReason.CLOSED_BY_CODE, CloseReason.OWNER_UNLOADED);
    }
    private void closeForReason(ProbeBackend backend, GridSession session, CloseReason reason) {
        switch (reason) {
            case PLAYER -> backend.callbacks(session).closed(CloseReason.PLAYER);
            case DISCONNECTED -> fixture.disconnect((PlayerMock) session.viewer());
            case REPLACED -> service.show(session.viewer(), confirm());
            case TIMED_OUT -> fixture.advanceTicks(25);
            case CLOSED_BY_CODE -> session.close();
            case OWNER_UNLOADED -> fixture.unload(service.owner());
            default -> throw new AssertionError("Not a shown-session close reason: " + reason);
        }
        settle();
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Ack>(reason));
    }
    @Test
    void close_anyReason_neverChangesContents() {
        ProbeBackend backend = backend(Grid.class, Confirm.class);
        for (CloseReason reason : closingReasons()) {
            PlayerMock player = server.addPlayer();
            SlotContainer container = service.container(editableStorage(1).endWhenUnviewed(false).baseline(List.of(UiTestItems.stone(8))).build());
            GridSession session = service.show(player, bound(container), ShowOptions.builder().timeout(Duration.ofSeconds(1)).build());
            settle();
            closeForReason(backend, session, reason);
            // The screen close itself never moves items; unload's separate disposition may empty the container.
            assertThat(backend.atClose(session, GridSlot.ofIndex(0))).isEqualTo(UiTestItems.stone(8));
            if (reason != CloseReason.OWNER_UNLOADED) {
                assertThat(container.contents()).containsExactly(UiTestItems.stone(8));
            }
        }
    }
    @Test
    void update_rebinding_neverChangesContents() {
        backend(Grid.class);
        SlotContainer first = service.container(editableStorage(2).endWhenUnviewed(false)
                .baseline(List.of(UiTestItems.stone(3), UiTestItems.dirt(4))).build());
        SlotContainer other = service.container(editableStorage(1).baseline(List.of(UiTestItems.stone(8))).build());
        GridSession session = service.show(viewer, bound(first));
        settle();
        session.update(bound(other));
        settle();
        assertThat(first.contents()).containsExactly(UiTestItems.stone(3), UiTestItems.dirt(4));
        assertThat(other.contents()).containsExactly(UiTestItems.stone(8));
        assertThat(first.state()).isEqualTo(ContainerState.EDITABLE);
        assertThat(first.isViewed()).isFalse();
        assertThat(other.isViewed()).isTrue();
    }
    @Test
    void lastViewerCloses_endWhenUnviewed_runsDispositionOnce() {
        ProbeBackend backend = backend(Grid.class);
        SlotContainer container = service.container(editableStorage(1).initial(viewer.getUniqueId(), List.of(UiTestItems.stone(8))).build());
        GridSession first = service.show(viewer, bound(container));
        GridSession second = service.show(server.addPlayer(), bound(container));
        settle();
        first.close(); settle();
        assertThat(container.state()).isEqualTo(ContainerState.EDITABLE);
        assertThat(container.isViewed()).isTrue();
        AtomicReference<ContainerState> atCompletion = new AtomicReference<>();
        second.result().thenAccept(ignored -> {
            assertThat(backend.closed()).contains(second);
            atCompletion.set(container.state());
        });
        second.close(); second.close(); settle();
        assertThat(atCompletion).hasValue(ContainerState.ENDED);
        assertThat(container.isViewed()).isFalse();
        assertThat(viewer.getInventory().getItem(0)).isEqualTo(UiTestItems.stone(8));
        assertThat(Arrays.stream(viewer.getInventory().getStorageContents()).filter(Objects::nonNull)
                .filter(item -> !item.isEmpty()).mapToInt(ItemStack::getAmount).sum()).isEqualTo(8);
        assertThat(EditableContractSupport.completed(container.end())).isEqualTo(EndOutcome.RETURNED);
        assertThat(backend.closed()).containsExactly(first, second);
    }
    @Test
    void lastViewerCloses_everyReason_endsAfterBackendClose() {
        ProbeBackend backend = backend(Grid.class, Confirm.class);
        for (CloseReason reason : closingReasons()) {
            AtomicInteger dispositions = new AtomicInteger();
            SlotContainer container = service.container(editableStorage(1).baseline(List.of(UiTestItems.stone(8)))
                    .undelivered((player, items) -> dispositions.incrementAndGet()).build());
            GridSession session = service.show(server.addPlayer(), bound(container), ShowOptions.builder().timeout(Duration.ofSeconds(1)).build());
            settle();
            AtomicReference<ContainerState> atCompletion = new AtomicReference<>();
            session.result().thenAccept(ignored -> atCompletion.set(container.state()));
            closeForReason(backend, session, reason);
            assertThat(backend.atClose(session, GridSlot.ofIndex(0))).isEqualTo(UiTestItems.stone(8));
            assertThat(atCompletion).hasValue(ContainerState.ENDED);
            assertThat(dispositions).hasValue(1);
        }
    }
    @Test
    void endWhenUnviewedFalse_lastClose_keepsContainer() {
        backend(Grid.class);
        SlotContainer container = service.container(editableStorage(1).endWhenUnviewed(false).baseline(List.of(UiTestItems.stone(8))).build());
        GridSession session = service.show(viewer, bound(container));
        settle(); session.close(); settle();
        assertThat(container.isViewed()).isFalse();
        assertThat(container.state()).isEqualTo(ContainerState.EDITABLE);
        assertThat(container.get(0)).isEqualTo(UiTestItems.stone(8));
    }
    @Test
    void ownerUnload_endsOwnContainersOnly() {
        backend(Grid.class);
        SlotContainer own = service.container(editableStorage(1).endWhenUnviewed(false).build());
        SlotContainer unviewed = service.container(editableStorage(1).endWhenUnviewed(false).build());
        SlotContainer other = fixture.service("OtherModule").container(editableStorage(1).build());
        CompletableFuture<StoreAnswer> write = new CompletableFuture<>();
        SlotContainer committing = service.container(editableStorage(1).disposition(Disposition.COMMIT).store(snapshot -> write)
                .initial(viewer.getUniqueId(), List.of(UiTestItems.stone(8))).build());
        CompletionStage<CommitOutcome> pending = committing.commit();
        GridSession session = service.show(viewer, bound(own));
        settle();
        fixture.unload(service.owner());
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Ack>(CloseReason.OWNER_UNLOADED));
        assertThat(own.state()).isEqualTo(ContainerState.ENDED);
        assertThat(unviewed.state()).isEqualTo(ContainerState.ENDED);
        assertThat(committing.state()).isEqualTo(ContainerState.ENDED);
        assertThat(EditableContractSupport.completed(pending)).isEqualTo(CommitOutcome.UNKNOWN);
        assertThat(other.state()).isEqualTo(ContainerState.EDITABLE);
        write.complete(StoreAnswer.refused(List.of(ItemStack.empty())));
        settle();
        assertThat(committing.state()).isEqualTo(ContainerState.ENDED);
    }
    @Test
    void ownerUnload_startingCommitDisposition_keepsUnknownWriteWithStore() {
        AtomicInteger writes = new AtomicInteger();
        CompletableFuture<StoreAnswer> answer = new CompletableFuture<>();
        SlotContainer container = service.container(editableStorage(1).disposition(Disposition.COMMIT)
                .initial(viewer.getUniqueId(), List.of(UiTestItems.stone(8)))
                .store(snapshot -> { writes.incrementAndGet(); return answer; }).build());
        fixture.unload(service.owner());
        assertThat(container.state()).isEqualTo(ContainerState.ENDED);
        assertThat(writes).hasValue(1);
        assertThat(EditableContractSupport.completed(container.end())).isEqualTo(EndOutcome.UNKNOWN_KEPT_BY_STORE);
        answer.complete(StoreAnswer.refused(List.of(ItemStack.empty()))); settle();
        assertThat(container.state()).isEqualTo(ContainerState.ENDED);
        assertThat(viewer.getInventory().getStorageContents()).allSatisfy(item -> assertThat(item == null || item.isEmpty()).isTrue());
    }
    @Test
    void ownerUnload_afterRefusalBeforeRefundDelivery_endsSynchronously() {
        backend(Grid.class);
        SlotContainer container = service.container(editableStorage(1).endWhenUnviewed(false)
                .initial(viewer.getUniqueId(), List.of(UiTestItems.stone(8)))
                .store(snapshot -> CompletableFuture.completedFuture(StoreAnswer.refused(List.of(ItemStack.empty())))).build());
        GridSession session = service.show(viewer, bound(container)); settle();
        AtomicReference<ContainerState> atCompletion = new AtomicReference<>();
        session.result().thenAccept(ignored -> atCompletion.set(container.state()));
        CompletionStage<CommitOutcome> pending = container.commit();
        assertThat(pending.toCompletableFuture()).isNotDone();
        fixture.unload(service.owner());
        assertThat(atCompletion).hasValue(ContainerState.ENDED);
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Ack>(CloseReason.OWNER_UNLOADED));
        assertThat(container.state()).isEqualTo(ContainerState.ENDED);
        assertThat(viewer.getInventory().getItem(0)).isEqualTo(UiTestItems.stone(8));
        assertThat(EditableContractSupport.completed(pending)).isEqualTo(CommitOutcome.REFUSED);
        settle();
        assertThat(EditableContractSupport.completed(container.end())).isEqualTo(EndOutcome.REFUSED_RETURNED);
        assertThat(container.state()).isEqualTo(ContainerState.ENDED);
        assertThat(viewer.getInventory().getItem(0)).isEqualTo(UiTestItems.stone(8));
    }
    @Test
    void showGridBindingEndedContainer_throws() {
        backend(Grid.class);
        SlotContainer container = service.container(ContainerSpec.template(1).build());
        Grid grid = bound(container);
        container.end();
        assertThatThrownBy(() -> service.show(viewer, grid)).isInstanceOf(IllegalStateException.class);
    }
    @Test
    void deliver_fullInventory_dropsRest() {
        for (int i = 0; i < 36; i++) { viewer.getInventory().setItem(i, UiTestItems.dirt(64)); }
        viewer.getInventory().setItem(5, UiTestItems.stone(62));
        CompletionStage<Delivery> result = service.deliver(viewer, List.of(UiTestItems.stone(8)));
        AtomicReference<Boolean> entity = new AtomicReference<>();
        result.thenAccept(ignored -> entity.set(RecordingScheduler.inEntityTask(viewer)));
        settle();
        Delivery delivery = EditableContractSupport.completed(result);
        assertThat(delivery.given()).containsExactly(UiTestItems.stone(2));
        assertThat(delivery.dropped()).containsExactly(UiTestItems.stone(6));
        assertThat(delivery.undelivered()).isEmpty();
        assertThat(viewer.getInventory().getItem(5)).isEqualTo(UiTestItems.stone(64));
        assertThat(viewer.getWorld().getEntitiesByClass(Item.class)).anySatisfy(item ->
                assertThat(item.getItemStack()).isEqualTo(UiTestItems.stone(6)));
        assertThat(entity).hasValue(true);
    }
    @Test
    void deliver_offline_allUndelivered() {
        ItemStack source = UiTestItems.stone(8);
        CompletionStage<Delivery> result = service.deliver(viewer, List.of(source));
        source.setAmount(1);
        fixture.disconnect(viewer);
        settle();
        Delivery delivery = EditableContractSupport.completed(result);
        assertThat(delivery.given()).isEmpty();
        assertThat(delivery.dropped()).isEmpty();
        assertThat(delivery.undelivered()).containsExactly(UiTestItems.stone(8));
    }
    @Test
    void closeInsideAction_takesEffectAfterHandler() {
        ProbeBackend backend = backend(Grid.class);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Boolean> deferred = new AtomicReference<>();
        GridSession session = service.show(viewer, Grid.builder(TITLE, 1).set(0, 0, ICON, click -> {
            calls.incrementAndGet(); click.session().close();
            deferred.set(!click.session().isOpen() && backend.closed().isEmpty()
                    && !click.session().result().toCompletableFuture().isDone());
        }).build());
        settle(); backend.callbacks(session).clicked(GridSlot.ofIndex(0), ClickKind.LEFT); settle();
        assertThat(calls).hasValue(1);
        assertThat(deferred).hasValue(true);
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Ack>(CloseReason.CLOSED_BY_CODE));
        assertThat(backend.closed()).containsExactly(session);
    }
    @Test
    void gesture_usesRenderedBindings_andTerminalSessionMovesNothing() {
        backend(Grid.class);
        SlotContainer old = service.container(editableStorage(1).endWhenUnviewed(false).baseline(List.of(UiTestItems.stone(8))).build());
        SlotContainer next = service.container(editableStorage(1).endWhenUnviewed(false).baseline(List.of(UiTestItems.dirt(3))).build());
        GridSession session = service.show(viewer, bound(old)); settle();
        session.update(bound(next));
        InMemoryViewerItems side = new InMemoryViewerItems(36);
        assertThat(fixture.gesture(session, side, new Gesture.Pickup(GridSlot.ofIndex(0), false))).isTrue();
        assertThat(side.cursor()).isEqualTo(UiTestItems.stone(8));
        assertThat(next.get(0)).isEqualTo(UiTestItems.dirt(3));
        settle(); session.close(); side.cursor(ItemStack.empty());
        assertThat(fixture.gesture(session, side, new Gesture.Pickup(GridSlot.ofIndex(0), false))).isFalse();
        assertThat(next.get(0)).isEqualTo(UiTestItems.dirt(3));
    }
    @Test
    void closeInsidePolicy_takesEffectAfterGestureHandler() {
        ProbeBackend backend = backend(Grid.class);
        AtomicReference<GridSession> shown = new AtomicReference<>();
        AtomicReference<Boolean> closedInsidePolicy = new AtomicReference<>();
        SlotContainer container = service.container(editableStorage(1).endWhenUnviewed(false).policy(request -> {
            shown.get().close();
            closedInsidePolicy.set(!shown.get().isOpen() && backend.closed().isEmpty()
                    && !shown.get().result().toCompletableFuture().isDone());
            return TransferDecision.allow();
        }).build());
        GridSession session = service.show(viewer, bound(container)); shown.set(session); settle();
        InMemoryViewerItems side = new InMemoryViewerItems(36); side.cursor(UiTestItems.stone(8));
        assertThat(fixture.gesture(session, side, new Gesture.Place(GridSlot.ofIndex(0), false))).isTrue();
        assertThat(closedInsidePolicy).hasValue(true);
        assertThat(backend.closed()).isEmpty();
        settle();
        assertThat(backend.closed()).containsExactly(session);
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Ack>(CloseReason.CLOSED_BY_CODE));
    }
    @Test
    void endWhileViewed_showsEmptyAndRefusesMoreInput() {
        ProbeBackend backend = backend(Grid.class);
        SlotContainer container = service.container(ContainerSpec.template(1).baseline(List.of(UiTestItems.stone(8))).build());
        GridSession session = service.show(viewer, bound(container)); settle();
        assertThat(fixture.shown(session, GridSlot.ofIndex(0))).isEqualTo(UiTestItems.stone(8));
        container.end(); fixture.advanceTicks(1);
        assertThat(fixture.shown(session, GridSlot.ofIndex(0)).isEmpty()).isTrue();
        assertThat(fixture.gesture(session, new InMemoryViewerItems(36), new Gesture.Pickup(GridSlot.ofIndex(0), false))).isFalse();
    }
    @Test
    void openGrid_neverTouchesCursor() {
        backend(Grid.class);
        viewer.setItemOnCursor(UiTestItems.dirt(3));
        SlotContainer container = service.container(editableStorage(1).baseline(List.of(UiTestItems.stone(8))).build());
        Player observed = spy(viewer);
        service.show(observed, bound(container)); settle();
        verify(observed, never()).getItemOnCursor();
        verify(observed, never()).setItemOnCursor(any());
        assertThat(viewer.getItemOnCursor()).isEqualTo(UiTestItems.dirt(3));
        assertThat(container.get(0)).isEqualTo(UiTestItems.stone(8));
    }

    @Test
    void secondGridBindingSameContainer_handsOverWithoutEnding() {
        ProbeBackend backend = backend(Grid.class);
        AtomicInteger writes = new AtomicInteger();
        SlotContainer container = service.container(editableStorage(1).disposition(Disposition.COMMIT).store(snapshot -> {
            writes.incrementAndGet(); return CompletableFuture.completedFuture(StoreAnswer.written());
        }).build());
        GridSession first = service.show(viewer, bound(container));
        settle();
        GridSession second = service.show(viewer, Grid.builder(Text.plain("Next"), 1)
                .bind(GridSlot.ofIndex(0), container, 0, BindingMode.EDITABLE).build());
        settle();
        assertThat(outcome(first)).isEqualTo(new UiResult.Closed<Ack>(CloseReason.REPLACED));
        assertThat(second.isOpen()).isTrue();
        assertThat(backend.opened()).containsExactly(first, second);
        assertThat(container.state()).isEqualTo(ContainerState.EDITABLE);
        assertThat(container.isViewed()).isTrue();
        assertThat(writes).hasValue(0);
    }
    @Test
    void openAfterBoundContainerEnded_showsNothingAndRefuses() {
        ProbeBackend backend = backend(Grid.class);
        SlotContainer container = service.container(editableStorage(1).endWhenUnviewed(false)
                .baseline(List.of(UiTestItems.stone(8))).build());
        GridSession session = service.show(viewer, bound(container));
        container.end();
        settle();
        assertThat(backend.opened()).containsExactly(session);
        assertThat(session.isOpen()).isTrue();
        assertThat(session.result().toCompletableFuture()).isNotDone();
        assertThat(fixture.shown(session, GridSlot.ofIndex(0)).isEmpty()).isTrue();
        assertThat(fixture.gesture(session, new InMemoryViewerItems(36), new Gesture.Pickup(GridSlot.ofIndex(0), false))).isFalse();
    }
    @Test
    void update_rebindingAwayFromContainer_endsItWhenUnviewed() {
        backend(Grid.class);
        SlotContainer first = service.container(editableStorage(1).build());
        SlotContainer next = service.container(editableStorage(1).build());
        GridSession session = service.show(viewer, bound(first));
        settle();
        InMemoryViewerItems side = new InMemoryViewerItems(36);
        side.cursor(UiTestItems.stone(8));
        assertThat(fixture.gesture(session, side, new Gesture.Place(GridSlot.ofIndex(0), false))).isTrue();
        session.update(bound(next));
        settle();
        assertThat(first.state()).isEqualTo(ContainerState.ENDED);
        assertThat(viewer.getInventory().getItem(0)).isEqualTo(UiTestItems.stone(8));
        assertThat(next.isViewed()).isTrue();
    }
    @Test
    void update_pagingWithinContainer_keepsItViewed() {
        backend(Grid.class);
        SlotContainer container = service.container(editableStorage(18).build());
        GridSession session = service.show(viewer, Grid.builder(TITLE, 1).bindRange(0, 0, 9, container, 0, BindingMode.EDITABLE).build());
        settle();
        session.update(Grid.builder(TITLE, 1).bindRange(0, 0, 9, container, 9, BindingMode.EDITABLE).build());
        settle();
        assertThat(container.state()).isEqualTo(ContainerState.EDITABLE);
        assertThat(container.isViewed()).isTrue();
    }
    @Test
    void update_rebindingAwayWithEndWhenUnviewedFalse_keepsContainer() {
        backend(Grid.class);
        SlotContainer first = service.container(editableStorage(1).endWhenUnviewed(false).build());
        SlotContainer next = service.container(editableStorage(1).build());
        GridSession session = service.show(viewer, bound(first));
        settle();
        session.update(bound(next));
        settle();
        assertThat(first.state()).isEqualTo(ContainerState.EDITABLE);
        assertThat(first.isViewed()).isFalse();
        assertThat(next.isViewed()).isTrue();
    }
    @Test
    void showFromResultDependentDuringUnload_isClosedOwnerUnloaded() {
        ProbeBackend backend = backend(Confirm.class);
        UiSession<Boolean> first = service.show(viewer, confirm());
        settle();
        AtomicReference<UiSession<Boolean>> second = new AtomicReference<>();
        first.result().thenAccept(ignored -> second.set(service.show(viewer, confirm())));
        fixture.unload(service.owner());
        assertThat(outcome(second.get())).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.OWNER_UNLOADED));
        settle();
        assertThat(backend.opened()).containsExactly(first);
    }
    @Test
    void containerDuringUnload_throws() {
        backend(Confirm.class);
        UiSession<Boolean> first = service.show(viewer, confirm());
        settle();
        ContainerSpec spec = editableStorage(1).build();
        AtomicReference<Throwable> observed = new AtomicReference<>();
        first.result().thenAccept(ignored -> {
            try {
                service.container(spec);
            } catch (Throwable failure) {
                observed.set(failure);
            }
        });
        fixture.unload(service.owner());
        assertThat(observed.get()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.container(spec)).isInstanceOf(IllegalStateException.class);
    }
    @Test
    void ownerUnload_returnDisposition_deliversToOnlineContributorBeforeUnloadReturns() {
        SlotContainer container = service.container(editableStorage(1).endWhenUnviewed(false).build());
        InMemoryViewerItems side = new InMemoryViewerItems(36);
        side.cursor(UiTestItems.stone(8));
        assertThat(fixture.engine().apply(bound(container), viewer, side, new Gesture.Place(GridSlot.ofIndex(0), false))).isTrue();
        fixture.unload(service.owner());
        assertThat(viewer.getInventory().getItem(0)).isEqualTo(UiTestItems.stone(8));
        assertThat(EditableContractSupport.completed(container.end())).isEqualTo(EndOutcome.RETURNED);
    }
    @Test
    void deliver_afterOwnerUnload_completesAllUndelivered() {
        fixture.unload(service.owner());
        Delivery delivery = EditableContractSupport.completed(service.deliver(viewer, List.of(UiTestItems.stone(3))));
        assertThat(delivery.undelivered()).containsExactly(UiTestItems.stone(3));
        assertThat(delivery.given()).isEmpty();
        assertThat(delivery.dropped()).isEmpty();
        assertThat(viewer.getInventory().getStorageContents()).allSatisfy(item -> assertThat(item == null || item.isEmpty()).isTrue());
        settle();
        assertThat(viewer.getInventory().getStorageContents()).allSatisfy(item -> assertThat(item == null || item.isEmpty()).isTrue());
    }
    @Test
    void return_contributorQuitsBeforeDelivery_goesToUndeliveredOnce() {
        List<UUID> batches = new ArrayList<>();
        List<ItemStack> returned = new ArrayList<>();
        SlotContainer container = service.container(editableStorage(1).endWhenUnviewed(false)
                .undelivered((player, items) -> { batches.add(player); returned.addAll(items); }).build());
        InMemoryViewerItems side = new InMemoryViewerItems(36);
        side.cursor(UiTestItems.stone(8));
        assertThat(fixture.engine().apply(bound(container), viewer, side, new Gesture.Place(GridSlot.ofIndex(0), false))).isTrue();
        CompletionStage<EndOutcome> ending = container.end();
        fixture.disconnect(viewer);
        settle();
        assertThat(batches).containsExactly(viewer.getUniqueId());
        assertThat(returned).containsExactly(UiTestItems.stone(8));
        assertThat(EditableContractSupport.completed(ending)).isEqualTo(EndOutcome.RETURNED);
    }
    @Test
    void deliver_neverFillsArmourOrOffHand() {
        for (int i = 0; i < 36; i++) { viewer.getInventory().setItem(i, UiTestItems.dirt(64)); }
        CompletionStage<Delivery> result = service.deliver(viewer, List.of(UiTestItems.stone(5)));
        settle();
        Delivery delivery = EditableContractSupport.completed(result);
        assertThat(delivery.given()).isEmpty();
        assertThat(delivery.dropped()).containsExactly(UiTestItems.stone(5));
        assertThat(viewer.getInventory().getArmorContents()).allSatisfy(item -> assertThat(item == null || item.isEmpty()).isTrue());
        assertThat(viewer.getInventory().getItemInOffHand().isEmpty()).isTrue();
    }

    private Optional<RecordingScheduler.Scope> entityScope() {
        return Optional.of(new RecordingScheduler.Scope(RecordingScheduler.Scope.Kind.ENTITY, viewer.getUniqueId()));
    }

    private static String text(LogRecord record) { return new SimpleFormatter().formatMessage(record); }

    private static void onFreshThread(Runnable task) throws Throwable {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                task.run();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "ui-contract-caller");
        caller.start();
        caller.join(10_000);
        if (caller.isAlive()) {
            fail("ui-contract-caller did not finish within 10 s");
        }
        if (failure.get() != null) {
            throw failure.get();
        }
    }
}
