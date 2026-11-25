package com.ultikits.ultitools.ui.dialog;

import com.ultikits.ultitools.ui.CloseReason;
import com.ultikits.ultitools.ui.UiBackend.BackendCallbacks;
import com.ultikits.ultitools.ui.UiIntent;
import com.ultikits.ultitools.ui.UiResult;
import com.ultikits.ultitools.ui.UiSession;
import io.papermc.paper.dialog.DialogResponseView;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Invalidatable lifetime boundary for server-retained button callbacks. */
@ApiStatus.Internal
public final class DialogTicket {
    private final UUID viewerId;
    private final String ownerName;
    private final Logger logger;
    private final AtomicBoolean showing = new AtomicBoolean();
    private final AtomicReference<Callbacks> handler;

    /**
     * Retains the typed callbacks only until the first answer or invalidation.
     * @param <R> the intent's answer type
     */
    <R> DialogTicket(UiSession<R> session, BackendCallbacks<R> callbacks, DialogAnswers answers, Logger logger,
                     String ownerName) {
        viewerId = session.viewer().getUniqueId();
        this.logger = logger;
        this.ownerName = ownerName;
        UiIntent<R> intent = session.intent();
        handler = new AtomicReference<>(new Callbacks(
                (role, response) -> report(intent, callbacks, answers, role, response, ownerName),
                () -> callbacks.closed(CloseReason.INTERRUPTED)));
    }

    /**
     * Forwards only the value type validated against this same intent.
     * @param <R> the intent's answer type
     */
    private static <R> void report(UiIntent<R> intent, BackendCallbacks<R> callbacks, DialogAnswers answers,
                                   ButtonRole role, @Nullable DialogResponseView response, String ownerName) {
        UiResult<?> result = answers.read(intent, role, response, ownerName);
        if (result instanceof UiResult.Closed<?> closed) {
            callbacks.closed(closed.reason());
        } else {
            // The decoder only produces the value type of this very intent. The sealed generic SPI erases that relation.
            callbacks.completed((R) result.value());
        }
    }

    /** Marks the screen visible only after the presenter returns successfully on the entity lane. */
    void shown() {
        showing.set(true);
    }

    /**
     * Accepts an action from Paper without running module code directly.
     * Paper dispatches clicks on the server main thread, which also runs entity-lane close operations.
     * This serialization prevents invalidation between consuming the handler and clearing showing below;
     * the two atomic operations alone do not make that transition safe for concurrent callback dispatch.
     */
    public void accept(ButtonRole role, @Nullable DialogResponseView response, Audience audience) {
        Callbacks current = handler.get();
        if (current == null) {
            return;
        }
        if (!(audience instanceof Player player) || !viewerId.equals(player.getUniqueId())) {
            logger.warning("Ignored dialog action from a different audience for owner " + ownerName);
            return;
        }
        if (handler.compareAndSet(current, null)) {
            showing.set(false);
            current.answer().accept(role, response);
        }
    }

    /** Ends a game-interrupted screen without sending a later clear-dialog packet to a different screen. */
    void interrupt() {
        if (!showing.getAndSet(false)) { return; }
        Callbacks current = handler.getAndSet(null);
        if (current != null) { current.interruption().run(); }
    }

    private record Callbacks(BiConsumer<ButtonRole, @Nullable DialogResponseView> answer, Runnable interruption) { }

    /** Drops all references to the intent and callbacks, returning whether a screen still needs dismissal. */
    boolean invalidate() {
        handler.set(null);
        return showing.getAndSet(false);
    }
}
