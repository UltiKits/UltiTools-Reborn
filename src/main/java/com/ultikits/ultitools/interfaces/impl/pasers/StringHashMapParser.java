package com.ultikits.ultitools.interfaces.impl.pasers;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.MemorySection;

import java.util.HashMap;

/**
 * Legacy configuration parser; executable behavior remains unchanged.
 * @deprecated Use {@link com.ultikits.ultitools.config.convert.ConfigConverter} and
 * {@link com.ultikits.ultitools.config.convert.ConfigConverterFor}; removed in the next version.
 * @removeIn 6.4.0
 */
// Frozen legacy superclass retained for compatibility until removal.
@SuppressWarnings("removal")
@Deprecated(since = "6.3.0", forRemoval = true)
public class StringHashMapParser extends ConfigParser<HashMap<String, String>> {
    @Override
    public HashMap<String, String> parse(Object object) {
        if (!(object instanceof ConfigurationSection)) {
            return null;
        }
        ConfigurationSection section = (ConfigurationSection) object;
        HashMap<String, String> map = new HashMap<>();
        for (String key : section.getKeys(false)) {
            map.put(key, section.getString(key));
        }
        return map;
    }

    @Override
    public MemorySection serializeToMemorySection(HashMap<String, String> object) {
        MemorySection memorySection = new MemoryConfiguration();
        for (String key : object.keySet()) {
            memorySection.set(key, object.get(key));
        }
        return memorySection;
    }
}
