package com.ultikits.ultitools.interfaces.impl.pasers;

import com.ultikits.ultitools.utils.BasicTypeUtil;
import com.ultikits.ultitools.utils.ReflectionUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.MemorySection;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DefaultConfigParser extends ConfigParser<Object> {

    @Override
    public Object parse(Object object) {
        if (object instanceof List) {
            List<String> list = new ArrayList<>();
            for (Object o : (List<?>) object) {
                list.add(o.toString());
            }
            return list;
        } else if (BasicTypeUtil.isBasicType(object) || object instanceof String) {
            return object;
        } else {
            Map<String, Object> map = new LinkedHashMap<>();
            ConfigurationSection section = (ConfigurationSection) object;
            Set<String> keys = section.getKeys(false);
            for (String key : keys) {
                Object value = section.get(key);
                if (value instanceof ConfigurationSection) {
                    value = parse(value);
                }
                map.put(key, value);
            }
            return map;
        }
    }

    @Override
    public MemorySection serializeToMemorySection(Object object) {
        MemorySection memorySection = new MemoryConfiguration();
        for (Field field : ReflectionUtil.getFields(object.getClass())) {
            field.setAccessible(true);
            Object fieldValue = ReflectionUtil.getFieldValue(object, field);
            memorySection.set(field.getName(), serialize(fieldValue));
        }
        return memorySection;
    }
}
