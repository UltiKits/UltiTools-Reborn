package com.ultikits.ultitools.command.engine;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.command.UnknownCommandEvent;

/** Recovers only unavailable literals rejected before a framework fallback can run. */
final class HiddenCommandListener implements Listener {
    private final CommandBindings bindings;

    HiddenCommandListener(CommandBindings bindings) {
        this.bindings = bindings;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUnknownCommand(UnknownCommandEvent event) throws CommandSyntaxException {
        String input = event.getCommandLine();
        String label = input.split("\\s+", 2)[0];
        FrameworkReplies replies = bindings.replies(label);
        if (replies == null || !replies.unavailableLiteral(input, event.getCommandSource())) return;
        event.message(null);
        replies.reply(input, event.getCommandSource());
    }
}
