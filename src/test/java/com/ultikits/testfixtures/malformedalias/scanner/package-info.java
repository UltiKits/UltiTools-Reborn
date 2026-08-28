/**
 * Fixture for {@code com.ultikits.ultitools.context.ComponentScannerMalformedAliasPropagationTest}
 * - an <b>integration</b> test whose entire point is to run {@code ComponentScanner.scanPackage}
 * over this package and assert that the resulting
 * {@code ContainerException} propagates all the way out, rather than being logged and swallowed
 * by {@code scanPackage}'s own catch-all.
 * <p>
 * <b>This package must hold exactly one malformed declaration.</b> Mirrors
 * {@code com.ultikits.testfixtures.finalviolation.scanner}'s own rule and the reason behind it:
 * {@code MergedAnnotationResolver#search} throws on the first malformed annotation type it
 * validates while walking a class's annotation tree, and both {@code File.listFiles()}'s
 * directory-walk order and {@code Class#getAnnotations()}'s array order are unspecified by the
 * relevant specs - a second, unrelated malformed declaration here would make the exception's
 * exact content nondeterministic. A new scan-triggered malformed-alias fixture belongs in its
 * own sibling package, not in this one.
 * <br>
 * 本包服务于
 * {@code ComponentScannerMalformedAliasPropagationTest}——一个**集成测试**，整个测试的意义就是让
 * {@code ComponentScanner.scanPackage} 真正扫描本包，断言抛出的 {@code ContainerException} 确实
 * 一路传播出来，而不是被 {@code scanPackage} 自身的 catch-all 记录后吞掉。
 * <p>
 * <b>本包必须恰好只有一个畸形声明。</b>与
 * {@code com.ultikits.testfixtures.finalviolation.scanner} 的规则及其理由完全一致：
 * {@code MergedAnnotationResolver#search} 在遍历一个类的注解树时，一遇到第一个畸形注解类型就会
 * 抛出，而 {@code File.listFiles()} 的目录遍历顺序与 {@code Class#getAnnotations()} 的数组顺序都
 * 不受相关规范保证。若本包内再混入第二个无关的畸形声明，异常的具体内容就会变得不确定。之后再需要
 * 新增"会被扫描触发"的畸形别名 fixture，请放进独立的同级包，不要加进本包。
 */
package com.ultikits.testfixtures.malformedalias.scanner;
