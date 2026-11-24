package com.ultikits.ultitools.command;

import java.util.Collection;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;

/**
 * A command whose Brigadier tree the module builds by hand. The framework registers it, owns it
 * with the module and hides it when the module unloads; validators, cooldowns and
 * {@code @CmdParam} suggestions do not apply.
 * <p>
 * Register an implementation as a container bean, for example with the framework's
 * {@code @Service} annotation. Aliases of the same bean are registered once. A redirect into
 * this tree targets its owned proxy; a redirect outside this tree retains its original target,
 * which is not governed by this module's unload. Use lower-case root labels.
 *
 * @since 7.0.0
 */
public interface BrigadierCommand {
    /**
     * Supplies the root node, whose literal is the command label. Called once per registration.
     * @return the complete root tree
     */
    LiteralCommandNode<CommandSourceStack> node();

    /**
     * Supplies additional labels.
     * @return the aliases, empty by default
     */
    default Collection<String> aliases() { return List.of(); }

    /**
     * Supplies the text shown in help.
     * @return the description, or null for none
     */
    default @Nullable String description() { return null; }
}
