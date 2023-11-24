package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.interfaces.Registrable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;

public class ServiceProxyManager implements InvocationHandler {
    private final Class<?> interfaceType;

    public ServiceProxyManager(Class<? extends Registrable> interfaceType) {
        this.interfaceType = interfaceType;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Optional<? extends Registrable> registrable =
                UltiTools.getInstance().getPluginManager().getService(
                        (Class<? extends Registrable>) interfaceType
                );
        if (!registrable.isPresent()) {
            throw new RuntimeException("No service found for " + interfaceType.getName());
        }
        Object realObject = registrable.get();
        return method.invoke(realObject, args);
    }

    public Registrable createProxy() {
        return (Registrable) Proxy.newProxyInstance(
                interfaceType.getClassLoader(),
                new Class<?>[]{interfaceType},
                this
        );
    }
}
