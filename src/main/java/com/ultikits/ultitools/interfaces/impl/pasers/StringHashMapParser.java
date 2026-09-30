package com.ultikits.ultitools.interfaces.impl.pasers;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.MemorySection;

import java.util.HashMap;

public class StringHashMapParser extends ConfigParser<HashMap<String, String>> implements DottedMapKeyRefusal {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(StringHashMapParser.class.getName());

    /** Where a refused dotted map key is reported (#553); {@code null} means this class's logger. */
    private java.util.function.Consumer<String> refusedKeys;

    /** {@inheritDoc} */
    @Override
    public void reportRefusedKeysTo(java.util.function.Consumer<String> sink) {
        this.refusedKeys = sink;
    }

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
            // #553: a dotted key is refused here, the one point where this map becomes a section.
            if (DottedMapKeyRefusal.storable(key, refusedKeys, LOGGER)) {
                memorySection.set(key, object.get(key));
            }
        }
        return memorySection;
    }
}
