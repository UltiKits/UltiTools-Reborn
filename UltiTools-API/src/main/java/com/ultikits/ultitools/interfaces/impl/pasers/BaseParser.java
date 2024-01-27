package com.ultikits.ultitools.interfaces.impl.pasers;

import com.ultikits.ultitools.interfaces.Parser;

public abstract class BaseParser<T> implements Parser<T> {

    abstract public T parse(Object object);
}
