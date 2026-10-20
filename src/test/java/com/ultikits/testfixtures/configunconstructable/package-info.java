/**
 * A single top-level {@code @ConfigEntity} class that exposes neither a {@code (String)}
 * constructor nor an accessible no-arg constructor - the genuine refusal D-03 keeps.
 * <p>
 * Lives in its own package, not {@code com.ultikits.ultitools.manager}, so a
 * {@code PackageScanUtils} scan of the manager package cannot pick it up. This repository
 * already uses {@code com.ultikits.testfixtures.*} for exactly that reason (Phase 3, malformed
 * {@code @Bean}/{@code @AliasFor} fixtures).
 * <p>
 * 一个顶层 {@code @ConfigEntity} 类，既没有 {@code (String)} 构造函数，也没有可访问的无参
 * 构造函数——这是 D-03 保留的那个真正的拒绝场景。<br>
 * 单独放在一个包下而不是 {@code com.ultikits.ultitools.manager}，这样对 manager 包的
 * {@code PackageScanUtils} 扫描才不会误扫到它。仓库里已经用 {@code com.ultikits.testfixtures.*}
 * 做过同样的事（第 3 阶段，畸形的 {@code @Bean}/{@code @AliasFor} fixture）。
 */
package com.ultikits.testfixtures.configunconstructable;
