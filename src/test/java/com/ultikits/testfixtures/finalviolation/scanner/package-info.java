/**
 * Fixtures for {@code com.ultikits.ultitools.context.ComponentScannerFinalContractTest} - an
 * <b>integration</b> test whose entire point is to run {@code ComponentScanner.scanPackage}
 * (and {@code SimpleContainer.scanComponents}) over this package and assert that the resulting
 * {@code ContainerException} both fires and names {@link ViolatingComponent} specifically,
 * proving the exception actually propagates out of {@code scanPackage}'s catch-all rather than
 * being logged and swallowed.
 * <p>
 * <b>This package must hold exactly one violation.</b> {@code ComponentScanner.processClass}
 * throws on the first violating class {@code scanDirectory}'s walk encounters, and
 * {@code File.listFiles()} order is filesystem-dependent, not guaranteed by the Java spec. A
 * second, unrelated violation added here would make which class's name ends up in the exception
 * message nondeterministic - exactly what happened when {@code FinalContractValidatorTest}'s
 * fixtures were briefly colocated in this same package during issue #190's development (see
 * {@link com.ultikits.testfixtures.finalviolation.validator}, where they live now instead).
 * A new scan-triggered violation fixture belongs in its own sibling package, not in this one.
 * <p>
 * 本包服务于 {@code ComponentScannerFinalContractTest}——一个**集成测试**，整个测试的意义就是让
 * {@code ComponentScanner.scanPackage} 真正扫描本包，断言抛出的 {@code ContainerException} 携带
 * {@link ViolatingComponent} 这个具体类名，以证明异常确实穿透了 {@code scanPackage} 的
 * catch-all 传播出来，而不是被记录后吞掉。
 * <p>
 * <b>本包必须恰好只有一个违规源。</b>{@code ComponentScanner.processClass} 在
 * {@code scanDirectory} 遍历到的第一个违规类处就会抛出，而 {@code File.listFiles()} 的顺序依赖
 * 文件系统、Java 规范并不保证。若本包内再混入第二个无关违规，异常消息里到底是哪个类名就会变得
 * 不确定——这正是 issue #190 开发过程中，{@code FinalContractValidatorTest} 的 fixture 曾短暂
 * 与本包共存时踩到的坑（现已移至 {@link com.ultikits.testfixtures.finalviolation.validator}）。
 * 之后再需要新增"会被扫描触发"的违规 fixture，请放进独立的同级包，不要加进本包。
 */
package com.ultikits.testfixtures.finalviolation.scanner;
