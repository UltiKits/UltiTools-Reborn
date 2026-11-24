package com.ultikits.ultitools.ui.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.data.dialog.type.MultiActionType;
import java.time.Duration;
import java.util.List;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/** The only adapter that constructs server-owned Paper dialog registry objects. */
@ApiStatus.Internal
public final class PaperDialogPresenter implements DialogPresenter {
    @Override
    public void show(Player viewer, DialogModel model, DialogTicket ticket) {
        DialogBase base = DialogBase.builder(model.title())
                .body(model.body().stream().map(DialogBody::plainMessage).toList())
                .inputs(model.inputs().stream().map(PaperDialogPresenter::input).toList())
                .canCloseWithEscape(model.escapeCloses())
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        List<ActionButton> buttons = model.buttons().stream().map(button -> button(button, ticket)).toList();
        DialogType type = switch (model.kind()) {
            case NOTICE -> DialogType.notice(buttons.getFirst());
            case CONFIRMATION -> DialogType.confirmation(buttons.get(0), buttons.get(1));
            case MULTI_ACTION -> multiAction(model, buttons, ticket);
        };
        viewer.showDialog(Dialog.create(builder -> builder.empty().base(base).type(type)));
    }

    private static DialogType multiAction(DialogModel model, List<ActionButton> buttons, DialogTicket ticket) {
        MultiActionType.Builder builder = DialogType.multiAction(buttons).columns(1);
        if (model.exit() != null) {
            builder.exitAction(button(model.exit(), ticket));
        }
        return builder.build();
    }

    private static ActionButton button(DialogModel.Button button, DialogTicket ticket) {
        ButtonRole role = button.role();
        ActionButton.Builder builder = ActionButton.builder(button.label())
                .action(DialogAction.customClick((response, audience) -> ticket.accept(role, response, audience),
                        ClickCallback.Options.builder().uses(1).lifetime(Duration.ofHours(12)).build()));
        if (button.tooltip() != null) {
            builder.tooltip(button.tooltip());
        }
        return builder.build();
    }

    private static DialogInput input(DialogModel.Input input) {
        return switch (input) {
            case DialogModel.Text text -> text(text);
            case DialogModel.Toggle toggle -> DialogInput.bool(toggle.key(), toggle.label()).initial(toggle.initial()).build();
            case DialogModel.Single single -> DialogInput.singleOption(single.key(), single.label(), single.options().stream()
                    .map(option -> SingleOptionDialogInput.OptionEntry.create(option.id(), option.display(), option.initial())).toList()).build();
            case DialogModel.Range range -> DialogInput.numberRange(range.key(), range.label(), range.start(), range.end())
                    .step(range.step()).initial(range.initial()).build();
        };
    }

    private static TextDialogInput text(DialogModel.Text text) {
        TextDialogInput.Builder builder = DialogInput.text(text.key(), text.label())
                .initial(text.initial()).maxLength(text.maxLength());
        if (text.maxLines() > 1) {
            builder.multiline(TextDialogInput.MultilineOptions.create(text.maxLines(), null));
        }
        return builder.build();
    }

    @Override public void dismiss(Player viewer) { viewer.closeDialog(); }
}
