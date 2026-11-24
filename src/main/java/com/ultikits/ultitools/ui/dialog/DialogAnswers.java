package com.ultikits.ultitools.ui.dialog;

import com.ultikits.ultitools.ui.Ack;
import com.ultikits.ultitools.ui.Choice;
import com.ultikits.ultitools.ui.ChoiceInput;
import com.ultikits.ultitools.ui.CloseReason;
import com.ultikits.ultitools.ui.Confirm;
import com.ultikits.ultitools.ui.Form;
import com.ultikits.ultitools.ui.FormInput;
import com.ultikits.ultitools.ui.FormResponse;
import com.ultikits.ultitools.ui.Notice;
import com.ultikits.ultitools.ui.SliderInput;
import com.ultikits.ultitools.ui.TextInput;
import com.ultikits.ultitools.ui.ToggleInput;
import com.ultikits.ultitools.ui.UiIntent;
import com.ultikits.ultitools.ui.UiResult;
import io.papermc.paper.dialog.DialogResponseView;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Validates one dialog action against the original intent. */
@ApiStatus.Internal
public final class DialogAnswers {
    private final Logger logger;

    /** Creates the answer decoder with framework logging. */
    public DialogAnswers(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Returns a validated answer or a player-closed result. */
    public UiResult<?> read(UiIntent<?> intent, ButtonRole role, @Nullable DialogResponseView response, String owner) {
        try {
            return checked(intent, role, response);
        } catch (IllegalArgumentException invalid) {
            return rejected(owner, invalid);
        }
    }

    private UiResult<?> rejected(String owner, RuntimeException failure) {
        logger.log(Level.WARNING, "Rejected dialog answer for owner " + owner, failure);
        return new UiResult.Closed<>(CloseReason.PLAYER);
    }

    private static UiResult<?> checked(UiIntent<?> intent, ButtonRole role, @Nullable DialogResponseView response) {
        if (role instanceof ButtonRole.Cancel) {
            return new UiResult.Closed<>(CloseReason.PLAYER);
        }
        if (intent instanceof Notice && role instanceof ButtonRole.Ack) {
            return new UiResult.Completed<>(Ack.ACK);
        }
        if (intent instanceof Confirm) {
            if (role instanceof ButtonRole.Yes) {
                return new UiResult.Completed<>(true);
            }
            if (role instanceof ButtonRole.No) {
                return new UiResult.Completed<>(false);
            }
        }
        if (intent instanceof Form form && role instanceof ButtonRole.Submit) {
            if (response == null) {
                throw new IllegalArgumentException("Missing form response");
            }
            return new UiResult.Completed<>(form(form, response));
        }
        if (intent instanceof Choice<?> choice && role instanceof ButtonRole.Pick pick) {
            if (pick.index() < 0 || pick.index() >= choice.options().size()) {
                throw new IllegalArgumentException("Unknown choice position");
            }
            return new UiResult.Completed<>(choice.options().get(pick.index()).value());
        }
        throw new IllegalArgumentException("Button does not belong to this intent");
    }

    private static FormResponse form(Form form, DialogResponseView response) {
        Map<FormInput<?>, Object> values = new IdentityHashMap<>();
        for (FormInput<?> input : form.inputs()) {
            Object value = switch (input) {
                case TextInput text -> response.getText(text.key());
                case ToggleInput toggle -> response.getBoolean(toggle.key());
                case ChoiceInput<?> choice -> option(choice, response.getText(choice.key()));
                case SliderInput slider -> number(response, slider.key());
            };
            if (value == null) {
                throw new IllegalArgumentException("Missing value for " + input.key());
            }
            values.put(input, value);
        }
        return FormResponse.of(form, values);
    }

    private static Object option(ChoiceInput<?> input, @Nullable String id) {
        if (id == null) {
            throw new IllegalArgumentException("Missing option id for " + input.key());
        }
        int index = Integer.parseInt(id);
        if (index < 0 || index >= input.options().size() || !Integer.toString(index).equals(id)) {
            throw new IllegalArgumentException("Unknown option id for " + input.key());
        }
        return input.options().get(index).value();
    }

    private static double number(DialogResponseView response, String key) {
        Float value = response.getFloat(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing slider value for " + key);
        }
        return value.doubleValue();
    }
}
