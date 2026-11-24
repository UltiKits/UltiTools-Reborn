package com.ultikits.ultitools.ui.container;

import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.ContainerSpec;
import com.ultikits.ultitools.ui.GestureEngine;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.UiSession;
import com.ultikits.ultitools.ui.runtime.ContainerHost;
import com.ultikits.ultitools.ui.runtime.ContainerRuntime;
import com.ultikits.ultitools.ui.runtime.UiOwner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Module-copy ownership and the three synchronous unload phases for editable containers. */
@ApiStatus.Internal
public final class FrameworkContainerRuntime implements ContainerRuntime {
    private final BooleanSupplier onContainerThread;
    private final Map<UiOwner, OwnerContainers> owners = new LinkedHashMap<>();
    private final GestureEngine engine;
    private @Nullable Duration unloadWaitOverride;

    /**
     * Creates the editable runtime. The scheduler is supplied per owner by its host, never by a module.
     * @param onContainerThread whether the caller may access container state
     * @param refusalNotifier sends a refusal text in the viewer's locale
     */
    public FrameworkContainerRuntime(BooleanSupplier onContainerThread, BiConsumer<Player, Text> refusalNotifier) {
        this.onContainerThread = Objects.requireNonNull(onContainerThread, "onContainerThread");
        engine = new ConservingGestureEngine(onContainerThread, this::ownsContainer,
                Objects.requireNonNull(refusalNotifier, "refusalNotifier"));
    }
    private void checkThread() {
        if (!onContainerThread.getAsBoolean()) { throw new IllegalStateException("Container runtime called off its thread"); }
    }
    @Override public SlotContainer create(UiOwner owner, ContainerSpec spec, ContainerHost host) {
        checkThread();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(host, "host");
        OwnerContainers group = owners.computeIfAbsent(owner, ignored -> new OwnerContainers());
        if (group.unloading) { throw new IllegalStateException("Owner unloading: " + owner.name()); }
        FrameworkSlotContainer container = new FrameworkSlotContainer(this, owner, spec, host, onContainerThread);
        group.containers.add(container);
        return container;
    }
    private FrameworkSlotContainer own(SlotContainer container) {
        checkThread();
        Objects.requireNonNull(container, "container");
        if (!ownsContainer(container)) { throw new IllegalArgumentException("Container belongs to another runtime"); }
        return (FrameworkSlotContainer) container;
    }
    private boolean ownsContainer(SlotContainer container) {
        return container instanceof FrameworkSlotContainer framework && framework.belongsTo(this);
    }
    @Override public void attach(SlotContainer container, UiSession<?> session) {
        own(container).attach(Objects.requireNonNull(session, "session"));
    }
    @Override public void detach(SlotContainer container, UiSession<?> session) {
        own(container).detach(Objects.requireNonNull(session, "session"));
    }
    @Override public GestureEngine engine() { return engine; }

    /** Shortens only the test budget, without replacing the service's absolute deadline. */
    void unloadWaitOverride(Duration wait) {
        checkThread();
        Objects.requireNonNull(wait, "wait");
        if (wait.isNegative() || wait.isZero()) { throw new IllegalArgumentException("wait must be positive"); }
        unloadWaitOverride = wait;
    }
    @Override public void prepareUnload(UiOwner owner, long deadlineNanos) {
        checkThread();
        OwnerContainers group = owners.get(owner);
        if (group == null || group.unloading) { return; }
        group.unloading = true;
        group.deadline = unloadWaitOverride == null ? deadlineNanos
                : Math.min(deadlineNanos, System.nanoTime() + unloadWaitOverride.toNanos());
        forEachContainer(group, "Container unload prepare failed", container -> container.prepareUnload(group.deadline));
    }
    @Override public void startUnloadCommits(UiOwner owner) {
        checkThread();
        OwnerContainers group = owners.get(owner);
        if (group != null) { forEachContainer(group, "Container unload start failed", FrameworkSlotContainer::startUnloadCommit); }
    }
    @Override public void finishUnload(UiOwner owner, long deadlineNanos) {
        checkThread();
        OwnerContainers group = owners.get(owner);
        if (group == null) { return; }
        if (!group.unloading) { prepareUnload(owner, deadlineNanos); }
        try { forEachContainer(group, "Container unload finish failed", FrameworkSlotContainer::unload); }
        finally {
            if (group.containers.isEmpty()) { owners.remove(owner); }
        }
    }
    void ended(UiOwner owner, FrameworkSlotContainer container) {
        checkThread();
        OwnerContainers group = owners.get(owner);
        if (group != null) { group.containers.remove(container); }
    }
    private static void forEachContainer(OwnerContainers group, String step, Consumer<FrameworkSlotContainer> action) {
        VirtualMachineError fatal = null;
        for (FrameworkSlotContainer container : new ArrayList<>(group.containers)) {
            try { action.accept(container); }
            catch (VirtualMachineError failure) {
                container.warn(step, failure);
                if (fatal == null) { fatal = failure; }
            } catch (Throwable failure) {
                container.warn(step, failure);
            }
        }
        if (fatal != null) { throw fatal; }
    }
    private static final class OwnerContainers {
        final List<FrameworkSlotContainer> containers = new ArrayList<>();
        boolean unloading;
        long deadline;
    }
}
