package com.ultikits.testfixtures.conditionallistener;

import com.ultikits.ultitools.annotations.ConditionalOnConfig;
import com.ultikits.ultitools.annotations.EventListener;

import org.bukkit.event.Listener;

/**
 * A listener whose {@code @ConditionalOnConfig} condition is deliberately configured to
 * evaluate {@code false} in the paired test's config file. {@code ListenerManager}'s
 * package-scan path must never instantiate this class.
 * <br>
 * 一个 {@code @ConditionalOnConfig} 条件在配套测试的配置文件中被特意设为 {@code false} 的
 * 监听器。{@code ListenerManager} 的包扫描路径绝不能实例化该类。
 */
@EventListener
@ConditionalOnConfig(value = "config/config.yml", path = "enableFalseListener")
public class FalseConditionListener implements Listener {
}
