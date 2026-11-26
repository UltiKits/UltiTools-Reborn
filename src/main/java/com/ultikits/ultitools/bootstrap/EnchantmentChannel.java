package com.ultikits.ultitools.bootstrap;

import org.jetbrains.annotations.ApiStatus;

import net.kyori.adventure.key.Key;

/**
 * Declarations of new enchantments made by one module.
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
public interface EnchantmentChannel {
    /**
     * Declares a new enchantment. The key's namespace must equal this module's namespace.
     * The definition runs later, once, while Paper composes the enchantment registry.
     *
     * @param key the new enchantment key
     * @param definition the builder callback
     * @throws IllegalArgumentException if the key is outside this module's namespace or declared twice
     * @throws IllegalStateException if called after bootstrap() returned
     */
    void register(Key key, EnchantmentDefinition definition);
}
