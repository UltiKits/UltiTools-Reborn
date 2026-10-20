/**
 * One {@code @ConfigEntity} class whose {@code count} field always violates its own
 * {@code @Range} constraint - scanning just this subpackage must register nothing (#358 Part
 * 1's "single failing class leaves no entry" case).
 * <p>
 * 一个 {@code count} 字段必然违反自身 {@code @Range} 约束的 {@code @ConfigEntity} 类——只扫描
 * 这个子包必须什么都不注册（#358 第一部分的「唯一一个校验失败的类」场景）。
 */
package com.ultikits.testfixtures.configstranded.bad;
