package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.interfaces.DataOperator;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class DataProxyManager implements InvocationHandler {
    private final Class<?> entityType;
    private final UltiToolsPlugin plugin;

    public DataProxyManager(Class<?> entityType, UltiToolsPlugin plugin) {
        this.entityType = entityType;
        this.plugin = plugin;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        DataOperator<? extends AbstractDataEntity> dataOperator = plugin.getDataOperator(
                (Class<? extends AbstractDataEntity>) entityType
        );
        return method.invoke(dataOperator, args);
    }

    public DataOperator<? extends AbstractDataEntity> createProxy() {
        return (DataOperator<? extends AbstractDataEntity>) Proxy.newProxyInstance(
                DataOperator.class.getClassLoader(),
                new Class<?>[]{DataOperator.class},
                this
        );
    }
}
