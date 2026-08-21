package com.ultikits.testfixtures.overrideslots.pkgy;

import com.ultikits.testfixtures.overrideslots.pkgx.PackagePrivateSlotBase;

/**
 * Extends {@link PackagePrivateSlotBase} from a <b>different</b> package, with no same-package
 * intermediate, and declares its own {@code public slot()}. The parent's {@code slot()} is
 * package-private and therefore not even accessible here, so per JLS 8.4.8.1 this does not override
 * it: the two are distinct methods that merely share a name, and both must survive de-duplication.
 * <p>
 * Note the absence of {@code @Override} - it would not compile, which is the compiler agreeing with
 * this class's whole point.
 * <p>
 * 跨包继承 {@link PackagePrivateSlotBase}，中间没有同包过渡类，自己声明 {@code public slot()}。
 * 父类的 {@code slot()} 是包私有的，在这里根本不可见，根据 JLS 8.4.8.1 这不构成覆盖：两者是仅仅
 * 同名的两个独立方法，去重时必须都保留。此处不能写 {@code @Override}——写了无法编译，这正是编译器
 * 对本类立意的背书。
 */
public class CrossPackageWideningChild extends PackagePrivateSlotBase {

    public void slot() {
    }
}
