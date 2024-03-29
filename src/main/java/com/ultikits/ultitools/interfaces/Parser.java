package com.ultikits.ultitools.interfaces;

public interface Parser<T> {
    T parse(Object object);

    Object serialize(T object);
}
