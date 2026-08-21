package com.ultikits.testfixtures.missingdependency;

/**
 * A type that compiles and sits on the classpath normally, but is deliberately hidden from a
 * dedicated test class loader to simulate a dependency absent at runtime.
 * <br>
 * 一个正常编译、正常在类路径上的类型，测试中会被专门的类加载器刻意隐藏，用来模拟运行时缺失的依赖。
 *
 * @see HasMethodReferencingMissingType
 */
public class MissingDependencyType {
}
