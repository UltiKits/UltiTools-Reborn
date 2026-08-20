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
 */
package com.ultikits.testfixtures.finalviolation.validator;
