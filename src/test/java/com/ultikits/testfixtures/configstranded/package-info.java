/**
 * Fixtures for #358 Part 1 - a refused module's second {@code @ConfigEntity} class must not
 * leave an earlier, already-registered sibling stranded in {@code ConfigManager}'s registry.
 * {@code ok/} holds two independently-valid classes; {@code bad/} holds one that always fails
 * its own {@code @Range} constraint. Scanning this parent package picks up all three, in an
 * unspecified order ({@code PackageScanUtils.scanAnnotatedClasses} returns a {@code HashSet}).
 * <p>
 * #358 第一部分的固件——一个被拒绝的模块，其第二个 {@code @ConfigEntity} 类不能让已经注册成功
 * 的前一个类遗留在 {@code ConfigManager} 的注册表里。{@code ok/} 下是两个各自独立且校验通过
 * 的类；{@code bad/} 下是一个必然违反自身 {@code @Range} 约束的类。扫描这个父包会同时命中
 * 三者，且顺序不确定（{@code PackageScanUtils.scanAnnotatedClasses} 返回的是 {@code HashSet}）。
 */
package com.ultikits.testfixtures.configstranded;
