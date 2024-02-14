package com.ultikits.ultitools.interfaces.impl.pasers;

import cn.hutool.core.util.ObjectUtil;
import com.ultikits.ultitools.interfaces.ObjectConfigSerializer;
import com.ultikits.ultitools.interfaces.Parser;

import java.util.List;

public abstract class ConfigParser<T> implements Parser<T>, ObjectConfigSerializer<T> {

    @Override
    public final Object serialize(T object) {
        if (ObjectUtil.isBasicType(object) || object instanceof String || object instanceof List) {
            return object;
        } else {
            return serializeToMemorySection(object);
        }
    }
}
