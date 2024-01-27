package com.ultikits.ultitools.interfaces.impl.pasers;

import cn.hutool.core.util.ObjectUtil;
import com.alibaba.fastjson.JSONObject;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class BaseConfigParser extends BaseParser<Object> {

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
}
