/**
 * Two structurally identical module fixtures in DISJOINT sub-packages, used by {@code
 * PluginManagerContainerIsolationTest} (UAT-03 / UAT-04) to prove that assembling one module
 * never leaks beans into another's container.
 * <p>
 * The sub-packages must be disjoint: {@code PluginManager.getPluginScanPackages} falls back to
 * the module class's own package when neither {@code @UltiToolsModule} nor {@code @ComponentScan}
 * is present, so two modules sharing a package would each scan up BOTH services and the isolation
 * assertion would be vacuous.
 * <br>
 * 两个结构相同但分处**不相交**子包的模块 fixture，供 {@code PluginManagerContainerIsolationTest}
 * （UAT-03 / UAT-04）证明装配一个模块绝不会把 bean 泄漏进另一个模块的容器。子包必须不相交：
 * 在没有 {@code @UltiToolsModule}/{@code @ComponentScan} 时扫描包会回退到模块类自身的包，
 * 同包的两个模块会各自扫到两个 service，使隔离断言变得空洞。
 */
package com.ultikits.testfixtures.containerisolation;
