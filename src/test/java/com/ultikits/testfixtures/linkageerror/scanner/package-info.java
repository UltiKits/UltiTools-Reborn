/**
 * The two real, compiled fixture classes {@code ComponentScannerTest} drives its LinkageError
 * skip-and-continue tests against.
 * <p>
 * {@link com.ultikits.testfixtures.linkageerror.scanner.LinkageErrorBreakingFixture}'s own
 * {@code .class} bytes are never actually read in these tests - a test-only {@code ClassLoader}
 * intercepts {@code loadClass} for its exact name and throws a chosen {@code LinkageError}
 * before delegation, on both the directory-mode and the jar-mode path. It only needs to exist as
 * a real file on disk so {@code ComponentScanner#scanDirectory}'s {@code File.listFiles()} walk
 * (directory mode) and a hand-built temporary JAR's entry list (jar mode) both have a real class
 * name to name.
 * {@link com.ultikits.testfixtures.linkageerror.scanner.LinkageErrorSurvivorFixture} is the
 * control: a plain {@code @Component} that must still register after its sibling is skipped.
 * <br>
 * {@code ComponentScannerTest} 用来驱动 LinkageError 跳过并继续测试的两个真实、已编译 fixture
 * 类。破坏类自身的 {@code .class} 字节内容从未被真正读取——测试专用的 {@code ClassLoader} 会针对
 * 其确切类名拦截 {@code loadClass} 并在委派之前抛出预设的 {@code LinkageError}，目录模式和 JAR
 * 模式两条路径皆是如此；它只需要在磁盘上作为一个真实文件存在，以便目录遍历与手工构造的临时 JAR
 * 条目列表都能引用到一个真实的类名。存活 fixture 是对照组：一个普通的 {@code @Component}，
 * 必须在其同级类被跳过之后仍然完成注册。
 */
package com.ultikits.testfixtures.linkageerror.scanner;
