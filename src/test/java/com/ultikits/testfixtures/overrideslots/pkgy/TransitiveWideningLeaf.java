package com.ultikits.testfixtures.overrideslots.pkgy;

import com.ultikits.testfixtures.overrideslots.pkgx.SamePackageWideningMiddle;

/**
 * Leaf of the three-level transitive case: a <b>different</b> package than
 * {@code pkgx.PackagePrivateSlotBase}, so it does not <em>directly</em> override that class's
 * package-private {@code slot()}. It does override {@link SamePackageWideningMiddle#slot()}, which
 * in turn overrides the root's - and overriding is transitive per JLS 8.4.8.1, so all three
 * declarations are one slot.
 * <p>
 * This is the case a de-duplication that only compares each candidate against the surviving
 * <em>representative</em> gets wrong: the root is not directly overridden by this leaf, so it
 * survives as a second entry unless every declaration folded into the slot is kept and tested.
 * <p>
 * 三层传递性用例的叶子类。它与根类 {@code pkgx.PackagePrivateSlotBase} 不同包，因此不<em>直接</em>
 * 覆盖后者的包私有 {@code slot()}；但它覆盖了 {@link SamePackageWideningMiddle#slot()}，后者又覆盖
 * 了根类的——JLS 8.4.8.1 下覆盖具有传递性，三条声明属于同一个槽。若去重时只拿候选者与槽的
 * <em>代表</em>比较，根类那条就会漏成第二个条目。
 */
public class TransitiveWideningLeaf extends SamePackageWideningMiddle {

    @Override
    public void slot() {
    }
}
