/**
 * Test-only fixtures for {@code ReflectionUtilTest}: hierarchies whose {@code slot()} declarations
 * exercise the package-sensitive half of JLS 8.4.8.1, which cannot be reproduced inside a single
 * test source file because it needs two real, distinct packages.
 * <p>
 * The family is deliberately tiny and every class declares the same method name, {@code slot()},
 * so a test can express its expectation as "how many entries named {@code slot} does
 * {@code getAllMethods} return, and which class declares the winner".
 * <p>
 * Nothing here violates any framework contract - these are ordinary, legal Java classes - so unlike
 * {@link com.ultikits.testfixtures.finalviolation} this package needs no quarantine rule. It lives
 * under {@code com.ultikits.testfixtures} only because its subpackages must be distinct packages.
 * <p>
 * 本包为 {@code ReflectionUtilTest} 提供 fixture：用两个真实存在的不同包，复现 JLS 8.4.8.1 中与
 * 包相关的那部分规则——这部分无法在单个测试源文件内构造。所有类都声明同名方法 {@code slot()}，
 * 因此测试可以直接断言"{@code getAllMethods} 返回了几条名为 {@code slot} 的方法、胜出的那条由
 * 哪个类声明"。本包中的类都是合法的普通 Java 类，不违反任何框架契约，无需隔离。
 */
package com.ultikits.testfixtures.overrideslots;
