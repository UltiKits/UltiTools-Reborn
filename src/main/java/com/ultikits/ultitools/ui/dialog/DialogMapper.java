package com.ultikits.ultitools.ui.dialog;

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
            case Form form -> model(form, viewer, form.body(), form.inputs().stream().map(input -> input(input, viewer)).toList(),
                    List.of(button(form.submit(), null, new ButtonRole.Submit(), viewer)), DialogModel.Kind.NOTICE, escapeCloses);
            case Choice<?> choice -> choice(choice, viewer, escapeCloses);
            case Grid ignored -> throw new IllegalArgumentException("Dialogs do not support grids");
        };
    }

    private DialogModel model(UiIntent<?> intent, Player viewer, List<Text> body, List<DialogModel.Input> inputs,
                              List<DialogModel.Button> buttons, DialogModel.Kind kind, boolean escapeCloses) {
        return new DialogModel(messages.render(intent.title(), viewer),
                body.stream().map(line -> messages.render(line, viewer)).toList(), inputs, buttons, null, kind, escapeCloses);
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
        return model(choice, viewer, choice.body(), List.of(), buttons, DialogModel.Kind.MULTI_ACTION, escapeCloses);
    }

    private DialogModel.Input input(FormInput<?> input, Player viewer) {
        Component label = messages.render(input.label(), viewer);
        return switch (input) {
            case TextInput text -> new DialogModel.Text(text.key(), label, text.initial(), text.maxLength(), text.maxLines());
            case ToggleInput toggle -> new DialogModel.Toggle(toggle.key(), label, toggle.initial());
            case ChoiceInput<?> single -> single(single, label, viewer);
            case SliderInput range -> new DialogModel.Range(range.key(), label, (float) range.min(), (float) range.max(),
                    (float) range.step(), (float) range.initial());
        };
    }

    private DialogModel.Single single(ChoiceInput<?> input, Component label, Player viewer) {
        List<DialogModel.Option> options = new ArrayList<>();
        for (int index = 0; index < input.options().size(); index++) {
            ChoiceOption<?> option = input.options().get(index);
            options.add(new DialogModel.Option(Integer.toString(index), messages.render(option.label(), viewer),
                    option.value().equals(input.initial())));
        }
        return new DialogModel.Single(input.key(), label, options);
    }
}
