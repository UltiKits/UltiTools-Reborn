/**
 * Test-only fixtures that intentionally violate the {@code @Final} contract
 * ({@link com.ultikits.ultitools.annotations.Final}): extending a sealed class or overriding a
 * sealed method. See the subpackages for the actual fixture classes:
 * {@link com.ultikits.testfixtures.finalviolation.validator} for
 * {@code FinalContractValidatorTest}'s unit-test fixtures, and
 * {@link com.ultikits.testfixtures.finalviolation.scanner} for
 * {@code ComponentScannerFinalContractTest}'s integration-test fixture.
 * <p>
 * This package family exists <b>outside</b> {@code com.ultikits.ultitools} on purpose. Component
 * scanning ({@code com.ultikits.ultitools.context.ComponentScanner}) walks whatever package it is
 * told to scan, recursively, with no notion of "this is a test fixture, skip it" - it just sees
 * real classes on the classpath. A class in here that violates {@code @Final} is a real, compiled
 * {@code .class} file, so if it sat inside {@code com.ultikits.ultitools} (e.g. next to the test
 * that uses it, colocated the usual way), any *other* test that scans a package containing it -
 * {@code com.ultikits.ultitools.context}, or the whole {@code com.ultikits.ultitools} tree via
 * {@code ContextConfig}'s {@code @ComponentScan} - would trip the violation and fail, for a reason
 * that has nothing to do with what that test is actually checking.
 * <p>
 * <b>Why two subpackages instead of one flat package?</b> The first attempt put every violation
 * fixture directly here, flat. That broke a second time, differently:
 * {@code ComponentScannerFinalContractTest} scans a whole package and asserts the exception names
 * one specific violating class; with more than one violation present, which class
 * {@code ComponentScanner.processClass} reports depends on {@code File.listFiles()}'s
 * filesystem-dependent order, so the assertion became order-dependent instead of deterministic.
 * {@code FinalContractValidatorTest} never scans a package (it calls {@code validate(Class)} on
 * one class at a time), so its fixtures don't have that constraint - but keeping them out of the
 * package an integration test scans wholesale avoids the coupling entirely. Each subpackage's own
 * {@code package-info} explains its specific constraint.
 * <p>
 * <b>Rule: this package itself must never directly hold a fixture class, and no test may scan it
 * directly.</b> {@code ComponentScanner#scanDirectory} recurses into subdirectories:
 * <pre>{@code
 * if (file.isDirectory()) {
 *     scanDirectory(file, packageName + "." + file.getName(), classLoader);
 * }
 * }</pre>
 * so scanning {@code com.ultikits.testfixtures.finalviolation} itself would walk straight into
 * both {@code validator/} and {@code scanner/} and pick up every violation in both - silently
 * undoing the split this javadoc just explained, with the package name now looking isolated even
 * though it no longer is. Only ever scan (or import from) a named subpackage; keep this package
 * itself to nothing but this file.
 * <p>
 * <b>Adding a new intentionally-violating fixture?</b> Put it under
 * {@code com.ultikits.testfixtures} (this family or a sibling of it), never under
 * {@code com.ultikits.ultitools}. If it will be reached by a whole-package scan, give it its own
 * subpackage rather than adding it to an existing one. Fixtures that do <i>not</i> violate
 * {@code @Final} - a compliant subclass, an unrelated class - are fine to keep colocated with their
 * test as usual; only a live violation needs to be quarantined here.
 * <p>
 * 本包族故意违反 {@code @Final} 契约（继承密封类或重写密封方法），且刻意放在
 * {@code com.ultikits.ultitools} 包树之外——原因见下。具体 fixture 类在两个子包中：
 * {@link com.ultikits.testfixtures.finalviolation.validator} 服务于
 * {@code FinalContractValidatorTest}（单元测试），
 * {@link com.ultikits.testfixtures.finalviolation.scanner} 服务于
 * {@code ComponentScannerFinalContractTest}（集成测试）。
 * <p>
 * 组件扫描会递归扫描它被要求扫描的任意包，并不知道"这是测试 fixture、应该跳过"——它看到的就是
 * 类路径上真实的类。如果违规 fixture 放在 {@code com.ultikits.ultitools} 内部（哪怕只是按惯例
 * 挨着使用它的测试类），任何恰好扫描到该路径的其他测试都会被无关地带崩。
 * <p>
 * <b>为什么拆成两个子包而不是一个扁平包？</b>最初的尝试把所有违规 fixture 都直接放在本包、扁平
 * 排列，结果以另一种方式再次踩坑：{@code ComponentScannerFinalContractTest} 整包扫描并断言异常
 * 携带某个具体违规类名；一旦包内出现一个以上违规，{@code ComponentScanner.processClass} 究竟报告
 * 哪一个，取决于 {@code File.listFiles()} 依赖文件系统的遍历顺序，断言因此从确定变成了不确定。
 * {@code FinalContractValidatorTest} 从不整包扫描（它逐个类调用 {@code validate(Class)}），所以它
 * 的 fixture 本没有这层约束——但把它们移出集成测试整包扫描的那个包，能彻底避免这种耦合。各子包
 * 各自的 package-info 说明了自己的具体约束。
 * <p>
 * <b>规则：本包直属不得放任何类，任何测试也不得直接扫描本包本身。</b>
 * {@code ComponentScanner#scanDirectory} 会递归下探子目录，所以扫描
 * {@code com.ultikits.testfixtures.finalviolation} 本身会一路递归进
 * {@code validator/} 和 {@code scanner/} 两个子包，把两边的违规全部收进来——悄无声息地推翻本文档
 * 刚解释过的拆分，而包名看起来却仍像是隔离的。永远只扫描（或 import）某个具名子包，本包本身
 * 除这份文档外不得有其他内容。
 * <p>
 * 之后再新增故意违规的 fixture，请放进 {@code com.ultikits.testfixtures} 下（本包族或其同级），
 * 不要放进 {@code com.ultikits.ultitools}；如果它会被整包扫描触及，给它单独开一个子包，不要并入
 * 已有的。不违规的 fixture（合规子类、无关类）仍可照常与测试类放在一起。
 */
package com.ultikits.testfixtures.finalviolation;
