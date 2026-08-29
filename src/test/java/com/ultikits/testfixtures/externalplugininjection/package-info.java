/**
 * Top-level (not nested) test-only fixtures for {@code ExternalPluginAdapterTest}'s
 * child-container {@code JavaPlugin} injection coverage (SILENT-16, #331). Each fixture is a
 * {@code @Service} whose sole constructor takes one dependency type, letting the test assert on
 * exactly which instance the container handed it -- the connector's own {@code JavaPlugin}, its
 * concrete class, or the framework's {@code UltiToolsPlugin} -- without the fixture doing any of
 * its own resolution.
 * <br>
 * 为 {@code ExternalPluginAdapterTest} 的子容器 {@code JavaPlugin} 注入覆盖（SILENT-16，#331）
 * 准备的顶层（非嵌套）测试专用 fixture。每个 fixture 都是一个只有单参构造函数的
 * {@code @Service}，让测试可以直接断言容器交给它的到底是哪个实例——连接器自己的
 * {@code JavaPlugin}、它的具体类，还是框架的 {@code UltiToolsPlugin}——而不需要 fixture 自己
 * 做任何解析。
 */
package com.ultikits.testfixtures.externalplugininjection;
