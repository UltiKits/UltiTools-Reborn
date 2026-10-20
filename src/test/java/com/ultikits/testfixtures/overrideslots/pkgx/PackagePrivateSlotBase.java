package com.ultikits.testfixtures.overrideslots.pkgx;

/**
 * Root of the override-slot fixture hierarchy: declares a <b>package-private</b> {@code slot()}.
 * <p>
 * Per JLS 8.4.8.1 a package-private method is overridden only by a subclass in the <b>same</b>
 * package, and there is deliberately no condition on the overriding method's own access - Java lets
 * an override widen access. {@link SamePackageWideningMiddle} is that same-package widening
 * override; {@code com.ultikits.testfixtures.overrideslots.pkgy.CrossPackageWideningChild} is the
 * cross-package case that is <em>not</em> an override at all.
 * <p>
 * 本包私有 {@code slot()} 是覆盖槽 fixture 的根。根据 JLS 8.4.8.1，包私有方法只会被<b>同包</b>的
 * 子类方法覆盖；而对覆盖方自身的访问级别没有任何限制——Java 允许覆盖时放宽访问权限。
 */
public class PackagePrivateSlotBase {

    void slot() {
    }
}
