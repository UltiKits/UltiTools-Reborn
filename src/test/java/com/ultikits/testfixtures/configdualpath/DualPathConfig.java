package com.ultikits.testfixtures.configdualpath;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.annotations.ConfigEntity;

/**
 * Declares {@code config/dualpath.yml}. Carries a {@code (String)} constructor so both
 * {@code ConfigManager.registerAll}'s scan and a hand-built {@code getAllConfigs()} instance
 * construct it the same way.
 * <br>
 * 声明 {@code config/dualpath.yml}。带 {@code (String)} 构造函数，使包扫描与手工构造的
 * {@code getAllConfigs()} 实例走同一条构造路径。
 */
@ConfigEntity("config/dualpath.yml")
public class DualPathConfig extends AbstractConfigEntity {

    @ConfigEntry(path = "threshold", comment = "Dual-path threshold")
    @Range(min = 1, max = 100)
    private int threshold = 10;

    public DualPathConfig(String configFilePath) {
        super(configFilePath);
    }

    public int getThreshold() {
        return threshold;
    }
}
