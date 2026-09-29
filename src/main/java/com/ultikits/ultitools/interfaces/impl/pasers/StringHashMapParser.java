package com.ultikits.ultitools.interfaces.impl.pasers;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.MemorySection;

import java.util.HashMap;

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

    /**
     * Writes each entry with its key kept whole (#553): a key such as {@code o.O} stays one key
     * instead of becoming {@code o} -> {@code O}. A key the configuration loader cannot read back (an
     * empty path segment) is refused, as {@code DefaultConfigParser} refuses it.
     *
     * @param object the map to write
     * @return the section holding its entries
     */
    @Override
    public MemorySection serializeToMemorySection(HashMap<String, String> object) {
        MemoryConfiguration memorySection = new MemoryConfiguration();
        memorySection.options().pathSeparator('\u0000');
        for (String key : object.keySet()) {
            memorySection.set(DefaultConfigParser.checkWritableKey(key), object.get(key));
        }
        return memorySection;
    }
}
