package com.ultikits.testfixtures.conditionallistener;

import com.ultikits.ultitools.annotations.ConditionalOnConfig;
import com.ultikits.ultitools.annotations.EventListener;

import org.bukkit.event.Listener;

/**
 * A listener whose {@code @ConditionalOnConfig} condition is deliberately configured to
 * evaluate {@code true} in the paired test's config file -- the control proving the
 * false-condition sibling's absence is due to the gate, not an unrelated scan failure.
 * <br>
 * 一个 {@code @ConditionalOnConfig} 条件在配套测试的配置文件中被特意设为 {@code true} 的
 * 监听器——作为对照，证明另一个 false 条件监听器的缺席是因为门控生效，而不是扫描本身出了问题。
 */
@EventListener
@ConditionalOnConfig(value = "config/config.yml", path = "enableTrueListener")
public class TrueConditionListener implements Listener {
}
