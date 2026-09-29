package com.ultikits.ultitools.interfaces.impl.pasers;

import java.util.List;

import com.ultikits.ultitools.interfaces.ObjectConfigSerializer;
import com.ultikits.ultitools.interfaces.Parser;
import com.ultikits.ultitools.utils.BasicTypeUtil;

public abstract class ConfigParser<T> implements Parser<T>, ObjectConfigSerializer<T> {

    @Override
    public final Object serialize(T object) {
        if (BasicTypeUtil.isBasicType(object) || object instanceof String || object instanceof List) {
            return object;
        } else {
            return serializeToMemorySection(object);
        }
    }
}
