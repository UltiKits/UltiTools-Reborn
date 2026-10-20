/**
 * Fixtures for proving that {@code @ConditionalOnConfig} on a {@code @CmdExecutor} class already
 * works on the standard module-JAR registration path (D-19), pinned at the actual Bukkit command
 * map boundary rather than assumed. Two real, top-level command classes so
 * {@code ComponentScanner.scanPackage} has an actual classpath entry to discover -- nested static
 * test classes are not reliably findable by that scan, so these live as their own files rather
 * than inside the test class that exercises them.
 * <br>
 * 用于证明 {@code @ConditionalOnConfig} 在标准模块 JAR 注册路径（D-19）上已经生效的 fixture，
 * 并在真实的 Bukkit 命令表边界上钉住这一点，而不是假设它成立。这里使用两个真实的顶层命令类，
 * 以便 {@code ComponentScanner.scanPackage} 能实际发现它们——嵌套的静态测试类未必能被该扫描
 * 可靠发现，因此它们各自独立成文件，而不是放在使用它们的测试类内部。
 */
package com.ultikits.testfixtures.conditionalcommand;
