package com.ultikits.testfixtures.configstrandedmulti.pkga;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * A construction counter alongside the always-valid field (IN-02): counts every time this class
 * is constructed, whether by {@code ConfigManager.registerAll}'s scan-and-construct step or by
 * {@code AbstractConfigEntity#ensureConstructable()}'s own throwaway instance during validation
 * - direct evidence that the CR-01 multi-package path constructs this class only as many times
 * as the framework's own documented contract requires, not once per scan package.
 */
@ConfigEntity("config/multipkg-a.yml")
public class ValidMultiPackageConfigEntity extends AbstractConfigEntity {

    public static final java.util.concurrent.atomic.AtomicInteger CONSTRUCTION_COUNT =
            new java.util.concurrent.atomic.AtomicInteger();

    @ConfigEntry(path = "value", comment = "A value that always passes validation")
    private String value = "default";

    public ValidMultiPackageConfigEntity(String configFilePath) {
        super(configFilePath);
        CONSTRUCTION_COUNT.incrementAndGet();
    }
}
