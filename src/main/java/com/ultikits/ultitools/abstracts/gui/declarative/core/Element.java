package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Element is the instantiated representation of a Widget, responsible for managing the Widget's
 * lifecycle and updates.
 * <p>
 * An Element is a persistent object: when a Widget rebuilds, its Element is reused if the type
 * still matches. That reuse is the key to the framework's efficient updates.
 * <p>
 * <b>Element's responsibilities:</b>
 * <ul>
 * <li>Holds a reference to the Widget (updated across rebuilds)</li>
 * <li>Manages the child Element tree</li>
 * <li>Coordinates Widget creation, update, and disposal</li>
 * <li>Supplies a RenderNode for RenderObjectElement</li>
 * </ul>
 *
 * <p><strong>Element types:</strong></p>
 * <ul>
 * <li>{@link ComponentElement} - composite, manages a child
 * Widget (StatelessWidget/StatefulWidget)</li>
 * <li>{@link RenderObjectElement} - render, corresponds to an actual RenderNode</li>
 * </ul>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 * @see Widget#createElement()
 */
public abstract class Element {

    @Nullable
    private Widget _widget;
    @Nullable
    private Element _parent;
    @Nullable
    private List<Element> _children;
    private boolean _dirty = false;
    private boolean _mounted = false;
    @Nullable
    private BuildContext _context;
    @Nullable
    private Runnable _rootBuildScheduler;

    protected Element(@NotNull Widget widget) {
        this._widget = widget;
    }

    /**
     * Assigns a Context to this Element (only used for the root Element or in tests).
     *
     * @param context the BuildContext
     */
    public void assignContext(@NotNull BuildContext context) {
        this._context = context;
    }

    /**
     * Mounts this Element into the tree.
     * <p>
     * Called the first time the Element is inserted into the tree. Subclasses should perform
     * initialization work here and mount their child Elements.
     *
     * @param parent the parent Element, or null if this is the root Element
     */
    public void mount(@Nullable Element parent) {
        _parent = parent;
        _mounted = true;
        _dirty = false;

        if (parent != null) {
            _context = parent._context.child(this);
        } else if (_context == null) {
            throw new IllegalStateException("Root element must have context assigned before mount");
        }
    }

    /**
     * Updates this Element with a new Widget.
     * <p>
     * Called when the Widget rebuilds. Subclasses should update their reference to the Widget
     * and perform any necessary updates.
     *
     * @param newWidget the new Widget instance
     */
    public void update(@NotNull Widget newWidget) {
        if (!canUpdate(newWidget)) {
            throw new IllegalArgumentException(
                    "Cannot update " + this + " with " + newWidget);
        }
        _widget = newWidget;
        // D-09 item 2: mark dirty here, in the BASE class, so StatefulElement and
        // StatelessElement (which both call super.update() before their own logic)
        // pick this up "for free" and move in step. Before this, only markNeedsBuild()
        // ever set _dirty, so a Widget swap via update() never scheduled a rebuild.
        _dirty = true;
    }

    /**
     * Checks whether this Element can update to the given Widget.
     *
     * @param newWidget the new Widget
     * @return true if it can be updated
     */
    public boolean canUpdate(@NotNull Widget newWidget) {
        return _widget != null && newWidget.canUpdate(this);
    }

    /**
     * Removes this Element from the tree.
     * <p>
     * Called when the Element is no longer needed. Subclasses should perform cleanup work here
     * and unmount their child Elements.
     */
    public void unmount() {
        _mounted = false;
        _parent = null;

        if (_children != null) {
            for (Element child : _children) {
                child.unmount();
            }
            _children.clear();
            _children = null;
        }
    }

    /**
     * Marks this Element as needing a rebuild.
     * <p>
     * This schedules the framework to rebuild this Element on the next frame.
     */
    public void markNeedsBuild() {
        if (!_mounted) {
            return;
        }
        if (_dirty) {
            return;
        }
        _dirty = true;

        // Notify the parent Element or the renderer.
        //
        // This is the sole exit point of the State.setState() -> Element.markNeedsBuild() chain:
        // if this Element is the mounted root (_parent == null), bubbling stops right here --
        // before GuiRenderer registers _rootBuildScheduler, setState() would mutate the State's
        // fields and mark this Element dirty, but no frame was ever scheduled to actually run
        // performBuild(), so the Inventory would never reflect the new state.
        if (_parent != null) {
            _parent.markChildNeedsBuild(this);
        } else if (_rootBuildScheduler != null) {
            _rootBuildScheduler.run();
        }
    }

    /**
     * Marks a child Element as needing a rebuild.
     *
     * @param child the child Element
     */
    public void markChildNeedsBuild(@NotNull Element child) {
        // Default implementation: forward to the parent Element; once bubbling reaches the
        // mounted root (_parent == null), hand off to the scheduling callback GuiRenderer
        // registered -- see the note on markNeedsBuild() above; the same "bubbling stops at the
        // root" gap applies here.
        if (_parent != null) {
            _parent.markChildNeedsBuild(child);
        } else if (_rootBuildScheduler != null) {
            _rootBuildScheduler.run();
        }
    }

    /**
     * Registers, on the mounted root Element, the callback that a "needs rebuild" bubble hands
     * off to on {@link com.ultikits.ultitools.abstracts.gui.declarative.engine.GuiRenderer}.
     * <p>
     * Framework-internal method -- only GuiRenderer calls this, when it mounts or remounts the
     * root Element; module authors do not need to (and should not) call it themselves. On a
     * non-root Element this field is always null, because bubbling forwards to the first
     * ancestor with {@code _parent != null} and never reaches this branch.
     *
     * @param scheduler the callback invoked when the root needs a rebuild, typically
     *                  {@code GuiRenderer::scheduleBuild}; pass {@code null} to unregister (when
     *                  the renderer is destroyed)
     */
    public void setRootBuildScheduler(@Nullable Runnable scheduler) {
        this._rootBuildScheduler = scheduler;
    }

