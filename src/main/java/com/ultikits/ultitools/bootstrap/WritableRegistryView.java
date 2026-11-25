package com.ultikits.ultitools.bootstrap;

import java.util.function.Consumer;

import io.papermc.paper.registry.data.EnchantmentRegistryEntry;
import net.kyori.adventure.key.Key;

@FunctionalInterface
interface WritableRegistryView {
    void register(Key key, Consumer<EnchantmentRegistryEntry.Builder> definition);
}
