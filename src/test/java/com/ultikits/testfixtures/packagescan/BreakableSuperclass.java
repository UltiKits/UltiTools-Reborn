package com.ultikits.testfixtures.packagescan;

/**
 * A type that compiles and sits on the classpath normally, but is deliberately blocked by a
 * dedicated test class loader in {@code PackageScanUtilsTest} to simulate a superclass genuinely
 * absent from the classpath at runtime - the same shape a Phase 7-removed symbol (e.g.
 * {@code AbstractDataEntity}) has for a module JAR compiled against an older UltiTools-API.
 * <br>
 * 一个正常编译、正常在类路径上的类型，{@code PackageScanUtilsTest} 会用专门的类加载器
 * 阻断对它的解析，用来模拟运行时真正缺失的父类——这正是 Phase 7 移除的符号（例如
 * {@code AbstractDataEntity}）对一个基于旧版 UltiTools-API 编译的模块 JAR 而言的真实形态。
 *
 * @see BreakingConfigEntity
 */
public class BreakableSuperclass {
}
