package com.ultikits.testfixtures.crosspackagededup.pkga;

import com.ultikits.ultitools.annotations.PostConstruct;

/**
 * Test-only fixture for {@code SimpleContainerLifecycleDedupTest}: declares a package-private
 * {@code @PostConstruct} method. Paired with
 * {@code com.ultikits.testfixtures.crosspackagededup.pkgb.PkgPrivateInitChild}, which extends this
 * class from a <b>different</b> package and declares its own, same-signature package-private
 * {@code init()}.
 * <p>
 * Per JLS 8.4.8.1, a package-private method is overridden only by a subclass in the <b>same</b>
 * package; a same-signature package-private method in a subclass in a different package is a
 * distinct method, not an override. {@code ReflectionUtil.getAllMethods}'s de-dup must therefore
 * keep both, and {@code SimpleContainer} must invoke both {@code @PostConstruct} methods rather
 * than collapsing one into the other. See issue #190.
 * <br>
 * 根据 JLS 8.4.8.1，包私有方法只会被<b>同一个包</b>内的子类方法覆盖；不同包的子类中出现的
 * 同名同参数包私有方法是一个独立的方法，而非覆盖。
 */
public class PkgPrivateInitBase {
    public static int initCount;

    @PostConstruct
    void init() {
        initCount++;
    }
}
