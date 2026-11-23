package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.scheduling.ManualScheduler;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.TaskRetiredException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.bukkit.entity.Player;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Test-only service. Deliberately extensible for the additive editable-slot contract. */
class InMemoryUiService implements UiService {
    private final Registry registry;
    private final String owner;
    private boolean unloaded;

    InMemoryUiService(Registry registry, String owner) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    @Override public String owner() { return owner; }
    @Override public <R> UiSession<R> show(Player viewer, UiIntent<R> intent) { return show(viewer, intent, ShowOptions.defaults()); }
    @Override public GridSession show(Player viewer, Grid grid) { return show(viewer, grid, ShowOptions.defaults()); }
    @Override public GridSession show(Player viewer, Grid grid, ShowOptions options) {
        return (GridSession) show(viewer, (UiIntent<Ack>) Objects.requireNonNull(grid, "grid"), options);
    }

    @Override
    public <R> UiSession<R> show(Player viewer, UiIntent<R> intent, ShowOptions options) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(options, "options");
        synchronized (registry) {
            MemorySession<R> session = newSession(viewer, intent);
            registry.sessions.add(session);
            registry.dispatch(viewer, () -> open(session, options));
            return session;
        }
    }

    @SuppressWarnings("unchecked") // Grid is sealed to UiIntent<Ack>; runtime narrowing preserves R.
    private <R> MemorySession<R> newSession(Player viewer, UiIntent<R> intent) {
        if (intent instanceof Grid grid) {
            return (MemorySession<R>) new MemoryGridSession(this, viewer, grid);
        }
        return new MemorySession<>(this, viewer, intent);
    }

    @SuppressWarnings("unchecked") // Class.getClass loses the UiIntent<R> type arguments, not the intent kind.
    private <R> void open(MemorySession<R> session, ShowOptions options) {
        if (!session.open) {
            return;
        }
        if (unloaded) {
            session.finish(new UiResult.Closed<>(CloseReason.OWNER_UNLOADED));
            return;
        }
        if (!session.viewer.isOnline()) {
            session.finish(new UiResult.Closed<>(CloseReason.DISCONNECTED));
            return;
        }
        MemorySession<?> previous = registry.active.get(session.viewer.getUniqueId());
        if (previous != null) {
            previous.finish(new UiResult.Closed<>(CloseReason.REPLACED));
        }
        Class<? extends UiIntent<?>> kind = (Class<? extends UiIntent<?>>) session.intent.getClass();
        UiBackend selected = null;
        for (UiBackend candidate : registry.backends) {
            if (candidate.supports(kind, session.viewer) && (selected == null || candidate.priority() > selected.priority())) {
                selected = candidate;
            }
        }
        if (selected == null) {
            session.finish(new UiResult.Closed<>(CloseReason.UNSUPPORTED));
            return;
        }
        registry.active.put(session.viewer.getUniqueId(), session);
        session.backend = selected;
        selected.open(session, session.callbacks());
        Duration timeout = options.timeout().orElse(session.intent instanceof Grid ? Duration.ZERO : Duration.ofMinutes(10));
        if (session.intent instanceof Grid && options.noTimeout()) {
            timeout = Duration.ZERO;
        }
        if (session.open && !timeout.isZero()) {
            session.timeout = registry.scheduler.global().runLater(timeout, () -> session.requestClose(CloseReason.TIMED_OUT));
        }
    }

    /** Shared player registry and virtual clock, independent of any one module's task lifetime. */
    static final class Registry implements AbstractUiServiceContractTest.Fixture {
        private final ManualScheduler.Engine clock = new ManualScheduler.Engine();
        private final Scheduler scheduler = clock.scheduler("UiRuntime");
        private final Map<String, InMemoryUiService> owners = new HashMap<>();
        private final List<AbstractUiServiceContractTest.ProbeBackend> backends = new ArrayList<>();
        private final List<MemorySession<?>> sessions = new ArrayList<>();
        private final Map<UUID, MemorySession<?>> active = new HashMap<>();
        private final List<LogRecord> warnings = new ArrayList<>();
        private final ThreadLocal<Player> entityTask = new ThreadLocal<>();
        private final ThreadLocal<Boolean> globalTask = new ThreadLocal<>();

        @Override public synchronized UiService service(String owner) {
            Objects.requireNonNull(owner, "owner");
            return owners.computeIfAbsent(owner, name -> new InMemoryUiService(this, name));
        }

        @Override public synchronized void register(AbstractUiServiceContractTest.ProbeBackend backend) {
            Objects.requireNonNull(backend, "backend");
            for (AbstractUiServiceContractTest.ProbeBackend existing : backends) {
                if (existing.priority() == backend.priority()
                        && existing.kinds().stream().anyMatch(backend.kinds()::contains)) {
                    throw new IllegalStateException("Equal backend priorities for a shared intent kind");
                }
            }
            backends.add(backend);
        }

        private void dispatch(Player viewer, Runnable action) {
            if (!viewer.isOnline()) {
                onGlobal(action);
                return;
            }
            TaskHandle scheduled = scheduler.entity(viewer).run(() -> {
                if (!viewer.isOnline()) {
                    onGlobal(action);
                    return;
                }
                entityTask.set(viewer);
                try {
                    synchronized (this) {
                        action.run();
                    }
                } finally {
                    entityTask.remove();
                }
            });
            scheduled.completion().whenComplete((ignored, failure) -> {
                if (failure instanceof TaskRetiredException) {
                    onGlobal(action);
                }
            });
        }

        private void onGlobal(Runnable action) {
            scheduler.global().run(() -> {
                globalTask.set(true);
                try {
                    synchronized (this) {
                        action.run();
                    }
                } finally {
                    globalTask.remove();
                }
            });
        }

        @Override public void advanceTicks(long ticks) { clock.advanceTicks(ticks); }
        @Override public synchronized void disconnect(PlayerMock player) {
            player.disconnect();
            clock.retire(player);
            for (MemorySession<?> session : List.copyOf(sessions)) {
                if (session.viewer == player) {
                    session.requestClose(CloseReason.DISCONNECTED);
                }
            }
        }
        @Override public synchronized void unload(String owner) {
            InMemoryUiService service = (InMemoryUiService) service(owner);
            service.unloaded = true;
            for (MemorySession<?> session : List.copyOf(sessions)) {
                if (session.service == service) {
                    session.requestClose(CloseReason.OWNER_UNLOADED);
                }
            }
        }
        @Override public boolean inEntityTask(Player player) {
            return entityTask.get() == player && scheduler.entity(player).isCurrentThread();
        }
        @Override public boolean inGlobalTask() { return Boolean.TRUE.equals(globalTask.get()) && scheduler.global().isCurrentThread(); }
        @Override public synchronized List<LogRecord> warnings() { return List.copyOf(warnings); }
        @Override public void close() { clock.close(); }
    }

    private static class MemorySession<R> implements UiSession<R> {
        final InMemoryUiService service;
        final Registry registry;
        final Player viewer;
        private final UiIntent<R> intent;
        private final CompletableFuture<UiResult<R>> completion = new CompletableFuture<>();
        private UiBackend backend;
        private TaskHandle timeout;
        boolean open = true;

        MemorySession(InMemoryUiService service, Player viewer, UiIntent<R> intent) {
            this.service = service;
            registry = service.registry;
            this.viewer = viewer;
            this.intent = intent;
        }

        @Override public Player viewer() { return viewer; }
        @Override public UiIntent<R> intent() { return intent; }
        @Override public CompletionStage<UiResult<R>> result() { return completion.minimalCompletionStage(); }
        @Override public boolean isOpen() { synchronized (registry) { return open; } }
        @Override public void close() { requestClose(CloseReason.CLOSED_BY_CODE); }

        final void requestClose(CloseReason reason) {
            registry.dispatch(viewer, () -> finish(new UiResult.Closed<>(reason)));
        }

        final void finish(UiResult<R> result) {
            if (!open) {
                return;
            }
            open = false;
            if (timeout != null) {
                timeout.cancel();
            }
            registry.active.remove(viewer.getUniqueId(), this);
            registry.sessions.remove(this);
            if (backend != null) {
                backend.close(this);
            }
            completion.complete(result);
        }

        private UiBackend.BackendCallbacks<R> callbacks() {
            return new UiBackend.BackendCallbacks<>() {
                @Override public void completed(R value) {
                    Objects.requireNonNull(value, "value");
                    if (!(intent instanceof Grid)) {
                        registry.dispatch(viewer, () -> finish(new UiResult.Completed<>(value)));
                    }
                }
                @Override public void closed(CloseReason reason) {
                    requestClose(Objects.requireNonNull(reason, "reason"));
                }
                @Override public void clicked(GridSlot slot, ClickKind kind) {
                    Objects.requireNonNull(slot, "slot");
                    Objects.requireNonNull(kind, "kind");
                    if (MemorySession.this instanceof MemoryGridSession grid) {
                        registry.dispatch(viewer, () -> grid.clicked(slot, kind));
                    }
                }
            };
        }
    }

    private static final class MemoryGridSession extends MemorySession<Ack> implements GridSession {
        private Grid current;

        MemoryGridSession(InMemoryUiService service, Player viewer, Grid grid) {
            super(service, viewer, grid);
            current = grid;
        }
        @Override public void update(Grid next) {
            Objects.requireNonNull(next, "next");
            synchronized (registry) {
                if (!open) {
                    return;
                }
                if (next.rows() != current.rows()) {
                    throw new IllegalArgumentException("A grid update must retain the row count");
                }
                current = next;
            }
        }
        @Override public Grid current() { synchronized (registry) { return current; } }

        private void clicked(GridSlot slot, ClickKind kind) {
            if (!open) {
                return;
            }
            current.item(slot).flatMap(GridItem::action).ifPresent(action -> {
                try {
                    action.onClick(new Click(viewer, slot, kind, this));
                } catch (Exception thrown) {
                    LogRecord record = new LogRecord(Level.WARNING, "Grid action failed for owner " + service.owner());
                    record.setThrown(thrown);
                    registry.warnings.add(record);
                }
            });
        }
    }

    private record Click(Player viewer, GridSlot slot, ClickKind kind, GridSession session) implements GridClick { }
}
