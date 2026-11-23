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
import java.util.Locale;
import java.util.Set;
import java.util.LinkedHashSet;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import com.ultikits.ultitools.text.FixedLocaleResolver;
import com.ultikits.ultitools.text.InMemoryMessages;
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
    private boolean unloading;
    private final List<PendingDelivery> pendingDeliveries = new ArrayList<>();

    InMemoryUiService(Registry registry, String owner) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    @Override public String owner() { return owner; }

    @Override public SlotContainer container(ContainerSpec spec) {
        Objects.requireNonNull(spec, "spec");
        registry.checkThread();
        if (unloaded) {
            throw new IllegalStateException("Owner unloaded: " + owner);
        }
        InMemorySlotContainer container = new InMemorySlotContainer(owner, spec, registry.scheduler,
                this::returnToContributor, registry.warnings::add, registry::redraw);
        registry.containers.add(container);
        return container;
    }

    private CompletionStage<Delivery> returnToContributor(UUID contributor, List<ItemStack> items) {
        Player player = Bukkit.getPlayer(contributor);
        if (player == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(new Delivery(List.of(), List.of(), items)).minimalCompletionStage();
        }
        return deliver(player, items);
    }

    @Override public CompletionStage<Delivery> deliver(Player player, List<ItemStack> items) {
        Objects.requireNonNull(player, "player");
        List<ItemStack> copy = UiTestItems.copies(items);
        PendingDelivery delivery = new PendingDelivery(player, copy);
        synchronized (registry) {
            if (unloading && Thread.currentThread() == registry.thread) { // NOPMD - only the unloading thread may give synchronously
                delivery.run();
            } else if (unloaded) {
                delivery.result.complete(new Delivery(List.of(), List.of(), copy));
            } else {
                pendingDeliveries.add(delivery);
                registry.dispatch(player, delivery);
            }
        }
        return delivery.result.minimalCompletionStage();
    }

    private final class PendingDelivery implements Runnable {
        private final Player player;
        private final List<ItemStack> items;
        private final CompletableFuture<Delivery> result = new CompletableFuture<>();
        PendingDelivery(Player player, List<ItemStack> items) { this.player = player; this.items = items; }
        @Override public void run() {
            synchronized (registry) {
                if (!result.isDone()) {
                    result.complete(give(player, items));
                    pendingDeliveries.remove(this);
                }
            }
        }
    }

    private void flushDeliveries() {
        registry.checkThread();
        // Unload owns every Paper entity context; no next tick is required for owed items.
        List.copyOf(pendingDeliveries).forEach(PendingDelivery::run);
    }

    private Delivery give(Player player, List<ItemStack> items) {
        if (!player.isOnline()) {
            return new Delivery(List.of(), List.of(), items);
        }
        ItemStack[] storage = player.getInventory().getStorageContents();
        InMemoryViewerItems side = new InMemoryViewerItems(storage.length);
        for (int i = 0; i < storage.length; i++) {
            side.slot(i, storage[i] == null ? ItemStack.empty() : storage[i]);
        }
        List<ItemStack> given = new ArrayList<>();
        List<ItemStack> dropped = new ArrayList<>();
        for (ItemStack item : items) {
            if (!item.isEmpty()) {
                int fits = UiTestItems.fit(side, item);
                UiTestItems.insert(side, item, fits);
                int rest = item.getAmount() - fits;
                if (rest < item.getAmount()) {
                    given.add(UiTestItems.amount(item, item.getAmount() - rest));
                }
                if (rest > 0) {
                    ItemStack drop = UiTestItems.amount(item, rest);
                    player.getWorld().dropItemNaturally(player.getLocation(), UiTestItems.copy(drop));
                    dropped.add(drop);
                }
            }
        }
        for (int i = 0; i < storage.length; i++) {
            player.getInventory().setItem(i, side.slot(i));
        }
        return new Delivery(given, dropped, List.of());
    }

    private static Set<InMemorySlotContainer> boundContainers(Grid grid) {
        Set<InMemorySlotContainer> result = new LinkedHashSet<>();
        grid.bindings().values().forEach(binding -> result.add((InMemorySlotContainer) binding.container()));
        return result;
    }

    private static void validateContainers(Grid grid) {
        boundContainers(grid).forEach(container -> {
            if (container.state() == ContainerState.ENDED) {
                throw new IllegalStateException("Cannot show an ended container");
            }
        });
    }
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
        if (intent instanceof Grid grid && Thread.currentThread() == registry.thread) { // NOPMD - validate synchronously on the container thread
            validateContainers(grid);
        }
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
        takeBindings(session);
        replacePrevious(session);
        session.openWith(selected);
        scheduleTimeout(session, options);
    }

    private void takeBindings(MemorySession<?> session) {
        synchronized (registry) {
            if (session instanceof MemoryGridSession grid) {
                grid.rendered = grid.current;
                boundContainers(grid.rendered).forEach(container -> container.attach(session));
            }
        }
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
        private final Thread thread = Thread.currentThread();
        private final List<InMemorySlotContainer> containers = new ArrayList<>();
        private final InMemoryMessages messages = new InMemoryMessages("test",
                new FixedLocaleResolver(Locale.ENGLISH, Optional.empty()));
        private final GestureEngine engine = new InMemoryGestureEngine((player, text) -> player.sendMessage(messages.render(text, player)));

        void checkThread() {
            if (Thread.currentThread() != thread) { // NOPMD - the registry captures the server thread
                throw new IllegalStateException("Container creation requires the server thread");
            }
        }
        void redraw(InMemorySlotContainer changed) {
            List<MemorySession<?>> snapshot = List.copyOf(sessions);
            for (MemorySession<?> session : snapshot) {
                if (session instanceof MemoryGridSession grid && grid.isOpen() && grid.backend != null
                        && boundContainers(grid.rendered).contains(changed)) {
                    dispatch(grid.viewer, () -> {
                        if (grid.isOpen()) {
                            grid.drawPicture();
                        }
                    });
                }
            }
        }
        @Override public ItemStack shown(GridSession session, GridSlot slot) {
            MemoryGridSession grid = (MemoryGridSession) session;
            return UiTestItems.copy(grid.picture.getOrDefault(slot, ItemStack.empty()));
        }
        @Override public GestureEngine engine() { return engine; }
        @Override public boolean gesture(GridSession session, ViewerItems side, Gesture gesture) {
            if (!session.isOpen()) {
                return false;
            }
            MemoryGridSession grid = (MemoryGridSession) session;
            return engine.apply(grid.rendered, grid.viewer, side, gesture);
        }

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
                service.unloading = true;
                closing = sessions.stream().filter(session -> session.service.owner().equals(owner)).toList();
            }
            for (MemorySession<?> session : List.copyOf(closing)) {
                session.choose(new UiResult.Closed<>(CloseReason.OWNER_UNLOADED));
                session.closeAndComplete();
            }
            List.copyOf(containers).stream().filter(container -> container.owner().equals(owner)).forEach(InMemorySlotContainer::unload);
            owners.get(owner).flushDeliveries();
            synchronized (this) {
                owners.get(owner).unloaded = true;
                owners.get(owner).unloading = false;
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
                if (this instanceof MemoryGridSession grid && backend != null) {
                    boundContainers(grid.rendered).forEach(container -> {
                        container.detach(this);
                        if (service.unloading && container.owner().equals(service.owner())) {
                            container.unload();
                        }
                    });
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
                    grid.drawPicture();
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
        private final Map<GridSlot, ItemStack> picture = new HashMap<>();

        void drawPicture() {
            picture.clear();
            rendered.bindings().forEach((slot, binding) -> picture.put(slot,
                    binding.container().state() == ContainerState.ENDED ? ItemStack.empty() : binding.container().get(binding.index())));
        }

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
                if (Thread.currentThread() == registry.thread) { // NOPMD - mutable container state is read only on its thread
                    validateContainers(next);
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
                Set<InMemorySlotContainer> before = boundContainers(previous);
                Set<InMemorySlotContainer> after = boundContainers(current);
                after.forEach(container -> container.attach(this));
                rendered = current;
                drawPicture();
                backend.update(this, previous, current);
                before.stream().filter(container -> !after.contains(container)).forEach(container -> container.detach(this));
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
