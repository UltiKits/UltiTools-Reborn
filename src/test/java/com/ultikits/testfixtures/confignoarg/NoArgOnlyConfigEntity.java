package com.ultikits.testfixtures.confignoarg;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * Only a no-arg constructor, hardcoding its path via {@code super(...)} - the shape of the 11
 * production config classes holding 146 constraints that were silently inert before this plan
 * (D-02). {@code ConfigManager.registerAll} must still register it successfully.
 * <p>
 * 只有一个通过 {@code super(...)} 硬编码路径的无参构造函数——这正是 11 个生产配置类的形态，
 * 它们持有的 146 条约束在本计划之前一直静默失效（D-02）。{@code ConfigManager.registerAll}
 * 仍然必须能成功注册它。
 */
@ConfigEntity("config/noarg.yml")
public class NoArgOnlyConfigEntity extends AbstractConfigEntity {

    @ConfigEntry(path = "value", comment = "A value")
    private String value = "default";

    public NoArgOnlyConfigEntity() {
        super("config/noarg.yml");
    }
}
