package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * State is the mutable state of a StatefulWidget.
 * <p>
 * State objects are managed by the framework and have the following properties:
 * <ul>
 *   <li>Created when the StatefulWidget is inserted into the tree</li>
 *   <li>Destroyed when the StatefulWidget is removed from the tree</li>
 *   <li>Reused across StatefulWidget rebuilds</li>
 * </ul>
 *
 * <p><strong>Lifecycle:</strong></p>
 * <ol>
 *   <li>{@link #initState()} - initializes state, called exactly once</li>
 *   <li>{@link #build(BuildContext)} - builds the UI, may be called multiple times</li>
 *   <li>{@link #didUpdateWidget(StatefulWidget)} - called when the configuration changes</li>
 *   <li>{@link #dispose()} - releases resources, called exactly once</li>
 * </ol>
 *
 * <p><strong>Usage example:</strong></p>
 * <pre>{@code
 * class CounterState extends State<CounterWidget> {
 *     private int count = 0;
 *
 *     @Override
 *     void initState() {
 *         super.initState();
 *         // initialization work
 *     }
 *
 *     @Override
 *     Widget build(BuildContext context) {
 *         return TextButton.builder()
 *             .text("Clicked: " + count)
 *             .onClick(() -> setState(() -> count++))
 *             .build();
 *     }
 * }
 * }</pre>
 *
 * @param <T> the corresponding StatefulWidget type
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public abstract class State<T extends StatefulWidget> {

    @Nullable
    private T _widget;
    @Nullable
    private StatefulElement _element;
    private boolean _dirty = false;
    private boolean _mounted = false;

    /**
     * Creates a new State.
     */
    public State() {
    }

    /**
     * Gets the Widget associated with this State.
     *
     * @return the Widget instance
     * @throws IllegalStateException if the State is not mounted yet
     */
    @NotNull
    public T getWidget() {
        if (_widget == null) {
            throw new IllegalStateException("State not mounted yet");
        }
        return _widget;
    }

    /**
     * Checks whether the State is mounted in the tree.
     *
     * @return true if mounted
     */
    public boolean isMounted() {
        return _mounted;
    }

    /**
     * Marks the State as dirty and schedules a rebuild.
     * <p>
     * This method notifies the framework that the State object has changed and the UI needs to
     * be rebuilt. The framework calls the build method on the next frame.
     * <p>
     * <b>Important:</b> the callback should only modify state and must not have side effects.
     *
     * <p><strong>Usage example:</strong></p>
     * <pre>{@code
     * void handleClick() {
     *     setState(() -> {
     *         _counter++;  // only modify state
     *     });
     * }
     * }</pre>
     *
     * @param fn the callback that modifies state
     */
    public void setState(@NotNull VoidCallback fn) {
        if (!_mounted) {
            throw new IllegalStateException("setState() called after dispose()");
        }

        // Run the state mutation
        fn.call();

        // Mark dirty and schedule a rebuild
        _dirty = true;
        if (_element != null) {
            _element.markNeedsBuild();
        }
    }

    /**
     * Initializes the state.
     * <p>
     * Called once, after the State object is created and before it is inserted into the tree.
     * Subscribe to streams, start animations, or run other one-time initialization here.
     * <p>
     * Must call super.initState().
     */
    protected void initState() {
        // overridden by subclasses
    }

    /**
     * Releases resources.
     * <p>
     * Called once, when the State object is permanently removed from the tree.
     * Unsubscribe, stop animations, or run other cleanup work here.
     * <p>
     * Must call super.dispose().
     */
    protected void dispose() {
        _mounted = false;
    }

    /**
     * Called when the Widget configuration changes.
     * <p>
     * Called when the parent Widget rebuilds and creates a new StatefulWidget instance. Compare
     * the old and new Widget properties here and adjust state accordingly.
     *
     * @param oldWidget the previous Widget instance
     */
    @SuppressWarnings("unchecked")
    protected void didUpdateWidget(@NotNull T oldWidget) {
        // overridden by subclasses
    }

    /**
     * Builds this State's UI.
     * <p>
     * This method is called:
     * <ul>
     *   <li>after initState</li>
     *   <li>after didUpdateWidget</li>
     *   <li>after setState</li>
     *   <li>when a depended-upon InheritedWidget changes</li>
     * </ul>
     *
     * @param context the build context
     * @return the Widget tree
     */
    @NotNull
    public abstract Widget build(@NotNull BuildContext context);

    // Package-private methods for framework

    void setElement(@Nullable StatefulElement element) {
        _element = element;
    }

    @SuppressWarnings("unchecked")
    void setWidget(@NotNull StatefulWidget widget) {
        _widget = (T) widget;
    }

    void setMounted(boolean mounted) {
        _mounted = mounted;
    }

    boolean isDirty() {
        return _dirty;
    }

    void clearDirty() {
        _dirty = false;
    }

    /**
     * Internal method: invokes didUpdateWidget using the raw type to avoid generics issues.
     */
    @SuppressWarnings("unchecked")
    void didUpdateWidgetInternal(@NotNull StatefulWidget oldWidget) {
        didUpdateWidget((T) oldWidget);
    }
}
