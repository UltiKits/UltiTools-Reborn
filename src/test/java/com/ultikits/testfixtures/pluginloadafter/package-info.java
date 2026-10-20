/**
 * Top-level (not nested) test-only fixtures for {@code PluginDependencyResolverTest}'s
 * {@code plugin.yml} {@code loadAfter} merge coverage (D-12). These classes must be top-level,
 * not static nested classes of the test: a static nested class carries an EnclosingClass
 * reference, and resolving it after the class has been re-loaded through a synthetic
 * child-first {@code URLClassLoader} (built over a temp JAR, so
 * {@code getProtectionDomain().getCodeSource()} is genuinely a JAR) throws
 * {@link IllegalAccessError} because the nested class and its enclosing class would then be
 * defined by two different class loaders - a JVM nest-membership violation, not a bug in the
 * test's own logic. A top-level class has no such reference and loads cleanly.
 * <br>
 * 为 {@code PluginDependencyResolverTest} 的 {@code plugin.yml} {@code loadAfter} 合并测试
 * （D-12）准备的顶层（非嵌套）测试专用 fixture。这些类必须是顶层类，不能是测试类的静态嵌套类：
 * 静态嵌套类携带一个外部类引用，当该类被重新通过一个基于临时 JAR 构建的子加载器优先
 * {@code URLClassLoader} 加载后（这样 {@code getProtectionDomain().getCodeSource()} 才会
 * 真正指向一个 JAR），解析这个外部类引用会抛出 {@link IllegalAccessError}——因为此时嵌套类
 * 与其外部类分别由两个不同的类加载器定义，这是 JVM 的嵌套成员校验冲突，不是测试逻辑本身的缺陷。
 * 顶层类没有这种引用，可以正常加载。
 */
package com.ultikits.testfixtures.pluginloadafter;
