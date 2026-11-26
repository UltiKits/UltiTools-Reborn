package com.ultikits.ultitools.bootstrap;

import org.jetbrains.annotations.ApiStatus;

import org.bukkit.Keyed;

import io.papermc.paper.registry.tag.Tag;
import io.papermc.paper.registry.tag.TagKey;

/**
 * Access to tags while Paper composes the enchantment registry.
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
public interface ComposeTags {
    /**
     * Wraps {@code RegistryComposeEvent#getOrCreateTag}; valid only during the definition call.
     *
     * @param <V> the tag's value type
     * @param key the tag to resolve
     * @return the tag supplied by Paper
     */
    <V extends Keyed> Tag<V> tag(TagKey<V> key);
}
