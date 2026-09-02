package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * StatefulDeclarativeGui is the base class for a declarative GUI that carries state.
 * <p>
 * It manages a State object internally, providing {@link #setState(Runnable)} to trigger a
 * rebuild.
 *
 * @param <T> the State type
 */
public abstract class StatefulDeclarativeGui<T extends StatefulDeclarativeGui<T>.State> extends DeclarativeGui {

    @NotNull
    private T state;

    public StatefulDeclarativeGui(@NotNull Player player, @NotNull String id, @NotNull String title, int rows) {
        super(player, id, title, rows);
        this.state = createState();
        state.gui = this;
    }

    /**
     * Creates the state object.
     *
     * @return the state object
     */
    @NotNull
    protected abstract T createState();

    /**
     * Gets the state object.
     *
     * @return the state object
     */
    @NotNull
    protected T getState() {
        return state;
    }

    /**
     * Sets state and triggers a rebuild.
     *
     * @param action the state-modifying operation
     */
    protected void setState(@NotNull Runnable action) {
        action.run();
        markNeedsBuild();
    }

    /**
     * The base class for a declarative GUI's state.
     */
    public abstract class State {
        protected StatefulDeclarativeGui<T> gui;

        /**
         * Triggers a rebuild.
         */
        protected void setState(@NotNull Runnable action) {
            if (gui != null) {
                gui.setState(action);
            }
        }

        /**
         * Gets the associated GUI.
         *
         * @return the GUI instance
         */
        @NotNull
        protected StatefulDeclarativeGui<T> getGui() {
            if (gui == null) {
                throw new IllegalStateException("State not attached to GUI");
            }
            return gui;
        }

        /**
         * Gets the player.
         *
         * @return the player
         */
        @NotNull
        protected Player getPlayer() {
            if (gui == null) {
                throw new IllegalStateException("State not attached to GUI");
            }
            return gui.getPlayer();
        }
    }
}
