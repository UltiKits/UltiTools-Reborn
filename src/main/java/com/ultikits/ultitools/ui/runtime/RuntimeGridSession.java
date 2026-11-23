package com.ultikits.ultitools.ui.runtime;

import com.ultikits.ultitools.ui.Ack;
import com.ultikits.ultitools.ui.ClickKind;
import com.ultikits.ultitools.ui.CloseReason;
import com.ultikits.ultitools.ui.ContainerState;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridAction;
import com.ultikits.ultitools.ui.GridClick;
import com.ultikits.ultitools.ui.GridItem;
import com.ultikits.ultitools.ui.GridSession;
import com.ultikits.ultitools.ui.GridSlot;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.UiBackend;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/** Separates the module's latest snapshot from the backend's rendered snapshot. */
@ApiStatus.Internal
final class RuntimeGridSession extends RuntimeSession<Ack> implements GridSession {
    private Grid current;
    private Grid rendered;
    private Map<GridSlot, ItemStack> picture = Map.of();
    private boolean updateQueued;
    private boolean attached;

    RuntimeGridSession(RuntimeUiService service, Player viewer, Grid grid) {
        super(service, viewer, grid);
        current = grid;
        rendered = grid;
    }

    @Override public Grid current() { synchronized (runtime.lock) { return current; } }
    Grid renderedLocked() { return rendered; }

    @Override public void update(Grid next) {
        Objects.requireNonNull(next, "next");
        synchronized (runtime.lock) { if (!isOpenLocked()) { return; } }
        if (next.rows() != current().rows()) {
            throw new IllegalArgumentException("A grid update must retain the row count");
        }
        if (runtime.isServerThread()) { validateContainers(next); }
        boolean queue;
        synchronized (runtime.lock) {
            if (!isOpenLocked()) { return; }
            current = next;
            queue = !updateQueued;
            updateQueued = true;
        }
        if (queue) { dispatch(this::notifyBackend); }
    }

    static void validateContainers(Grid grid) {
        for (SlotContainer container : boundContainers(grid)) {
            if (container.state() == ContainerState.ENDED) {
                throw new IllegalStateException("Cannot show an ended container");
            }
        }
    }

    static Set<SlotContainer> boundContainers(Grid grid) {
        Set<SlotContainer> containers = Collections.newSetFromMap(new IdentityHashMap<>());
        grid.bindings().values().forEach(binding -> containers.add(binding.container()));
        return containers;
    }

    @Override void takeBindings() {
        Grid snapshot;
        synchronized (runtime.lock) {
            rendered = current;
            snapshot = rendered;
            attached = true;
        }
        boundContainers(snapshot).forEach(container -> runtime.containers.attach(container, this));
        drawPicture();
    }

    @Override void detachBindings() {
        Grid snapshot;
        synchronized (runtime.lock) {
            if (!attached) { return; }
            attached = false;
            snapshot = rendered;
        }
        boundContainers(snapshot).forEach(this::detach);
    }

    private void detach(SlotContainer container) {
        try { runtime.containers.detach(container, this); } catch (RuntimeException failure) {
            runtime.warn(service.owner(), "Container detach failed", failure);
        }
    }

    private void notifyBackend() {
        Grid previous;
        Grid next;
        UiBackend selected;
        synchronized (runtime.lock) {
            updateQueued = false;
            if (!isOpenLocked() || backend == null || current == rendered) { // NOPMD - snapshot identity defines an update chain
                return;
            }
            previous = rendered;
            next = current;
            selected = backend;
        }
        Set<SlotContainer> after = boundContainers(next);
        after.forEach(container -> runtime.containers.attach(container, this));
        synchronized (runtime.lock) { rendered = next; }
        drawPicture();
        try { selected.update(this, previous, next); } catch (RuntimeException failure) {
            runtime.warn(service.owner(), "Backend update failed", failure);
            requestClose(CloseReason.UNSUPPORTED);
        } finally {
            boundContainers(previous).stream().filter(container -> !after.contains(container)).forEach(this::detach);
        }
        renderPicture();
    }

    void drawPicture() {
        Grid snapshot;
        synchronized (runtime.lock) { snapshot = rendered; }
        Map<GridSlot, ItemStack> drawn = new LinkedHashMap<>();
        snapshot.bindings().forEach((slot, binding) -> drawn.put(slot,
                binding.container().state() == ContainerState.ENDED ? ItemStack.empty()
                        : binding.container().get(binding.index()).clone()));
        synchronized (runtime.lock) { picture = drawn; }
    }

    Map<GridSlot, ItemStack> pictureCopy() {
        Map<GridSlot, ItemStack> result = new LinkedHashMap<>();
        synchronized (runtime.lock) { picture.forEach((slot, item) -> result.put(slot, item.clone())); }
        return Collections.unmodifiableMap(result);
    }

    void redraw() {
        if (isOpen()) {
            drawPicture();
            renderPicture();
        }
    }

    void renderPicture() {
        UiBackend selected;
        synchronized (runtime.lock) {
            if (!isOpenLocked()) { return; }
            selected = backend;
        }
        if (selected instanceof BoundSlotRenderer renderer) {
            try { renderer.redraw(this, pictureCopy()); } catch (RuntimeException failure) {
                runtime.warn(service.owner(), "Bound slot redraw failed", failure);
            }
        }
    }

    @Override void clicked(GridSlot slot, ClickKind kind) {
        GridAction action;
        synchronized (runtime.lock) {
            if (!isOpenLocked()) { return; }
            action = rendered.item(slot).flatMap(GridItem::action).orElse(null);
        }
        if (action != null) { dispatch(() -> runAction(action, slot, kind)); }
    }

    private void runAction(GridAction action, GridSlot slot, ClickKind kind) {
        if (!isOpen()) { return; }
        try { action.onClick(new Click(viewer, slot, kind, this)); } catch (Exception failure) {
            runtime.warn(service.owner(), "Grid action failed", failure);
        }
    }

    private static final class Click implements GridClick {
        private final Player viewer;
        private final GridSlot slot;
        private final ClickKind kind;
        private final GridSession session;
        Click(Player viewer, GridSlot slot, ClickKind kind, GridSession session) {
            this.viewer = viewer;
            this.slot = slot;
            this.kind = kind;
            this.session = session;
        }
        @Override public Player viewer() { return viewer; }
        @Override public GridSlot slot() { return slot; }
        @Override public ClickKind kind() { return kind; }
        @Override public GridSession session() { return session; }
    }
}
