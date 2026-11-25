package com.ultikits.ultitools.bootstrap;

import org.jetbrains.annotations.ApiStatus;

import io.papermc.paper.registry.data.EnchantmentRegistryEntry;

/**
 * The definition of a custom enchantment.
 *
 * Experimental: built on Paper APIs that Paper marks experimental ({@code PluginBootstrap},
 * {@code LifecycleEvents.TAGS}, {@code LifecycleEvents.DATAPACK_DISCOVERY}). A later 7.x release may
 * change this interface when Paper changes them; such changes are listed in COMPATIBILITY.md.
 *
 * @since 7.0.0
 */
@ApiStatus.Experimental
@FunctionalInterface
public interface EnchantmentDefinition {
    /**
     * Fills Paper's builder. Throwing marks the module as failed in its bootstrap phase.
     *
     * @param builder Paper's builder for this entry
     * @param tags resolves registry tags such as {@code #minecraft:enchantable/sword} for supported items
     */
    void define(EnchantmentRegistryEntry.Builder builder, ComposeTags tags);
}
