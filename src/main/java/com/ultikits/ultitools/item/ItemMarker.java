package com.ultikits.ultitools.item;

import java.util.Objects;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

/**
 * A typed persistent-data value written with {@code ItemStack#editPersistentDataContainer}.
 * @param <P> primitive storage type
 * @param <C> complex value type
 * @param key persistent-data key
 * @param type value codec
 * @param value value to store
 */
public record ItemMarker<P, C>(NamespacedKey key, PersistentDataType<P, C> type, C value) {
    /** Rejects null key, type or value. */
    public ItemMarker {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
    }
}
