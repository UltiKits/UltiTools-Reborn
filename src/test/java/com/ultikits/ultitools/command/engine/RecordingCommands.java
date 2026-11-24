package com.ultikits.ultitools.command.engine;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandRegistrationFlag;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.configuration.PluginMeta;

/** Exercises the real dispatcher without pretending MockBukkit fires lifecycle events. */
public final class RecordingCommands implements Commands {
    private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
    int registrations;
    private final String namespace;

    public RecordingCommands() { this(null); }
    RecordingCommands(String namespace) { this.namespace = namespace; }

    @Override public CommandDispatcher<CommandSourceStack> getDispatcher() { return dispatcher; }
    @Override public Set<String> register(LiteralCommandNode<CommandSourceStack> node, String description,
            Collection<String> aliases) {
        registrations++;
        // Paper's ApiMirrorRootNode.simpleUnwrap/unwrapArgumentWrapper discard node overrides.
        CommandNode<CommandSourceStack> rebuilt = rebuild(node, new IdentityHashMap<>());
        dispatcher.getRoot().addChild(rebuilt);
        Set<String> registered = new LinkedHashSet<>();
        registered.add(node.getLiteral());
        for (String alias : aliases) {
            dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal(alias)
                    .requires(rebuilt.getRequirement()).redirect(rebuilt));
            registered.add(alias);
        }
        if (namespace != null) {
            for (String label : Set.copyOf(registered)) {
                String qualified = namespace + ":" + label;
                dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal(qualified)
                        .requires(rebuilt.getRequirement()).redirect(rebuilt));
                registered.add(qualified);
            }
        }
        return registered;
    }
    private static CommandNode<CommandSourceStack> rebuild(CommandNode<CommandSourceStack> node,
            Map<CommandNode<CommandSourceStack>, CommandNode<CommandSourceStack>> copies) {
        CommandNode<CommandSourceStack> existing = copies.get(node);
        if (existing != null) return existing;
        var builder = node.createBuilder();
        CommandNode<CommandSourceStack> redirect = node.getRedirect();
        if (redirect != null) builder.forward(rebuild(redirect, copies), node.getRedirectModifier(), node.isFork());
        CommandNode<CommandSourceStack> copy = builder.build();
        copies.put(node, copy);
        for (CommandNode<CommandSourceStack> child : node.getChildren()) copy.addChild(rebuild(child, copies));
        return copy;
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
