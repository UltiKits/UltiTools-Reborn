package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.scheduling.ManualScheduler;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.TaskRetiredException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
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
        MemorySession<R> session = newSession(viewer, intent);
        boolean ownerUnloaded;
        synchronized (registry) {
            ownerUnloaded = unloaded;
            registry.sessions.add(session);
        }
        if (ownerUnloaded) {
            session.choose(new UiResult.Closed<>(CloseReason.OWNER_UNLOADED));
            session.closeAndComplete();
        } else if (!viewer.isOnline()) {
            session.requestClose(CloseReason.DISCONNECTED);
        } else {
            registry.dispatch(viewer, () -> openTask(session, options));
        }
        return session;
    }

    @SuppressWarnings("unchecked") // Grid is sealed to UiIntent<Ack>; runtime narrowing preserves R.
    private <R> MemorySession<R> newSession(Player viewer, UiIntent<R> intent) {
        if (intent instanceof Grid grid) {
            return (MemorySession<R>) new MemoryGridSession(this, viewer, grid);
        }
        return new MemorySession<>(this, viewer, intent);
    }

    private <R> void openTask(MemorySession<R> session, ShowOptions options) {
        if (!session.isOpen()) {
            return;
        }
        if (!session.viewer.isOnline()) {
            session.requestClose(CloseReason.DISCONNECTED);
            return;
        }
        UiBackend selected = select(session);
        if (selected == null) {
            session.requestClose(CloseReason.UNSUPPORTED);
            return;
        }
        replacePrevious(session);
        session.openWith(selected);
        scheduleTimeout(session, options);
    }

    private UiBackend select(MemorySession<?> session) {
        synchronized (registry) {
            UiBackend selected = null;
            for (UiBackend candidate : registry.backends) {
                if (candidate.supports(session.intent(), session.viewer)
                        && (selected == null || candidate.priority() > selected.priority())) {
                    selected = candidate;
                }
            }
            return selected;
        }
    }

    private void replacePrevious(MemorySession<?> session) {
        MemorySession<?> previous;
        synchronized (registry) {
            previous = registry.active.get(session.viewer.getUniqueId());
        }
        if (previous != null) {
            previous.choose(new UiResult.Closed<>(CloseReason.REPLACED));
            previous.closeAndComplete();
        }
    }

    private void scheduleTimeout(MemorySession<?> session, ShowOptions options) {
        synchronized (registry) {
            if (session.isOpen()) {
                timeoutFor(session.intent(), options).ifPresent(timeout -> session.timeout = registry.scheduler.global()
                        .runLater(timeout, () -> session.requestClose(CloseReason.TIMED_OUT)));
            }
        }
    }

    private static Optional<Duration> timeoutFor(UiIntent<?> intent, ShowOptions options) {
        return intent instanceof Grid ? options.timeout() : Optional.of(options.timeout().orElse(Duration.ofMinutes(10)));
    }

    /** Shared player registry and virtual clock, independent of any one module's task lifetime. */
    static final class Registry implements AbstractUiServiceContractTest.Fixture {
        private final ManualScheduler.Engine clock = new ManualScheduler.Engine();
        private final RecordingScheduler scheduler = new RecordingScheduler(clock.scheduler("UiRuntime"));
        private final Map<String, InMemoryUiService> owners = new HashMap<>();
        private final List<UiBackend> backends = new ArrayList<>();
        private final List<MemorySession<?>> sessions = new ArrayList<>();
        private final Map<UUID, MemorySession<?>> active = new HashMap<>();
        private final List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        private final Queue<Runnable> retiredContinuations = new ConcurrentLinkedQueue<>();

        @Override public synchronized UiService service(String owner) {
            Objects.requireNonNull(owner, "owner");
            return owners.computeIfAbsent(owner, name -> new InMemoryUiService(this, name));
        }

        @Override public synchronized void register(UiBackend backend) {
            Objects.requireNonNull(backend, "backend");
            for (UiBackend existing : backends) {
                if (existing.priority() == backend.priority()) {
                    throw new IllegalStateException("Equal backend priorities");
                }
            }
            backends.add(backend);
        }

        void dispatch(Player viewer, Runnable action) {
            if (!viewer.isOnline()) {
                onGlobal(action);
                return;
            }
            TaskHandle scheduled = scheduler.entity(viewer).run(() -> {
                if (!viewer.isOnline()) {
                    onGlobal(action);
                    return;
                }
                action.run();
            });
            scheduled.completion().whenComplete((ignored, failure) -> {
                Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
                if (cause instanceof TaskRetiredException) {
                    retiredContinuations.add(action);
                }
            });
        }

        private void onGlobal(Runnable action) { scheduler.global().run(action); }

        private void flushRetired() {
            // Retirement completes handles while the manual engine is iterating its task list.
            Runnable action;
            while ((action = retiredContinuations.poll()) != null) {
                onGlobal(action);
            }
        }

        @Override public void advanceTicks(long ticks) {
            for (long i = 0; i < ticks; i++) {
                flushRetired();
                clock.advanceTicks(1);
            }
            flushRetired();
        }
        @Override public void disconnect(PlayerMock player) {
            player.disconnect();
            clock.retire(player);
            flushRetired();
            List<MemorySession<?>> quitting;
            synchronized (this) {
                quitting = sessions.stream().filter(session -> session.viewer.getUniqueId().equals(player.getUniqueId())).toList();
            }
            quitting.forEach(session -> session.requestClose(CloseReason.DISCONNECTED));
        }
        @Override public void unload(String owner) {
            List<MemorySession<?>> closing;
            synchronized (this) {
                InMemoryUiService service = (InMemoryUiService) service(owner);
                service.unloaded = true;
                closing = sessions.stream().filter(session -> session.service.owner().equals(owner)).toList();
            }
            for (MemorySession<?> session : closing) {
                session.choose(new UiResult.Closed<>(CloseReason.OWNER_UNLOADED));
                session.closeAndComplete();
            }
        }
        @Override public RecordingScheduler scheduler() { return scheduler; }
        @Override public List<LogRecord> warnings() { return List.copyOf(warnings); }
        @Override public void close() { clock.close(); }
    }

    private static class MemorySession<R> implements UiSession<R> {
        final InMemoryUiService service;
        final Registry registry;
        final Player viewer;
        private final UiIntent<R> intent;
        private final CompletableFuture<UiResult<R>> completion = new CompletableFuture<>();
        UiBackend backend;
        TaskHandle timeout;
        private UiResult<R> chosen;
        private boolean closedAndCompleted;

        MemorySession(InMemoryUiService service, Player viewer, UiIntent<R> intent) {
            this.service = service;
            registry = service.registry;
            this.viewer = viewer;
            this.intent = intent;
        }

        @Override public Player viewer() { return viewer; }
        @Override public UiIntent<R> intent() { return intent; }
        @Override public CompletionStage<UiResult<R>> result() { return completion.minimalCompletionStage(); }
        @Override public boolean isOpen() { synchronized (registry) { return chosen == null; } }
        @Override public void close() { requestClose(CloseReason.CLOSED_BY_CODE); }

        final void requestClose(CloseReason reason) { chooseAndQueue(new UiResult.Closed<>(reason)); }

        final void chooseAndQueue(UiResult<R> result) {
            if (choose(result)) {
                registry.dispatch(viewer, this::closeAndComplete);
            }
        }

        final boolean choose(UiResult<R> result) {
            synchronized (registry) {
                if (chosen != null) {
                    return false;
                }
                chosen = result;
                if (timeout != null) {
                    timeout.cancel();
                }
                return true;
            }
        }

        final void closeAndComplete() {
            UiResult<R> result;
            synchronized (registry) {
                if (closedAndCompleted || chosen == null) {
                    return;
                }
                closedAndCompleted = true;
                registry.active.remove(viewer.getUniqueId(), this);
                registry.sessions.remove(this);
                if (backend != null) {
                    backend.close(this);
                }
                result = chosen;
            }
            completion.complete(result);
        }

        final void openWith(UiBackend selected) {
            synchronized (registry) {
                if (!isOpen()) {
                    return;
                }
                registry.active.put(viewer.getUniqueId(), this);
                backend = selected;
                if (this instanceof MemoryGridSession grid) {
                    grid.rendered = grid.current;
                }
                selected.open(this, callbacks());
            }
        }

        private UiBackend.BackendCallbacks<R> callbacks() {
            return new UiBackend.BackendCallbacks<>() {
                @Override public void completed(R value) {
                    Objects.requireNonNull(value, "value");
                    if (!(intent instanceof Grid)) {
                        chooseAndQueue(new UiResult.Completed<>(value));
                    }
                }
                @Override public void closed(CloseReason reason) {
                    requestClose(Objects.requireNonNull(reason, "reason"));
                }
                @Override public void clicked(GridSlot slot, ClickKind kind) {
                    Objects.requireNonNull(slot, "slot");
                    Objects.requireNonNull(kind, "kind");
                    synchronized (registry) {
                        if (isOpen() && MemorySession.this instanceof MemoryGridSession grid) {
                            grid.rendered.item(slot).flatMap(GridItem::action)
                                    .ifPresent(action -> registry.dispatch(viewer, () -> grid.runAction(action, slot, kind)));
                        }
                    }
                }
            };
        }
    }

    private static final class MemoryGridSession extends MemorySession<Ack> implements GridSession {
        private Grid current;
        private Grid rendered;

        MemoryGridSession(InMemoryUiService service, Player viewer, Grid grid) {
            super(service, viewer, grid);
            current = grid;
            rendered = grid;
        }
        @Override public void update(Grid next) {
            Objects.requireNonNull(next, "next");
            synchronized (registry) {
                if (!isOpen()) {
                    return;
                }
                if (next.rows() != current.rows()) {
                    throw new IllegalArgumentException("A grid update must retain the row count");
                }
                current = next;
                registry.dispatch(viewer, this::notifyBackend);
            }
        }
        @Override public Grid current() { synchronized (registry) { return current; } }

        private void notifyBackend() {
            synchronized (registry) {
                if (!isOpen() || backend == null || current.equals(rendered)) {
                    return;
                }
                Grid previous = rendered;
                rendered = current;
                backend.update(this, previous, current);
            }
        }

        void runAction(GridAction action, GridSlot slot, ClickKind kind) {
            synchronized (registry) {
                if (!isOpen()) {
                    return;
                }
                try {
                    action.onClick(new Click(viewer, slot, kind, this));
                } catch (Exception thrown) {
                    LogRecord record = new LogRecord(Level.WARNING, "Grid action failed for owner {0}");
                    record.setParameters(new Object[] {service.owner()});
                    record.setThrown(thrown);
                    registry.warnings.add(record);
                }
            }
        }
    }

    private record Click(Player viewer, GridSlot slot, ClickKind kind, GridSession session) implements GridClick { }
}
