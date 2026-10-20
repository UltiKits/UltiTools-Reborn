/**
 * Fixtures for {@code PluginManagerRegistrationParityTest} (WIRE-05/WIRE-06, #203/#326):
 * {@code PluginManager.register(UltiToolsPlugin)} and {@code PluginManager.initializePlugin}
 * assembled through one shared method, closing five of the nine measured capability differences
 * between the two registration entry points.
 * <p>
 * {@link ParityFixtureModule} deliberately combines every difference this plan closes into one
 * module main class -- {@code @ContextEntry}, an {@code @Autowired} field pointing at its own
 * scanned {@code @Service}, a {@code @PostConstruct} method observing {@code getContext()}, and a
 * {@code private static} instance field -- so the capability-set parity test can assemble the
 * exact same class through both entry points and compare the resulting containers directly,
 * rather than needing one narrow fixture per difference.
 * <p>
 * These are real, top-level files rather than nested test classes, for the same two reasons
 * {@code com.ultikits.testfixtures.registersingletonordering}'s package javadoc already records:
 * {@code ComponentScanner.scanPackage} needs an actual classpath entry to discover, and the
 * module main class is packaged into a throwaway jar and loaded through an isolated classloader
 * because {@code UltiToolsPlugin}'s no-arg constructor reads {@code plugin.yml} from its own
 * class's {@code CodeSource}, which must be a real jar file, not the {@code target/test-classes}
 * directory Surefire runs from.
 * <br>
 * 为 {@code PluginManagerRegistrationParityTest}（WIRE-05/WIRE-06，#203/#326）提供 fixture：
 * {@code PluginManager.register(UltiToolsPlugin)} 与 {@code PluginManager.initializePlugin}
 * 现在经过同一个共享装配方法，关闭了两个注册入口点之间九个已测得能力差异中的五个。
 * <p>
 * {@link ParityFixtureModule} 刻意把本计划关闭的每一个差异都合并进同一个模块主类——
 * {@code @ContextEntry}、一个指向自身扫描到的 {@code @Service} 的 {@code @Autowired} 字段、
 * 一个观察 {@code getContext()} 的 {@code @PostConstruct} 方法，以及一个
 * {@code private static} instance 字段——这样容量集对等测试就能把同一个类经过两个入口点分别
 * 装配，直接比较两个容器，而不需要为每个差异单独准备一份 fixture。
 */
package com.ultikits.testfixtures.registrationparity;
