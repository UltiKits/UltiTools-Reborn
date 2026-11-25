package com.ultikits.ultitools.ui.dialog;

import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.ui.Choice;
import com.ultikits.ultitools.ui.Confirm;
import com.ultikits.ultitools.ui.Form;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridSession;
import com.ultikits.ultitools.ui.Notice;
import com.ultikits.ultitools.ui.SliderInput;
import com.ultikits.ultitools.ui.UiBackend;
import com.ultikits.ultitools.ui.UiIntent;
import com.ultikits.ultitools.ui.UiSession;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/** Paper Dialog renderer for notices, confirmations, forms and choices. */
@ApiStatus.Internal
public final class DialogBackend implements UiBackend {
    static final boolean ESCAPE_CLOSES = false;
    private final DialogMapper mapper;
    private final DialogPresenter presenter;
    private final DialogAnswers answers;
    private final Logger logger;
    private final Function<? super UiSession<?>, String> ownerNames;
    private final Map<UiSession<?>, DialogTicket> tickets = new ConcurrentHashMap<>();

    /** Creates an isolated backend; registration and message catalogues belong to runtime wiring. */
    public DialogBackend(Messages messages, DialogPresenter presenter, Logger logger,
                         Function<? super UiSession<?>, String> ownerNames) {
        mapper = new DialogMapper(messages);
        this.presenter = Objects.requireNonNull(presenter, "presenter");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.ownerNames = Objects.requireNonNull(ownerNames, "ownerNames");
        answers = new DialogAnswers(logger);
    }

    /** Creates the Paper presenter without registering it or constructing any registry objects. */
    public static DialogBackend paper(Messages messages, Logger logger,
                                      Function<? super UiSession<?>, String> ownerNames) {
        return new DialogBackend(messages, new PaperDialogPresenter(), logger, ownerNames);
    }

    @Override public Key id() { return Key.key("ultitools", "dialog"); }
    @Override public int priority() { return 100; }

    @Override
    public boolean supports(UiIntent<?> intent, Player viewer) {
        return switch (intent) {
            case Notice ignored -> true;
            case Confirm ignored -> true;
            case Choice<?> ignored -> true;
            case Form form -> form.inputs().stream().filter(SliderInput.class::isInstance)
                    .map(SliderInput.class::cast).allMatch(DialogBackend::representable);
            case Grid ignored -> false;
            default -> false;
        };
    }

    /**
     * Bounds the float spacing at the largest absolute endpoint, including negative and zero-crossing ranges.
     * Requiring {@code ULP <= step / 2} bounds nearest-float rounding error by step / 4, leaving a margin before
     * the contract's half-step snap boundary. Equality is accepted; a larger ULP rejects the whole range
     * even when its endpoints and initial value happen to be exactly representable. Endpoint and initial
     * casts are also checked explicitly, and a non-finite or zero float step remains unsupported.
     */
    private static boolean representable(SliderInput slider) {
        float step = (float) slider.step();
        double tolerance = slider.step() / 4;
        float magnitude = (float) Math.max(Math.abs(slider.min()), Math.abs(slider.max()));
        return Float.isFinite(step) && step > 0 && Math.ulp(magnitude) <= slider.step() / 2
                && representable(slider.min(), tolerance) && representable(slider.max(), tolerance)
                && representable(slider.initial(), tolerance);
    }

    private static boolean representable(double value, double tolerance) {
        float single = (float) value;
        return Float.isFinite(single) && Math.abs(value - (double) single) <= tolerance;
    }

    @Override
    public <R> void open(UiSession<R> session, BackendCallbacks<R> callbacks) {
        DialogModel model = mapper.map(session.intent(), session.viewer(), ESCAPE_CLOSES);
        DialogTicket ticket = new DialogTicket(session, callbacks, answers, logger, ownerNames.apply(session));
        tickets.put(session, ticket);
        presenter.show(session.viewer(), model, ticket);
        ticket.shown();
    }

    /** Reports game-driven dismissal only for this backend's shown dialogs, on the server thread. */
    public void interrupt(Player player) {
        tickets.forEach((session, ticket) -> {
            if (session.viewer().getUniqueId().equals(player.getUniqueId())) { ticket.interrupt(); }
        });
    }

    @Override public void update(GridSession session, Grid previous, Grid next) {
        throw new IllegalStateException("Dialogs do not support grids");
    }

    /**
     * Dismisses only a successfully shown, unanswered screen. Paper provides no dialog replacement signal:
     * if another plugin has since shown its own dialog, our later timeout dismissal also closes that dialog.
     */
    @Override
    public void close(UiSession<?> session) {
        DialogTicket ticket = tickets.remove(session);
        if (ticket != null && ticket.invalidate() && session.viewer().isOnline()) {
            presenter.dismiss(session.viewer());
        }
    }
}
