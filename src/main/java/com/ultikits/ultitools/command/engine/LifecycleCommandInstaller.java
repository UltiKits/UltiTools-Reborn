package com.ultikits.ultitools.command.engine;

import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

/** Installs the only registrar callback while the framework is enabling. */
final class LifecycleCommandInstaller {
    private final Supplier<LifecycleEventManager<Plugin>> lifecycle;
    private final CommandBindings bindings;
    private final Logger logger;
    private boolean installed;

    LifecycleCommandInstaller(Supplier<LifecycleEventManager<Plugin>> lifecycle, CommandBindings bindings, Logger logger) {
        this.lifecycle = lifecycle;
        this.bindings = bindings;
        this.logger = logger;
    }

    synchronized void ensureInstalled() {
        if (installed) return;
        try {
            lifecycle.get().registerEventHandler(LifecycleEvents.COMMANDS.newHandler(event -> bindings.onCommands(event.registrar())));
            installed = true;
        } catch (IllegalStateException exception) {
            logger.severe("Cannot install command lifecycle handler: " + exception.getMessage());
        }
    }

    synchronized boolean isInstalled() { return installed; }
}
