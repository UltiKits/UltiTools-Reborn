package com.ultikits.testfixtures.conditionallistenerdispatch;

import java.util.concurrent.atomic.AtomicInteger;

import com.ultikits.ultitools.annotations.ConditionalOnConfig;
import com.ultikits.ultitools.annotations.EventListener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * A listener whose {@code @ConditionalOnConfig} condition is deliberately configured to evaluate
 * {@code false} in the paired test's config file. If {@code ListenerManager} ever registers this
 * class with Bukkit despite the false condition, firing a {@link PlayerJoinEvent} would increment
 * {@link #HITS} -- which the test asserts stays at zero.
 * <br>
 * 一个 {@code @ConditionalOnConfig} 条件在配套测试的配置文件中被特意设为 {@code false} 的监听器。
 * 如果 {@code ListenerManager} 无视 false 条件仍然向 Bukkit 注册了该类，触发一次
 * {@link PlayerJoinEvent} 就会使 {@link #HITS} 递增——测试断言它必须保持为零。
 */
@EventListener
@ConditionalOnConfig(value = "config/config.yml", path = "enableFalseListener")
public class FalseConditionDispatchListener implements Listener {

    public static final AtomicInteger HITS = new AtomicInteger(0);

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        HITS.incrementAndGet();
    }
}
