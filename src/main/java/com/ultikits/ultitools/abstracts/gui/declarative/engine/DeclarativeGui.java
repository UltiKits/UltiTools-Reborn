package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.Ack;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridSession;
import com.ultikits.ultitools.ui.ShowOptions;
import com.ultikits.ultitools.ui.UiResult;
import com.ultikits.ultitools.ui.UiService;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * A state-derived screen shown through {@link UiService}. Subclasses only describe widgets;
 * backend selection, clicks and close reasons belong to the UI service.
 * @since 6.2.0
 */
public abstract class DeclarativeGui {
    private static final Logger LOGGER = Logger.getLogger(DeclarativeGui.class.getName());
    protected final String id;
    private final UiService ui;
    private final Player viewer;
    private final int rows;
    private final GuiRenderer renderer;
    private Grid firstGrid;
    private GridSession session;
    private boolean initialized;

    /**
     * @param ui the screen owner's UI service
     * @param scheduler the module's own injected scheduler
     * @param viewer the viewer
     * @param id the build-context identifier
     * @param title deferred screen title
     * @param rows fixed row count, 1..6
     */
    protected DeclarativeGui(UiService ui, Scheduler scheduler, Player viewer, String id, Text title, int rows) {
        this.ui = Objects.requireNonNull(ui, "ui");
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.id = Objects.requireNonNull(id, "id");
        this.rows = rows;
        renderer = new GuiRenderer(title, rows, new GuiScheduler(scheduler, viewer), this::acceptGrid);
    }

    /** @param context the root build context @return a widget tree derived from current state */
    public abstract @NotNull Widget build(@NotNull BuildContext context);

    /**
     * Synchronously builds the first complete frame, then shows it. Later frames update the same session.
     * Calling this while the page is open returns that session; after its result arrives the page may reopen.
     * @return the grid session
     * @throws IllegalStateException if not on the viewer's owning thread, or called re-entrantly during opening
     */
    public final GridSession open() {
        if (!renderer.getScheduler().isOnMainThread()) {
            throw new IllegalStateException("open() must be called on the viewer's owning thread");
        }
        if (initialized) {
            if (session == null) {
                throw new IllegalStateException("The declarative screen is already opening");
            }
            return session;
        }
        initialized = true;
        BuildContext context = BuildContext.root(viewer, id, rows);
        try {
            renderer.initialize(() -> build(context), context);
            session = ui.show(viewer, Objects.requireNonNull(firstGrid, "The first frame did not finish"), ShowOptions.defaults());
        } catch (RuntimeException | Error failure) {
            initialized = false;
            try {
                renderer.dispose();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        GridSession opened = session;
        opened.result().thenAccept(this::finished);
        return opened;
    }

    private void acceptGrid(Grid grid) {
        if (session == null) {
            firstGrid = grid;
        } else {
            session.update(grid);
        }
    }

    private void finished(UiResult<Ack> result) {
        initialized = false;
        session = null;
        firstGrid = null;
        try {
            renderer.dispose();
        } catch (RuntimeException | Error failure) {
            logCloseFailure("dispose", failure);
        }
        try {
            onGuiClose(result);
        } catch (RuntimeException | Error failure) {
            logCloseFailure("onGuiClose", failure);
        }
    }

    private void logCloseFailure(String phase, Throwable failure) {
        LOGGER.log(Level.WARNING, "Declarative screen '" + id + "' owned by module '" + ui.owner()
                + "' failed during " + phase, failure);
    }

    /** Requests a later frame while this page is mounted. */
    protected void markNeedsBuild() {
        if (initialized) {
            renderer.scheduleBuild();
        }
    }

    /** @param action modifies state before requesting a later frame */
    protected void setState(@NotNull Runnable action) {
        action.run();
        markNeedsBuild();
    }

    /** @param result the service's final close result; resources have already been disposed */
    protected void onGuiClose(@NotNull UiResult<Ack> result) {
        // Optional subclass cleanup after the session ends.
    }

    /** @return the renderer owning this page's elements */
    protected @NotNull GuiRenderer getRenderer() { return renderer; }
    /** @return whether the page is mounted and awaiting its session result */
    public boolean isInitialized() { return initialized; }
    /** @return the viewer */
    public @NotNull Player viewer() { return viewer; }
}
