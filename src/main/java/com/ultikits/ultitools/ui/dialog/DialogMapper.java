package com.ultikits.ultitools.ui.dialog;

import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.Choice;
import com.ultikits.ultitools.ui.ChoiceInput;
import com.ultikits.ultitools.ui.ChoiceOption;
import com.ultikits.ultitools.ui.Confirm;
import com.ultikits.ultitools.ui.Form;
import com.ultikits.ultitools.ui.FormInput;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.Notice;
import com.ultikits.ultitools.ui.SliderInput;
import com.ultikits.ultitools.ui.TextInput;
import com.ultikits.ultitools.ui.ToggleInput;
import com.ultikits.ultitools.ui.UiIntent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Converts supported intents into a viewer-localized dialog description. */
@ApiStatus.Internal
public final class DialogMapper {
    private final Messages messages;

    /** Creates a mapper using the framework's message contract. */
    public DialogMapper(Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /** Maps one supported intent without constructing any Paper registry object. */
    public DialogModel map(UiIntent<?> intent, Player viewer, boolean escapeCloses) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(viewer, "viewer");
        return switch (intent) {
            case Notice notice -> model(notice, viewer, notice.body(), List.of(),
                    List.of(button(notice.button(), null, new ButtonRole.Ack(), viewer)), DialogModel.Kind.NOTICE, escapeCloses);
            case Confirm confirm -> model(confirm, viewer, confirm.body(), List.of(), List.of(
                    button(confirm.yes(), null, new ButtonRole.Yes(), viewer),
                    button(confirm.no(), null, new ButtonRole.No(), viewer)), DialogModel.Kind.CONFIRMATION, escapeCloses);
            case Form form -> form(form, viewer, escapeCloses);
            case Choice<?> choice -> choice(choice, viewer, escapeCloses);
            case Grid ignored -> throw new IllegalArgumentException("Dialogs do not support grids");
            default -> throw new IllegalArgumentException("Unsupported dialog intent");
        };
    }

    private DialogModel model(UiIntent<?> intent, Player viewer, List<Text> body, List<DialogModel.Input> inputs,
                              List<DialogModel.Button> buttons, DialogModel.Kind kind, boolean escapeCloses) {
        return model(intent, viewer, body, inputs, buttons, kind, escapeCloses, null);
    }

    private DialogModel model(UiIntent<?> intent, Player viewer, List<Text> body, List<DialogModel.Input> inputs,
                              List<DialogModel.Button> buttons, DialogModel.Kind kind, boolean escapeCloses,
                              @Nullable DialogModel.Button exit) {
        return new DialogModel(messages.render(intent.title(), viewer),
                body.stream().map(line -> messages.render(line, viewer)).toList(), inputs, buttons, exit, kind, escapeCloses);
    }

    private DialogModel form(Form form, Player viewer, boolean escapeCloses) {
        DialogModel.Button submit = button(form.submit(), null, new ButtonRole.Submit(), viewer);
        List<DialogModel.Button> buttons = escapeCloses ? List.of(submit) : List.of(submit, cancel(viewer));
        DialogModel.Kind kind = escapeCloses ? DialogModel.Kind.NOTICE : DialogModel.Kind.CONFIRMATION;
        List<DialogModel.Input> inputs = new ArrayList<>();
        for (int index = 0; index < form.inputs().size(); index++) {
            inputs.add(input(form.inputs().get(index), DialogModel.inputKey(index), viewer));
        }
        return model(form, viewer, form.body(), inputs, buttons, kind, escapeCloses);
    }

    private DialogModel.Button cancel(Player viewer) {
        return button(Text.of(MessageKey.of("ultitools", "ui.cancel")), null, new ButtonRole.Cancel(), viewer);
    }

    private DialogModel.Button button(Text label, @Nullable Text tooltip, ButtonRole role, Player viewer) {
        Component renderedTooltip = tooltip == null ? null : messages.render(tooltip, viewer);
        return new DialogModel.Button(messages.render(label, viewer), renderedTooltip, role);
    }

    private DialogModel choice(Choice<?> choice, Player viewer, boolean escapeCloses) {
        List<DialogModel.Button> buttons = new ArrayList<>();
        for (int index = 0; index < choice.options().size(); index++) {
            ChoiceOption<?> option = choice.options().get(index);
            buttons.add(button(option.label(), option.tooltip(), new ButtonRole.Pick(index), viewer));
        }
        return model(choice, viewer, choice.body(), List.of(), buttons, DialogModel.Kind.MULTI_ACTION, escapeCloses,
                escapeCloses ? null : cancel(viewer));
    }

    private DialogModel.Input input(FormInput<?> input, String wireKey, Player viewer) {
        Component label = messages.render(input.label(), viewer);
        return switch (input) {
            case TextInput text -> new DialogModel.Text(wireKey, label, text.initial(), text.maxLength(), text.maxLines());
            case ToggleInput toggle -> new DialogModel.Toggle(wireKey, label, toggle.initial());
            case ChoiceInput<?> single -> single(single, wireKey, label, viewer);
            case SliderInput range -> new DialogModel.Range(wireKey, label, (float) range.min(), (float) range.max(),
                    (float) range.step(), (float) range.initial());
            default -> throw new IllegalArgumentException("Unsupported dialog input");
        };
    }

    /** Paper option entries have no tooltip field; form option tooltips are not displayed. */
    private DialogModel.Single single(ChoiceInput<?> input, String wireKey, Component label, Player viewer) {
        List<DialogModel.Option> options = new ArrayList<>();
        for (int index = 0; index < input.options().size(); index++) {
            ChoiceOption<?> option = input.options().get(index);
            options.add(new DialogModel.Option(Integer.toString(index), messages.render(option.label(), viewer),
                    option.value().equals(input.initial())));
        }
        return new DialogModel.Single(wireKey, label, options);
    }
}
