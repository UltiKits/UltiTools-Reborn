package com.ultikits.testfixtures.overrideslots.pkgx;

/**
 * Same package as {@link PackagePrivateSlotBase}, widening its package-private {@code slot()} to
 * {@code public}. This <b>is</b> an override per JLS 8.4.8.1 - the manifestation-1 shape of issue
 * #190's override-detection asymmetry, where a symmetric name-based key gives the two declarations
 * different keys and lets both survive de-duplication.
 * <p>
 * Also the intermediate link of the three-level transitive case: see
 * {@code com.ultikits.testfixtures.overrideslots.pkgy.TransitiveWideningLeaf}.
 * <p>
 * 与 {@link PackagePrivateSlotBase} 同包，把它的包私有 {@code slot()} 放宽为 {@code public}。
 * 根据 JLS 8.4.8.1 这<b>构成</b>覆盖；同时它也是三层传递性用例的中间环节。
 */
public class SamePackageWideningMiddle extends PackagePrivateSlotBase {

    @Override
    public void slot() {
    }
}
