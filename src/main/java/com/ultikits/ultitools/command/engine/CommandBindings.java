package com.ultikits.ultitools.command.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import com.mojang.brigadier.tree.CommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

/** Separates lifecycle-registered shapes from live module instances. */
final class CommandBindings {
    record Binding(Object owner, String ownerName, Object instance, CompiledCommand command) {}
    private final Map<String, Binding> candidates = new LinkedHashMap<>();
    private final Map<String, Binding> active = new ConcurrentHashMap<>();
    private final Map<String, String> delivered = new LinkedHashMap<>();
    private final Logger logger;
    private final Runnable updatePlayers;
    private boolean initialComplete;

    CommandBindings(Logger logger, Runnable updatePlayers) {
        this.logger = logger;
        this.updatePlayers = updatePlayers;
    }

    synchronized boolean bind(Object owner, String ownerName, Object instance, CompiledCommand command) {
        Binding previous = candidates.get(command.label());
        if (previous != null && !Objects.equals(owner, previous.owner())) {
            logger.severe("Command /" + command.label() + " from " + ownerName + " (" + instance.getClass().getName()
                    + ") conflicts with " + previous.ownerName() + " (" + previous.instance().getClass().getName() + "); rejected.");
            return false;
        }
        Binding binding = new Binding(owner, ownerName, instance, command);
        candidates.put(command.label(), binding);
        if (command.signature().equals(delivered.get(command.label()))) {
            active.put(command.label(), binding);
            updatePlayers.run();
        } else {
            active.remove(command.label());
            if (initialComplete) warnDeferred(binding);
        }
        return true;
    }

    private void warnDeferred(Binding binding) {
        logger.warning("Command /" + binding.command().label() + " from " + binding.ownerName()
                + " was registered after start-up; it becomes available after /minecraft:reload or a restart.");
    }

    synchronized void unbind(Object owner) {
        List<String> labels = candidates.entrySet().stream()
                .filter(entry -> Objects.equals(owner, entry.getValue().owner())).map(Map.Entry::getKey).toList();
        labels.forEach(candidates::remove);
        labels.forEach(active::remove);
        if (!labels.isEmpty()) updatePlayers.run();
    }

    synchronized void clear() {
        candidates.clear();
        active.clear();
        updatePlayers.run();
    }

    Binding find(String label) { return active.get(label); }

    CommandNode<CommandSourceStack> node(String label, List<String> path) {
        Binding binding = find(label);
        if (binding == null) return null;
        CommandNode<CommandSourceStack> node = binding.command().tree();
        for (String part : path) {
            node = node.getChild(part);
            if (node == null) return null;
        }
        return node;
    }

    int dispatch(String label, String input, CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Binding binding = find(label);
        if (binding == null) throw ProxyTreeFactory.unknown();
        var proxy = new ProxyTreeFactory(this).create(label, binding.command().tree());
        String actual = input.split("\\s+", 2)[0];
        var root = com.mojang.brigadier.builder.LiteralArgumentBuilder.<CommandSourceStack>literal(actual)
                .requires(proxy.getRequirement()).executes(proxy.getCommand()).build();
        proxy.getChildren().forEach(root::addChild);
        var dispatcher = new com.mojang.brigadier.CommandDispatcher<CommandSourceStack>();
        dispatcher.getRoot().addChild(root);
        return dispatcher.execute(input, source);
    }

    synchronized Object ownerByLabel(String input) {
        String label = input.toLowerCase(Locale.ROOT);
        int namespace = label.indexOf(':');
        if (namespace >= 0) label = label.substring(namespace + 1);
        for (Binding binding : candidates.values()) {
            CompiledCommand command = binding.command();
            if (command.label().equals(label) || command.aliases().contains(label)) return binding.owner();
        }
        return null;
    }

    /** Called only while Paper makes its registrar available. Never retains the registrar. */
    synchronized void onCommands(Commands commands) {
        active.clear();
        delivered.clear();
        ProxyTreeFactory proxies = new ProxyTreeFactory(this);
        for (Binding binding : new ArrayList<>(candidates.values())) {
            CompiledCommand command = binding.command();
            active.put(command.label(), binding);
            var registered = commands.register(proxies.create(command.label(), command.tree()),
                    command.description(), command.aliases());
            List<String> labels = new ArrayList<>(command.aliases());
            labels.add(command.label());
            for (String label : labels) {
                if (!registered.contains(label)) logger.warning("Command label /" + label + " from "
                        + binding.ownerName() + " was not registered by Paper (label occupied).");
            }
            delivered.put(command.label(), command.signature());
        }
        initialComplete = true;
    }
}
