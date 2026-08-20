package com.ultikits.testfixtures.missingdependency;

/**
 * References {@link MissingDependencyType} in its method signatures. Resolving
 * {@link Class#getDeclaredMethods()} on this class requires its defining class loader to resolve
 * {@code MissingDependencyType} - when that loader refuses to, the JVM throws
 * {@link NoClassDefFoundError} at that point rather than at class-load time, because method
 * descriptor resolution is lazy.
 * <br>
 * 方法签名中引用了 {@link MissingDependencyType}。对本类调用
 * {@link Class#getDeclaredMethods()} 需要其定义类加载器解析 {@code MissingDependencyType}——
 * 一旦该加载器拒绝解析，JVM 会在此刻（而非类加载时，因为方法描述符是惰性解析的）抛出
 * {@link NoClassDefFoundError}。
 */
public class HasMethodReferencingMissingType {

    public MissingDependencyType returnsMissingType() {
        return null;
    }

    public void acceptsMissingType(MissingDependencyType type) {
        // no-op, signature is what matters
    }
}
