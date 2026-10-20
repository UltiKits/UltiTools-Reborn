/**
 * Fixtures for proving {@code ListenerManager}'s bean-resolution {@code registerAll(UltiToolsPlugin)}
 * overload honours {@code @EventListener(manualRegister = true)} on the {@code
 * PluginManager.register(UltiToolsPlugin)} connector path, exactly as it already does on the
 * standard module-JAR path (WIRE-05 difference #7, plan 04-08).
 * <p>
 * Two real, top-level {@code @EventListener} classes so both the
 * {@code com.google.common.reflect.ClassPath}-backed {@code PackageScanUtils.scanAnnotatedClasses}
 * (the pre-fix package-scan path) and {@code ComponentScanner.scanPackage} (the post-fix
 * bean-resolution path) have an actual classpath entry to discover -- nested static test classes
 * are not reliably findable by either scan, so these live as their own files rather than inside
 * the test class that exercises them.
 * <br>
 * 用于证明 {@code ListenerManager} 的按 bean 解析的 {@code registerAll(UltiToolsPlugin)} 重载在
 * {@code PluginManager.register(UltiToolsPlugin)} 连接器路径上遵循
 * {@code @EventListener(manualRegister = true)}，与它在标准模块 JAR 路径上早已做到的一致
 * （WIRE-05 差异 #7，计划 04-08）的 fixture。这里使用两个真实的顶层 {@code @EventListener}
 * 类，以便修复前的包扫描路径（{@code PackageScanUtils.scanAnnotatedClasses}）与修复后的按 bean
 * 解析路径（{@code ComponentScanner.scanPackage}）都能实际发现它们——嵌套的静态测试类未必能被
 * 任何一种扫描可靠发现，因此它们各自独立成文件，而不是放在使用它们的测试类内部。
 */
package com.ultikits.testfixtures.manualregisterlistener;
