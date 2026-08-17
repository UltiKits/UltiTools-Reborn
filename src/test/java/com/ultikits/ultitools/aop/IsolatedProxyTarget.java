package com.ultikits.ultitools.aop;

/**
 * Target class for the cross-class-loader proxy test.
 * <p>
 * This class must not reference any framework type: it is loaded by an isolated
 * class loader whose parent is the platform loader, which cannot see them.
 */
public class IsolatedProxyTarget {

    public String getValue() {
        return "original";
    }

    public int calculate(int a, int b) {
        return a + b;
    }

    String packagePrivateMethod() {
        return "pkg-original";
    }
}
