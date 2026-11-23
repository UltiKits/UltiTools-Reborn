package com.ultikits.ultitools.command.engine;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandRegistrationFlag;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.configuration.PluginMeta;

/** Exercises the real dispatcher without pretending MockBukkit fires lifecycle events. */
final class RecordingCommands implements Commands {
    private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
    int registrations;
    @Override public CommandDispatcher<CommandSourceStack> getDispatcher() { return dispatcher; }
    @Override public Set<String> register(LiteralCommandNode<CommandSourceStack> node, String description,
            Collection<String> aliases) {
        registrations++;
        dispatcher.getRoot().addChild(node);
        Set<String> registered = new LinkedHashSet<>();
        registered.add(node.getLiteral());
        for (String alias : aliases) {
            dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal(alias)
                    .requires(node.getRequirement()).redirect(node));
            registered.add(alias);
        }
        return registered;
    }
    @Override public Set<String> register(PluginMeta meta, LiteralCommandNode<CommandSourceStack> node,
            String description, Collection<String> aliases) { return register(node, description, aliases); }
    @Override public Set<String> registerWithFlags(PluginMeta meta, LiteralCommandNode<CommandSourceStack> node,
            String description, Collection<String> aliases, Set<CommandRegistrationFlag> flags) {
        return register(node, description, aliases);
    }
    @Override public Set<String> register(String label, String description, Collection<String> aliases, BasicCommand command) {
        throw new UnsupportedOperationException("BasicCommand is not the engine's registration path");
    }
    @Override public Set<String> register(PluginMeta meta, String label, String description,
            Collection<String> aliases, BasicCommand command) { return register(label, description, aliases, command); }
}
