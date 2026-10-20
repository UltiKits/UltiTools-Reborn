package com.ultikits.testfixtures.conditionallistenerdispatch;

import java.util.concurrent.atomic.AtomicInteger;

import com.ultikits.ultitools.annotations.ConditionalOnConfig;
import com.ultikits.ultitools.annotations.EventListener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * A listener whose {@code @ConditionalOnConfig} condition is deliberately configured to evaluate
 * {@code true} in the paired test's config file -- the control proving that a fired
 * {@link PlayerJoinEvent} genuinely reaches a correctly-registered listener, so the false-condition
 * sibling's zero {@code HITS} means the gate worked rather than event dispatch itself being broken.
 * <br>
 * 一个 {@code @ConditionalOnConfig} 条件在配套测试的配置文件中被特意设为 {@code true} 的监听器——
 * 作为对照，证明触发的 {@link PlayerJoinEvent} 确实能到达一个被正确注册的监听器，从而说明另一个
 * false 条件监听器的零命中是门控生效的结果，而不是事件分发机制本身出了问题。
 */
@EventListener
@ConditionalOnConfig(value = "config/config.yml", path = "enableTrueListener")
public class TrueConditionDispatchListener implements Listener {

    public static final AtomicInteger HITS = new AtomicInteger(0);

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        HITS.incrementAndGet();
    }
}
