/**
 * Fixture package for {@code com.ultikits.ultitools.context.RequiredDependencyModuleIsolationTest}:
 * holds exactly one {@code @Service} ({@link com.ultikits.ultitools.context.isolationfixture.broken.BrokenService})
 * whose required {@code @Autowired} dependency can never resolve. This package is scanned and
 * refreshed on its own {@code SimpleContainer}, deliberately separate from the sibling
 * {@code .clean} package's container, so the test can assert that this container's own scan
 * aborts without affecting the sibling container's scan.
 * <br>
 * {@code RequiredDependencyModuleIsolationTest} 的 fixture 包：只包含一个必需
 * {@code @Autowired} 依赖永远无法解析的 {@code @Service}
 * （{@link com.ultikits.ultitools.context.isolationfixture.broken.BrokenService}）。本包会被扫描并
 * 在它自己独立的 {@code SimpleContainer} 上刷新，与同级 {@code .clean} 包的容器故意分开，
 * 以便测试断言本容器自身的扫描会中止，且不影响同级容器的扫描。
 */
package com.ultikits.ultitools.context.isolationfixture.broken;
