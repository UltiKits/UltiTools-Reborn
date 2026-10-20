/**
 * A single {@code @ConfigEntity} class reachable by package scan, used to prove that the same
 * {@code configFilePath} arriving through BOTH registration routes -- {@code
 * ConfigManager.registerAll}'s package scan and a module's {@code getAllConfigs()} override --
 * occupies exactly one slot in {@code ConfigManager.pluginConfigMap} (UAT-02).
 * <p>
 * 一个可被包扫描发现的 {@code @ConfigEntity} 类，用于证明同一个 {@code configFilePath} 经由
 * 两条注册路径（包扫描自动注册与模块的 {@code getAllConfigs()} 覆盖）到达时，在
 * {@code ConfigManager.pluginConfigMap} 中只占一个槽位（UAT-02）。
 */
package com.ultikits.testfixtures.configdualpath;
