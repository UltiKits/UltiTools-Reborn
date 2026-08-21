package com.ultikits.testfixtures.finalviolation.validator;

/**
 * Test-only fixture for {@code FinalContractValidatorTest}: illegally overrides
 * {@link PackagePrivateSealedBase#sealedPackageMethod()}, which is annotated {@code @Final}, by
 * widening it from package-private to {@code public} from within the <b>same</b> package.
 * <p>
 * Per JLS 8.4.8.1 this is a real override - a package-private method is overridden by a
 * same-signature method in a same-package subclass, and there is no condition on the overriding
 * method's own access, so widening is permitted and changes nothing about the override relation.
 * It is therefore exactly what {@code @Final} forbids. This is the contract hole of issue #190:
 * matching the two declarations by a symmetric name-based key gives them different keys (one
 * carries the package, the other does not) and the violation walks straight through the validator.
 * <p>
 * Contrast {@code FinalContractValidatorTest.CrossPackageShadowsSealedMethod}, which shares the
 * same parent but sits in a different package and therefore legitimately does not override it.
 * <p>
 * See this package's {@code package-info} for why it is safe to hold more than one violation shape,
 * and the parent {@code finalviolation} package's {@code package-info} for why any of this lives
 * outside {@code com.ultikits.ultitools} at all.
 * <p>
 * 本 fixture 在<b>同一个包</b>内把 {@link PackagePrivateSealedBase#sealedPackageMethod()}
 * （标注了 {@code @Final} 的包私有方法）放宽为 {@code public}。根据 JLS 8.4.8.1，这构成真实的覆盖
 * ——同包子类的同签名方法会覆盖包私有方法，且对覆盖方自身的访问级别没有限制——因此正是
 * {@code @Final} 所禁止的行为。这就是 issue #190 的契约漏洞所在。
 */
public class WideningOverrideOfSealedPackageMethod extends PackagePrivateSealedBase {

    @Override
    public void sealedPackageMethod() {
    }
}
