/**
 * Fixtures for proving {@code ListenerManager.registerAll(UltiToolsPlugin, String)} honours
 * {@code @ConditionalOnConfig} on the reflective package-scan path (WIRE-07, D-17).
 * <p>
 * Two real, top-level {@code @EventListener} classes so the
 * {@code com.google.common.reflect.ClassPath}-backed {@code PackageScanUtils.scanAnnotatedClasses}
 * has an actual classpath entry to discover -- nested static test classes are not reliably
 * findable by that scan, so these live as their own files rather than inside the test class
 * that exercises them.
 * <br>
 * 用于证明 {@code ListenerManager.registerAll(UltiToolsPlugin, String)} 在反射式包扫描路径上
 * 遵循 {@code @ConditionalOnConfig}（WIRE-07，D-17）的 fixture。这里使用两个真实的顶层
 * {@code @EventListener} 类，以便基于 {@code com.google.common.reflect.ClassPath} 的
 * {@code PackageScanUtils.scanAnnotatedClasses} 能实际发现它们——嵌套的静态测试类未必能被该扫描
 * 可靠发现，因此它们各自独立成文件，而不是放在使用它们的测试类内部。
 */
package com.ultikits.testfixtures.conditionallistener;
