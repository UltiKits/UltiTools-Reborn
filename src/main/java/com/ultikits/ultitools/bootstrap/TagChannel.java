package com.ultikits.ultitools.bootstrap;

import java.util.Collection;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;

import org.bukkit.Keyed;

import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;

/**
 * Item and enchantment tag changes declared by one module.
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
public interface TagChannel {
    /** The registries available to this channel. */
    Set<RegistryKey<?>> SUPPORTED = Set.of(RegistryKey.ITEM, RegistryKey.ENCHANTMENT);

    /**
     * Adds values to an existing or new tag of the given registry; any namespace may be extended.
     *
     * @param <T> the registry value type
     * @param registry a supported registry
     * @param tag the tag key
     * @param values the entry keys to add
     * @throws IllegalArgumentException if the registry is not supported
     * @throws IllegalStateException if called after bootstrap() returned
     */
    <T extends Keyed> void addToTag(RegistryKey<T> registry, Key tag, Collection<Key> values);

    /**
     * Creates or replaces a tag in this module's namespace with exactly these values.
     *
     * @param <T> the registry value type
     * @param registry a supported registry
     * @param tag the tag key
     * @param values the replacement entry keys
     * @throws IllegalArgumentException if the registry is not supported or the tag is outside this module's namespace
     * @throws IllegalStateException if called after bootstrap() returned
     */
    <T extends Keyed> void setTag(RegistryKey<T> registry, Key tag, Collection<Key> values);
}
