/**
 * Fixtures for {@code com.ultikits.ultitools.context.FinalContractValidatorTest} - a
 * <b>unit</b> test that calls {@code FinalContractValidator.validate(Class)} directly on one
 * fixture class at a time. It never scans this package as a whole, so unlike its sibling
 * {@link com.ultikits.testfixtures.finalviolation.scanner}, it is safe for this package to hold
 * more than one violation shape: {@link IllegalSubclass} exercises the "extends a sealed class"
 * check and {@link IllegalOverride} exercises the "overrides a sealed method" check, and nothing
 * here depends on which one a directory walk would see first.
 * <p>
 * 本包服务于 {@code FinalContractValidatorTest}——一个**单元测试**，逐个类直接调用
 * {@code validate(Class)}，从不整包扫描。因此与它的同级包
 * {@link com.ultikits.testfixtures.finalviolation.scanner} 不同，本包可以同时容纳多种违规形态：
 * {@link IllegalSubclass} 覆盖"继承密封类"分支，{@link IllegalOverride} 覆盖"重写密封方法"分支，
 * 二者共存不受目录遍历顺序影响。
 * <p>
 * <b>Rule: no test may scan this package directly.</b> It intentionally holds more than one
 * violation shape - safe for {@code FinalContractValidatorTest}, which validates one named class
 * at a time, but scanning the package as a whole (e.g. {@code ComponentScanner#scanPackage}) would
 * reproduce the same {@code File.listFiles()} order-dependent nondeterminism this fixture family
 * was split up to avoid in the first place (see the parent {@code finalviolation} package-info). A
 * future scan-triggered violation belongs in its own subpackage, not here.
 * <p>
 * <b>规则：任何测试都不得整包扫描本包。</b>本包故意持有多种违规形态——这对
 * {@code FinalContractValidatorTest}（逐个具名类调用 validate）是安全的，但若整包扫描本包
 * （例如 {@code ComponentScanner#scanPackage}），就会重现本 fixture 家族当初被拆分正是为了
 * 避免的那种 {@code File.listFiles()} 顺序依赖的不确定性（见父包 {@code finalviolation} 的
 * package-info）。未来若需新增"会被扫描触发"的违规，请开独立子包，不要放进本包。
 */
package com.ultikits.testfixtures.finalviolation.validator;
