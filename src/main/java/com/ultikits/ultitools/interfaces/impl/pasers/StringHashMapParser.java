package com.ultikits.ultitools.interfaces.impl.pasers;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.MemorySection;
import org.jetbrains.annotations.ApiStatus;

import java.util.HashMap;

public class StringHashMapParser extends ConfigParser<HashMap<String, String>> implements DottedMapKeyRefusal {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(StringHashMapParser.class.getName());

    /**
     * The form in which the framework writes this entry's map, refusing a dotted key (#553) and
     * reporting it to {@code refused}; see {@link DottedMapKeyRefusal}.
     * <p>
     * Framework-internal: {@code public} only because the configuration entity lives in another
     * package. Module code should not call it.
     *
     * @param value   the map, possibly {@code null}
     * @param path    the entry's path
     * @param refused receives (entry path, refused key), or {@code null} for this class's logger
     * @return the value to put into the configuration
     * @since 6.3.0
     */
    @Override
    @ApiStatus.Internal
    @SuppressWarnings("unchecked")
    public Object fileForm(Object value, String path, java.util.function.BiConsumer<String, String> refused) {
        if (!(value instanceof HashMap)) {
            return value;
        }
        return DottedMapKeyRefusal.Context.with(path, refused, () -> serialize((HashMap<String, String>) value));
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
        DottedMapKeyRefusal.Context context = DottedMapKeyRefusal.Context.current();
        for (String key : object.keySet()) {
            // #553: a dotted key is refused here, the one point where this map becomes a section.
            if (DottedMapKeyRefusal.storable(key, context.path, context.refused, LOGGER)) {
                memorySection.set(key, object.get(key));
            }
        }
        return memorySection;
    }
}
