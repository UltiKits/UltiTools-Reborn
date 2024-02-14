package com.ultikits.ultitools.interfaces;

import org.bukkit.configuration.MemorySection;

public interface ObjectConfigSerializer<T> {
    MemorySection serializeToMemorySection(T object);
}
