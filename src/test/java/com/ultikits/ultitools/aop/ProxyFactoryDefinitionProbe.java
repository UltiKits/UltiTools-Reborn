package com.ultikits.ultitools.aop;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;

public final class ProxyFactoryDefinitionProbe {

    private ProxyFactoryDefinitionProbe() {
    }

    @SuppressWarnings({"unchecked", "PMD.AvoidAccessibilityAlteration"})
    public static void main(String[] args) throws Exception {
        URL classes = ProxyFactoryDefinitionProbe.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes}, ClassLoader.getPlatformClassLoader())) {
            Class<Object> target = (Class<Object>) loader.loadClass(IsolatedProxyTarget.class.getName());
            Method method = target.getDeclaredMethod("packagePrivateMethod");
            method.setAccessible(true);
            ProxyFactory factory = new ProxyFactory(Collections.singletonList(
                    invocation -> "intercepted:" + invocation.proceed()));
            Class<?> proxyClass = factory.createProxyClass(target, Collections.singleton(method));
            Object proxy = proxyClass.getDeclaredConstructor().newInstance();
            Object result = method.invoke(proxy);
            if (proxyClass.getClassLoader() != loader || proxyClass.getPackage() != target.getPackage()
                    || !"intercepted:pkg-original".equals(result)) {
                throw new AssertionError("Proxy definition lost its runtime package or interception: " + result);
            }
            System.out.println("proxy-loader=target-loader; package-private=" + result);
        }
    }
}
