package com.ultikits.ultitools.ui.dialog;

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
    private final Logger logger;
    private final AtomicBoolean showing = new AtomicBoolean(true);
    private final AtomicReference<BiConsumer<ButtonRole, @Nullable DialogResponseView>> handler;

    <R> DialogTicket(UiSession<R> session, BackendCallbacks<R> callbacks, DialogAnswers answers, Logger logger) {
        viewerId = session.viewer().getUniqueId();
        this.logger = logger;
        UiIntent<R> intent = session.intent();
        handler = new AtomicReference<>((role, response) -> report(intent, callbacks, answers, role, response));
    }

    private static <R> void report(UiIntent<R> intent, BackendCallbacks<R> callbacks, DialogAnswers answers,
                                   ButtonRole role, @Nullable DialogResponseView response) {
        // The current session seam deliberately exposes no owner; never infer it from a title or viewer.
        UiResult<?> result = answers.read(intent, role, response, "<unavailable through UiSession>");
        if (result instanceof UiResult.Closed<?> closed) {
            callbacks.closed(closed.reason());
        } else {
            // The decoder only produces the value type of this very intent. The sealed generic SPI erases that relation.
            callbacks.completed((R) result.value());
        }
    }

    /** Accepts an action from Paper without running module code directly. */
    public void accept(ButtonRole role, @Nullable DialogResponseView response, Audience audience) {
        BiConsumer<ButtonRole, @Nullable DialogResponseView> current = handler.get();
        if (current == null) {
            return;
        }
        if (!(audience instanceof Player player) || !viewerId.equals(player.getUniqueId())) {
            logger.warning("Ignored dialog action from a different audience; owner unavailable through UiSession");
            return;
        }
        if (handler.compareAndSet(current, null)) {
            showing.set(false);
            current.accept(role, response);
        }
    }

    /** Drops all references to the intent and callbacks, returning whether a screen still needs dismissal. */
    boolean invalidate() {
        handler.set(null);
        return showing.getAndSet(false);
    }
}
