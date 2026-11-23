package com.ultikits.ultitools.ui.runtime;

import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.ui.CloseReason;
import com.ultikits.ultitools.ui.Gesture;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridSession;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.GridSlot;
import com.ultikits.ultitools.ui.UiBackend;
import com.ultikits.ultitools.ui.UiResult;
import com.ultikits.ultitools.ui.UiService;
import com.ultikits.ultitools.ui.ViewerItems;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Shared registration, lifecycle and ordered execution for all owners' UI services. */
@ApiStatus.Internal
public final class UiRuntime implements AutoCloseable {
    final Object lock = new Object();
    final Scheduler scheduler;
    final ContainerRuntime containers;
    private final Logger logger;
    private final Thread serverThread;
    private final GlobalPump pump;
    private final Map<String, RuntimeUiService> owners = new LinkedHashMap<>();
    private final List<BackendEntry> backends = new ArrayList<>();
    private final Set<RuntimeSession<?>> sessions = new LinkedHashSet<>();
    private final Map<UUID, RuntimeSession<?>> active = new HashMap<>();
    private final Map<UUID, ViewerLane> lanes = new HashMap<>();
    private boolean closed;

    /** Creates the runtime with framework-owned scheduling and the server thread. */
    public UiRuntime(Scheduler scheduler, ContainerRuntime containers, Logger logger, Thread serverThread) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.containers = Objects.requireNonNull(containers, "containers");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.serverThread = Objects.requireNonNull(serverThread, "serverThread");
        pump = new GlobalPump(scheduler, logger);
    }

    /** Gets the shared owner-scoped service, including its unloaded state. */
    public UiService service(String owner) {
        Objects.requireNonNull(owner, "owner");
        synchronized (lock) {
            return owners.computeIfAbsent(owner, name -> new RuntimeUiService(this, name, closed));
        }
    }

    /** Registers a backend, rejecting an equal priority independently of the supported intent types. */
    public void register(UiBackend backend) {
        Objects.requireNonNull(backend, "backend");
        int priority = backend.priority();
        synchronized (lock) {
            if (closed) { throw new IllegalStateException("UI runtime closed"); }
            if (backends.stream().anyMatch(entry -> entry.priority() == priority)) {
                throw new IllegalStateException("Equal backend priorities: " + priority);
            }
            backends.add(new BackendEntry(backend, priority));
            backends.sort(Comparator.comparingInt(BackendEntry::priority).reversed());
        }
    }

    ViewerLane lane(Player viewer) {
        UUID id = viewer.getUniqueId();
        boolean online = viewer.isOnline();
        synchronized (lock) {
            ViewerLane current = lanes.get(id);
            if (current == null) {
                current = new ViewerLane(scheduler, viewer, pump, logger);
                if (online && !closed) { lanes.put(id, current); }
            } else if (!current.belongsTo(viewer) && (!online || current.wasRetired())) {
                current = new ViewerLane(scheduler, viewer, pump, logger);
                if (online && !closed) { lanes.put(id, current); }
            }
            return current;
        }
    }

    boolean add(RuntimeSession<?> session) {
        synchronized (lock) {
            sessions.add(session);
            return session.service.unloaded || session.service.unloading;
        }
    }

    @Nullable UiBackend select(RuntimeSession<?> session) {
        List<BackendEntry> candidates;
        synchronized (lock) { candidates = List.copyOf(backends); }
        for (BackendEntry entry : candidates) {
            try {
                if (entry.backend().supports(session.intent(), session.viewer())) {
                    return entry.backend();
                }
            } catch (RuntimeException failure) {
                warn(session.service.owner(), "Backend supports failed", failure);
            }
        }
        return null;
    }

    void replacePrevious(RuntimeSession<?> session) {
        RuntimeSession<?> previous;
        synchronized (lock) { previous = active.get(session.viewerId); }
        if (previous != null) {
            previous.choose(new UiResult.Closed<>(CloseReason.REPLACED));
            previous.closeAndComplete();
        }
    }

    boolean activate(RuntimeSession<?> session, UiBackend backend) {
        synchronized (lock) {
            if (!session.isOpenLocked()) { return false; }
            active.put(session.viewerId, session);
            session.backend = backend;
            return true;
        }
    }

    void removeLocked(RuntimeSession<?> session) {
        active.remove(session.viewerId, session);
        sessions.remove(session);
    }

    /** Handles a viewer quitting; continuations move to the global pump. */
    public void quit(Player player) {
        Objects.requireNonNull(player, "player");
        UUID id = player.getUniqueId();
        ViewerLane retired = lane(player);
        retired.retire();
        List<RuntimeSession<?>> quitting;
        synchronized (lock) {
            active.remove(id);
            quitting = sessions.stream().filter(session -> session.viewerId.equals(id)).toList();
        }
        quitting.forEach(session -> session.requestClose(CloseReason.DISCONNECTED));
        pump.execute(() -> {
            synchronized (lock) { lanes.remove(id, retired); }
        });
    }

    /** Synchronously closes this owner's sessions before its services are torn down. */
    public void unload(String owner) {
        Objects.requireNonNull(owner, "owner");
        checkThread();
        RuntimeUiService service = (RuntimeUiService) service(owner);
        List<RuntimeSession<?>> closing;
        synchronized (lock) {
            if (service.unloaded || service.unloading) { return; }
            service.unloading = true;
            service.unloadingThread = Thread.currentThread();
            closing = sessions.stream().filter(session -> session.service.equals(service)).toList();
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        try {
            containers.prepareUnload(owner);
            service.flushDeliveries();
            containers.startUnloadCommits(owner);
            for (RuntimeSession<?> session : closing) {
                session.choose(new UiResult.Closed<>(CloseReason.OWNER_UNLOADED));
                session.closeAndComplete();
            }
            containers.finishUnload(owner, deadline);
        } finally {
            synchronized (lock) {
                service.unloaded = true;
                service.unloading = false;
                service.unloadingThread = null;
            }
        }
    }

    /** Applies a gesture against the backend's rendered snapshot, refusing a terminal session. */
    public boolean applyGesture(GridSession session, ViewerItems side, Gesture gesture) {
        RuntimeGridSession grid = ownGrid(session);
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(gesture, "gesture");
        Grid rendered;
        synchronized (lock) {
            if (!grid.isOpenLocked()) { return false; }
            rendered = grid.renderedLocked();
        }
        return containers.engine().apply(rendered, grid.viewer(), side, gesture);
    }

    /** Returns defensive copies of the last drawn bound slots. */
    public Map<GridSlot, ItemStack> picture(GridSession session) { return ownGrid(session).pictureCopy(); }

    private RuntimeGridSession ownGrid(GridSession session) {
        Objects.requireNonNull(session, "session");
        if (!(session instanceof RuntimeGridSession grid) || !grid.runtime.equals(this)) {
            throw new IllegalArgumentException("Session belongs to another UI runtime");
        }
        return grid;
    }

    void redraw(SlotContainer container) {
        List<RuntimeGridSession> watching;
        synchronized (lock) {
            watching = sessions.stream().filter(RuntimeGridSession.class::isInstance)
                    .map(RuntimeGridSession.class::cast).filter(grid -> grid.isOpenLocked() && grid.backend != null
                            && RuntimeGridSession.boundContainers(grid.renderedLocked()).contains(container)).toList();
        }
        watching.forEach(grid -> grid.dispatch(grid::redraw));
    }

    boolean isServerThread() { return Thread.currentThread().equals(serverThread); }
    void checkThread() {
        if (!isServerThread()) { throw new IllegalStateException("UI operation requires the server thread"); }
    }
    void warn(String owner, String message, Throwable failure) {
        LogRecord record = new LogRecord(Level.WARNING, message + " for owner " + owner);
        record.setThrown(failure);
        log(record);
    }
    void log(LogRecord record) { logger.log(record); }

    @Override public void close() {
        checkThread();
        List<String> names;
        synchronized (lock) {
            if (closed) { return; }
            closed = true;
            names = List.copyOf(owners.keySet());
        }
        try { names.forEach(this::unload); } finally {
            pump.close();
            synchronized (lock) { lanes.clear(); }
        }
    }

    private static final class BackendEntry {
        private final UiBackend backend;
        private final int priority;
        BackendEntry(UiBackend backend, int priority) { this.backend = backend; this.priority = priority; }
        UiBackend backend() { return backend; }
        int priority() { return priority; }
    }
}
