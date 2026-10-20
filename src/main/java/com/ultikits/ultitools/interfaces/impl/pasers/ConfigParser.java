package com.ultikits.ultitools.interfaces.impl.pasers;

import java.util.List;

import com.ultikits.ultitools.interfaces.ObjectConfigSerializer;
import com.ultikits.ultitools.interfaces.Parser;
import com.ultikits.ultitools.utils.BasicTypeUtil;

/**
 * Legacy configuration parser; executable behavior remains unchanged.
 * @deprecated Use {@link com.ultikits.ultitools.config.convert.ConfigConverter} and
 * {@link com.ultikits.ultitools.config.convert.ConfigConverterFor}; removed in the next version.
 * @removeIn 6.4.0
 */
// Retains the published legacy interfaces until their announced removal version.
@SuppressWarnings("removal")
@Deprecated(since = "6.3.0", forRemoval = true)
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
