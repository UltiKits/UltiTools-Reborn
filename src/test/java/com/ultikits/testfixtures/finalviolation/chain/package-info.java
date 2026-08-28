/**
 * Fixtures for {@code com.ultikits.ultitools.context.FinalContractValidatorTest}'s cross-package
 * transitive-override scenario: a {@code @Final} package-private method on {@link p1.ChainRoot},
 * widened to {@code public} by a same-package {@link p1.ChainMiddle}, then overridden again by
 * {@link p2.ChainLeaf} from a <b>different</b> package.
 * <p>
 * {@code ChainRoot} and {@code ChainMiddle} must share a package: per JLS 8.4.8.1, a package-private
 * method is only overridden by a same-package subclass, so the widening step that makes the whole
 * chain reachable from {@code ChainLeaf} only exists because {@code ChainMiddle} sits next to
 * {@code ChainRoot}. {@code ChainLeaf} sits in a second, different package on purpose: comparing
 * it directly against {@code ChainRoot} fails the package check (that is issue #190's original
 * bug), and only succeeds by first passing through {@code ChainMiddle}.
 * <p>
 * Like the sibling {@code validator} package, these fixtures are only ever validated one class at
 * a time via {@code FinalContractValidator.validate(Class)}; no test scans this package as a
 * whole. See the parent {@code finalviolation} package-info for why this whole family lives
 * outside {@code com.ultikits.ultitools}.
 * <p>
 * 本包服务于跨包传递重写场景：{@link p1.ChainRoot} 声明包私有的 {@code @Final} 方法，被同包的
 * {@link p1.ChainMiddle} 放宽为 {@code public}，再被<b>不同包</b>的 {@link p2.ChainLeaf} 二次重写。
 * {@code ChainRoot} 与 {@code ChainMiddle} 必须同包——依据 JLS 8.4.8.1，包私有方法只能被同包子类
 * 覆盖，链条之所以能从 {@code ChainLeaf} 一路追溯回去，正是因为这一步放宽发生在同包内。
 * {@code ChainLeaf} 故意放在第二个不同的包：直接拿它与 {@code ChainRoot} 比较会因为包不同而失败
 * （这正是 issue #190 的原始缺陷），只有先经过 {@code ChainMiddle} 才能成立。
 */
package com.ultikits.testfixtures.finalviolation.chain;