    /**
     * Performs a rebuild.
     * <p>
     * Subclasses should implement this method to build or rebuild the Widget tree.
     */
    public abstract void performRebuild();

    /**
     * Gets the Widget associated with this Element.
     *
     * @return the Widget instance
     * @throws IllegalStateException if the Widget is null
     */
    @NotNull
    public Widget getWidget() {
        if (_widget == null) {
            throw new IllegalStateException("Widget is null");
        }
        return _widget;
    }

    /**
     * Gets the parent Element.
     *
     * @return the parent Element, or null at the root
     */
    @Nullable
    public Element getParent() {
        return _parent;
    }

    /**
     * Gets the list of child Elements.
     *
     * @return the child Element list, or an empty list if there are none
     */
    @NotNull
    public List<Element> getChildren() {
        return _children != null ? _children : Collections.emptyList();
    }

    /**
     * Gets the build context.
     *
     * @return the BuildContext
     */
    @NotNull
    public BuildContext getContext() {
        if (_context == null) {
            throw new IllegalStateException("Element context not initialized");
        }
        return _context;
    }

    /**
     * Checks whether this Element is mounted.
     *
     * @return true if mounted
     */
    public boolean isMounted() {
        return _mounted;
    }

    /**
     * Checks whether this Element needs a rebuild.
     *
     * @return true if it needs a rebuild
     */
    public boolean isDirty() {
        return _dirty;
    }

    /**
     * Clears the dirty flag.
     */
    public void clearDirty() {
        _dirty = false;
    }

    /**
     * Adds a child Element.
     *
     * @param child the child Element
     */
    protected void addChild(@NotNull Element child) {
        if (_children == null) {
            _children = new ArrayList<>();
        }
        _children.add(child);
    }

    /**
     * Updates a single child Element.
     * <p>
     * This is the core method of reconciliation. It decides whether to reuse the existing
     * Element or create a new one.
     *
     * @param newWidget the new Widget
     * @param oldChild  the previous child Element, may be null
     * @return the updated Element
     */
    @Nullable
    protected Element updateChild(@Nullable Widget newWidget, @Nullable Element oldChild) {
        // If the new Widget is null, remove the old Element.
        if (newWidget == null) {
            if (oldChild != null) {
                oldChild.unmount();
            }
            return null;
        }

        // If there is no old Element, create a new one.
        if (oldChild == null) {
            Element newChild = newWidget.createElement();
            newChild.mount(this);
            return newChild;
        }

        // If the Widget type matches, reuse the Element.
        if (oldChild.canUpdate(newWidget)) {
            oldChild.update(newWidget);
            return oldChild;
        }

        // Type differs, so it must be replaced.
        oldChild.unmount();
        Element newChild = newWidget.createElement();
        newChild.mount(this);
        return newChild;
    }

    /**
     * Reconciles a list of old child Elements against a list of new Widgets, pairing by
     * {@link SlotKey} where a Widget declares one and falling back to positional (index)
     * pairing where it does not -- so a caller that never supplies a key sees exactly the
     * same index-based behaviour this method replaces.
     * <p>
     * This is the shared implementation D-09 item 5 asks for:
     * {@link com.ultikits.ultitools.abstracts.gui.declarative.widgets.ContainerElement} and
     * {@link com.ultikits.ultitools.abstracts.gui.declarative.widgets.GridViewElement} were
     * literal twins, each hand-rolling the same
     * {@code Math.min(size)} index pairing and never reading {@link Widget#getKey()}. Both
     * now call this method instead of maintaining their own copy, so the two classes cannot
     * drift apart the way they did before this plan.
     * <p>
     * <b>Algorithm:</b> the old child Elements are first split into a keyed group and an
     * unkeyed group. Iterating the new Widget list, a keyed Widget looks up its match in the
     * keyed group, and an unkeyed Widget takes the next available Element from the unkeyed
     * group, in order. A match is reused via {@link #updateChild(Widget, Element)} (or replaced
     * there if its type no longer matches); no match means a new Element is created. After the
     * pass, whatever remains in either group -- unclaimed by any new Widget -- is unmounted.
     *
     * @param oldChildren the previously-mounted child Elements, in their current order
     * @param newWidgets  the new child Widgets, in their desired order
     * @return the reconciled child Elements, in {@code newWidgets}' order
     */
    @NotNull
    protected List<Element> updateChildren(@NotNull List<Element> oldChildren, @NotNull List<Widget> newWidgets) {
        List<Element> newChildren = new ArrayList<>(newWidgets.size());

        Map<SlotKey, Element> keyedOld = new HashMap<>();
        Deque<Element> unkeyedOld = new ArrayDeque<>();
        for (Element old : oldChildren) {
            SlotKey key = old.getWidget().getKey();
            if (key != null) {
                keyedOld.put(key, old);
            } else {
                unkeyedOld.addLast(old);
            }
        }

        for (Widget newWidget : newWidgets) {
            SlotKey key = newWidget.getKey();
            Element oldChild = key != null ? keyedOld.remove(key)
                    : (unkeyedOld.isEmpty() ? null : unkeyedOld.pollFirst());
            Element updated = updateChild(newWidget, oldChild);
            if (updated != null) {
                newChildren.add(updated);
            }
        }

        // Anything left in either group was not claimed by any new Widget -- unmount it.
        for (Element leftover : keyedOld.values()) {
            leftover.unmount();
        }
        for (Element leftover : unkeyedOld) {
            leftover.unmount();
        }

        return newChildren;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + _widget + ")";
    }

}