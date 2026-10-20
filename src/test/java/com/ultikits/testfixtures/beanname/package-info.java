/**
 * Fixtures for {@code com.ultikits.ultitools.context.DeclaredAttributeEffectTest$BeanNameAndValue}
 * (03-09, Task 1) -- deliberately <b>not</b> under {@code com.ultikits.ultitools.context} even
 * though every test that uses these classes reaches them by reflection alone and never scans
 * this package.
 * <p>
 * {@link BeanNameFixtures#conflictingNameValue()} declares {@code @Bean(name = "a", value = "b")}
 * on purpose, so that method's registration hard-fails with {@code ContainerException} once
 * {@code ComponentScanner.processBeanMethod}/{@code registerConfiguration} let the malformed
 * declaration propagate (D-02/D-25). {@code ComponentScanner.scanDirectory} walks a package's
 * compiled {@code .class} files, not a specific test's usage of them -- so a class merely
 * <em>compiled into</em> {@code com.ultikits.ultitools.context} (nested or top-level) is
 * discoverable by <b>any</b> test that scans that package, regardless of whether the test that
 * declared the class ever calls {@code scanPackage} itself. Several existing tests do exactly
 * that (e.g. {@code ComponentScannerTest}'s {@code freshScanner.scanPackage(
 * "com.ultikits.ultitools.context")}) and {@code scanDirectory} recurses into subpackages, so a
 * fixture with a malformed {@code @Bean} living anywhere under {@code context/} would abort
 * those unrelated scans the moment the malformed-declaration hard-fail (this plan's own
 * deliverable) started propagating correctly -- measured directly during this plan's own
 * execution. This package isolates the malformed fixture the same way
 * {@link com.ultikits.testfixtures.finalviolation.scanner} isolates a {@code @Final} violation
 * fixture for the identical reason.
 * <br>
 * 03-09 Task 1 的 fixture——刻意不放在 {@code com.ultikits.ultitools.context} 下，尽管所有使用
 * 这些类的测试都只通过反射到达它们，从不扫描本包。{@link BeanNameFixtures#conflictingNameValue()}
 * 故意声明了 {@code @Bean(name = "a", value = "b")}，一旦
 * {@code ComponentScanner.processBeanMethod}/{@code registerConfiguration} 让这类畸形声明正确
 * 传播（D-02/D-25），该方法的注册就会以 {@code ContainerException} 硬失败。
 * {@code ComponentScanner.scanDirectory} 遍历的是某个包已编译的 {@code .class}
 * 文件，而不是某个测试对它们的具体用法——因此一个仅仅*编译进*
 * {@code com.ultikits.ultitools.context}（无论嵌套与否）的类，能被任何扫描该包的测试发现，
 * 无论声明该类的测试自身是否调用过 {@code scanPackage}。已有若干测试正是这样做的（例如
 * {@code ComponentScannerTest} 的 {@code freshScanner.scanPackage(
 * "com.ultikits.ultitools.context")}），而 {@code scanDirectory} 会递归进入子包——本计划自身
 * 执行过程中就实测到：若把带畸形 {@code @Bean} 的 fixture 放在 {@code context/}
 * 下任意位置，一旦畸形声明硬失败（本计划自身的交付物）开始正确传播，就会中止那些无关的扫描。
 * 本包隔离这个畸形 fixture 的方式，与 {@link com.ultikits.testfixtures.finalviolation.scanner}
 * 出于同样原因隔离一个 {@code @Final} 违规 fixture 完全一致。
 */
package com.ultikits.testfixtures.beanname;
