package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.LogRecord;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Reusable display-contract assertions for the in-memory service and later backend implementations. */
public abstract class AbstractUiServiceContractTest {
    protected static final Text TITLE = Text.plain("Contract");
    protected static final ItemSpec ICON = ItemSpec.builder(Key.key("minecraft", "stone")).build();
    protected Fixture fixture;
    protected ServerMock server;
    protected PlayerMock viewer;
    protected UiService service;

    /**
     * Creates an isolated registry shared by all owners, with registerable rendering backends, a
     * controllable scheduler and disconnectable MockBukkit players. Backend subclasses supply their
     * real service and adapt registration, owner unload and quit delivery, while reusing the same
     * probes. advanceTicks must settle due entity/global tasks deterministically. Thread observations
     * must report execution inside the relevant scheduled task, not merely ownership of the main
     * thread (ManualScheduler considers every tick context owned by the same main thread).
     */
    protected abstract Fixture createFixture();

    /** Test-only lifecycle and observation controls, not additions to the public UiService API. */
    public interface Fixture extends AutoCloseable {
        UiService service(String owner);
        void register(ProbeBackend backend);
        void advanceTicks(long ticks);
        void disconnect(PlayerMock player);
        void unload(String owner);
        /** The scheduler wrapper used for every scheduled service step. */
        RecordingScheduler scheduler();
        List<LogRecord> warnings();
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
        @Override public boolean supports(Class<? extends UiIntent<?>> kind, Player player) { return kinds.contains(kind); }
        @Override public <R> void open(UiSession<R> session, BackendCallbacks<R> reports) {
            callbacks.put(session, reports);
            opened.add(session);
            operations.add("open");
        }
        @Override public void close(UiSession<?> session) { closed.add(session); operations.add("close"); }
        public Set<Class<?>> kinds() { return kinds; }
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

    protected void settle() { fixture.advanceTicks(3); }
    protected static <R> UiResult<R> outcome(UiSession<R> session) { return session.result().toCompletableFuture().join(); }
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
        assertThat(outcome(session)).isEqualTo(new UiResult.Completed<>(true));
    }

    @Test
    void backend_highestPriorityWins() {
        ProbeBackend chest = new ProbeBackend("chest", 0, Confirm.class);
        ProbeBackend dialog = new ProbeBackend("dialog", 100, Confirm.class);
        fixture.register(chest);
        fixture.register(dialog);
        UiSession<Boolean> session = service.show(viewer, confirm());
        settle();
        assertThat(dialog.opened()).containsExactly(session);
        assertThat(chest.opened()).isEmpty();
        assertThat(session.isOpen()).isTrue();
    }

    @Test
    void backend_equalPriority_failsRegistration() {
        fixture.register(new ProbeBackend("one", 100, Confirm.class));
        assertThatThrownBy(() -> fixture.register(new ProbeBackend("two", 100, Notice.class, Confirm.class)))
                .isInstanceOf(IllegalStateException.class);
        fixture.register(new ProbeBackend("disjoint", 100, Grid.class));
        assertThat(service.owner()).isEqualTo("FirstModule");
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
        settle();
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
        settle();
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.OWNER_UNLOADED));
        assertThat(backend.opened()).isEmpty();
    }

    @Test
    void close_isIdempotent() {
        ProbeBackend backend = backend(Confirm.class);
        UiSession<Boolean> session = service.show(viewer, confirm());
        settle();
        session.close();
        session.close();
        settle();
        session.close();
        settle();
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.CLOSED_BY_CODE));
        assertThat(backend.closed()).containsExactly(session);
    }

    @Test
    void confirm_dismissed_neverCompletesTrue() {
        ProbeBackend backend = backend(Confirm.class);
        UiSession<Boolean> session = service.show(viewer, confirm());
        settle();
        backend.callbacks(session).completed(false);
        settle();
        assertThat(outcome(session)).isEqualTo(new UiResult.Completed<>(false));
        UiSession<Boolean> distinguishable = service.show(viewer, confirm());
        settle();
        backend.callbacks(distinguishable).closed(CloseReason.PLAYER);
        settle();
        assertThat(outcome(distinguishable)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.PLAYER));
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
        settle();
        session.update(Grid.builder(Text.plain("Later"), 2).build());
        settle();
        assertThat(session.current()).isSameAs(first);
        assertThat(backend.closed()).containsExactly(session);
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
            assertThat(record.getMessage()).contains(service.owner());
            assertThat(record.getThrown()).isSameAs(thrown);
        });
    }
}
