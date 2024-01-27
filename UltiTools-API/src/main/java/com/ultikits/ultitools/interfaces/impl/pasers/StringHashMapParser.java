package com.ultikits.ultitools.interfaces.impl.pasers;

import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;

public class StringHashMapParser extends BaseParser<HashMap<String, String>> {
    @Override
    public HashMap<String, String> parse(Object object) {
        if (!(object instanceof ConfigurationSection)){
            return null;
        }
        ConfigurationSection section = (ConfigurationSection) object;
        HashMap<String, String> map = new HashMap<>();
        for (String key : section.getKeys(false)) {
            map.put(key, section.getString(key));
        }
        return map;
    }
}
