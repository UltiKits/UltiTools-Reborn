/**
 * A single top-level {@code @ConfigEntity} class with only a no-arg constructor that hardcodes
 * its path via {@code super(path)} - the framework-documented idiom D-03 explicitly refuses to
 * break. {@code ConfigManager.registerAll} must still register it successfully.
 * <p>
 * 一个顶层 {@code @ConfigEntity} 类，只有一个通过 {@code super(path)} 硬编码路径的无参构造函数
 * ——这是框架文档记载的写法，D-03 明确表示不能破坏它。{@code ConfigManager.registerAll}
 * 仍然必须能成功注册它。
 */
package com.ultikits.testfixtures.confignoarg;
