package com.ultikits.ultitools.command.engine;

import java.util.List;
import java.util.Locale;
import com.ultikits.ultitools.command.BrigadierCommand;
import java.util.Arrays;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.context.MergedAnnotationResolver;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.ApiStatus;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;

/** Internal cross-package facade; all tree and binding details stay in this package. */
@ApiStatus.Internal
public final class CommandEngine {
    private final CommandBindings bindings;
    private final CommandTreeCompiler compiler;
    private final LifecycleCommandInstaller installer;
    private boolean listenerInstalled;

    /** Creates the Paper-backed engine. */
    public CommandEngine() {
        this(new PaperNativeArgumentTypes(), Bukkit.getLogger());
    }

    CommandEngine(NativeArgumentTypes types, Logger logger) {
        bindings = new CommandBindings(logger, () -> Bukkit.getOnlinePlayers().forEach(player -> player.updateCommands()));
        compiler = new CommandTreeCompiler(types);
        installer = new LifecycleCommandInstaller(() -> UltiTools.getInstance().getLifecycleManager(), bindings, logger);
    }

    /**
     * Registers an annotation executor under its owner without accessing Paper's dispatcher.
     * @param owner the ownership token
     * @param ownerName the diagnostic name
     * @param executor the live executor
     * @param description the resolved description
     * @param permission the root permission
     * @param requireOp the root operator requirement
     * @param aliases the root label followed by its aliases
     * @return whether ownership accepted the command
     */
    public boolean register(Object owner, String ownerName, BaseCommandExecutor executor, String description,
            String permission, boolean requireOp, List<String> aliases) {
        installer.ensureInstalled();
        List<String> labels = aliases;
        if (labels.isEmpty()) {
            CmdExecutor annotation = MergedAnnotationResolver.find(executor.getClass(), CmdExecutor.class);
            if (annotation == null || annotation.alias().length == 0) {
                throw new CommandCompilationException(executor.getClass().getName() + ": missing command labels");
            }
            labels = Arrays.asList(annotation.alias());
        }
        var invocation = new CommandInvocation(executor, labels.getFirst().toLowerCase(Locale.ROOT));
        var command = compiler.compile(executor, description, labels, permission, requireOp, invocation::run, invocation::help);
        var replies = new FrameworkReplies(executor, command.tree(),
                (input, source) -> bindings.dispatch(command.label(), input, source));
        var decorated = new CompiledCommand(command.label(), command.aliases(), command.description(), replies.tree(), command.signature());
        return bindings.bind(owner, ownerName, executor, decorated, replies);
    }

    /** Installs the framework listener once during enable, independently of command registration. */
    public synchronized void registerInternalListeners() {
        if (listenerInstalled) return;
        Bukkit.getPluginManager().registerEvents(new HiddenCommandListener(bindings), UltiTools.getInstance());
        listenerInstalled = true;
    }

    /**
     * Registers a module-provided Brigadier tree with the same ownership lifecycle.
     * @param owner the ownership token
     * @param ownerName the diagnostic name
     * @param command the hand-written command bean
     * @return whether ownership accepted the command
     */
    public boolean register(Object owner, String ownerName, BrigadierCommand command) {
        installer.ensureInstalled();
        return bindings.bind(owner, ownerName, command, HandWrittenCommands.compile(command));
    }

    /**
     * Releases only the specified owner's commands.
     * @param owner the ownership token
     */
    public void unregister(Object owner) { bindings.unbind(owner); }

    /** Releases module, external and framework bindings together. */
    public void close() { bindings.clear(); }

    /**
     * Resolves ownership through a primary label, alias or namespaced spelling.
     * @param label the command spelling
     * @return the owner, or null when no binding exists
     */
    public Object ownerByLabel(String label) { return bindings.ownerByLabel(label); }
}
