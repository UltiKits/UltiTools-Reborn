package com.ultikits.ultitools.command.engine;

import java.util.List;
import org.jetbrains.annotations.Nullable;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;

/**
 * A tree together with its immutable registration metadata.
 * @param label the primary root name
 * @param aliases the additional labels
 * @param description the help description, or null
 * @param tree the real command tree
 * @param signature the canonical structure and visibility signature
 */
public record CompiledCommand(String label, List<String> aliases, @Nullable String description,
        LiteralCommandNode<CommandSourceStack> tree, String signature) {
    /** Copies the alias collection so callers cannot change a registration. */
    public CompiledCommand { aliases = List.copyOf(aliases); }
}
