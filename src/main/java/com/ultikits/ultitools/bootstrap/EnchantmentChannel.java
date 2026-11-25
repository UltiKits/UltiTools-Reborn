package com.ultikits.ultitools.bootstrap;

import org.jetbrains.annotations.ApiStatus;

import net.kyori.adventure.key.Key;

/**
 * Declarations of new enchantments made by one module.
 *
 * Experimental: built on Paper APIs that Paper marks experimental ({@code PluginBootstrap},
 * {@code LifecycleEvents.TAGS}, {@code LifecycleEvents.DATAPACK_DISCOVERY}). A later 7.x release may
 * change this interface when Paper changes them; such changes are listed in COMPATIBILITY.md.
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
