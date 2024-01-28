package com.ultikits.ultitools.interfaces.impl.pasers;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReflectUtil;
import com.alibaba.fastjson.JSONObject;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.MemorySection;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
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
        } else if (ObjectUtil.isBasicType(object) || object instanceof String) {
            return object;
        } else {
            JSONObject jsonObject = new JSONObject();
            ConfigurationSection section = (ConfigurationSection) object;
            Set<String> keys = section.getKeys(false);
            for (String key : keys) {
                Object value = section.get(key);
                if (value instanceof ConfigurationSection) {
                    value = parse(value);
                }
                jsonObject.put(key, value);
            }
            return jsonObject;
        }
    }

    @Override
    public MemorySection serializeToMemorySection(Object object) {
        MemorySection memorySection = new MemoryConfiguration();
        for (Field field : ReflectUtil.getFields(object.getClass())) {
            field.setAccessible(true);
            Object fieldValue = ReflectUtil.getFieldValue(object, field);
            memorySection.set(field.getName(), serialize(fieldValue));
        }
        return memorySection;
    }
}
