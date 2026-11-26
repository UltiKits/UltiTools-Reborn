package com.ultikits.ultitools.bootstrap;

import org.jetbrains.annotations.ApiStatus;

/**
 * Declarations of data packs bundled in a module JAR.
 *
 * Experimental: these channels use Paper's bootstrap lifecycle and {@code RegistryEvents.ENCHANTMENT}.
 * Paper 26.3 marks {@code PluginBootstrap}, {@code LifecycleEvents.TAGS},
 * {@code LifecycleEvents.DATAPACK_DISCOVERY} and {@code io.papermc.paper.registry.tag.Tag} experimental.
 * A later 7.x release may adapt this interface to Paper changes; see COMPATIBILITY.md.
 *
 * @since 7.0.0
 */
@ApiStatus.Experimental
@ApiStatus.NonExtendable
public interface DatapackChannel {
    /**
     * Declares the bundled data pack at {@code datapacks/<name>/} in this module's JAR.
     * Data packs that add world-generation data are refused: the module will not load, even if
     * it catches the exception from this method.
     *
     * @param name lower-case {@code [a-z0-9_-]{1,32}}; the server lists it as {@code UltiTools/<namespace>.<name>}
     * @throws IllegalArgumentException if the name is invalid, declared twice, or the directory or its pack.mcmeta is missing or invalid, or the pack adds world-generation data
     * @throws IllegalStateException if called after bootstrap() returned
     */
    void embedded(String name);
}
