/**
 * Fixtures for {@code RegisterSingletonAssemblyTest}'s {@code initializePlugin}-level proof that
 * the plugin instance's own {@code registerSingleton} call runs AFTER component scanning, so an
 * {@code @Autowired} field on the module main class resolves against its own scanned
 * {@code @Service} beans (03-08, Task 2, T-03-27).
 * <p>
 * These two classes are real, top-level files rather than nested test classes -- both so
 * {@code ComponentScanner.scanPackage} has an actual classpath entry to discover (see
 * {@code com.ultikits.testfixtures.conditionalcommand}'s package javadoc for the established
 * reason nested static test classes are not reliably found by that scan) and, more specifically
 * for this fixture, so the module main class can be packaged into a throwaway jar and loaded
 * through an isolated classloader: {@code UltiToolsPlugin}'s no-arg constructor reads
 * {@code plugin.yml} from its own class's {@code CodeSource}, which must be a real jar file, not
 * the {@code target/test-classes} directory Surefire runs from.
 * <br>
 * 为 {@code RegisterSingletonAssemblyTest} 的 {@code initializePlugin} 级证明提供 fixture：
 * 插件实例自身的 {@code registerSingleton} 调用必须在组件扫描之后运行，这样模块主类上的
 * {@code @Autowired} 字段才能解析到它自己扫描到的 {@code @Service} bean（03-08，Task 2，
 * T-03-27）。
 */
package com.ultikits.testfixtures.registersingletonordering;
