package com.ultikits.testfixtures.configstrandedmulti.pkgb;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;

/**
 * {@code count} is constrained to {@code [1, 10]} and defaults to a value outside it, so this
 * class always refuses registration regardless of whether its own file pre-exists.
 */
@ConfigEntity("config/multipkg-b.yml")
public class InvalidMultiPackageConfigEntity extends AbstractConfigEntity {

    @ConfigEntry(path = "count", comment = "A count constrained to [1, 10]")
    @Range(min = 1, max = 10)
    private int count = 999;

    public InvalidMultiPackageConfigEntity(String configFilePath) {
        super(configFilePath);
    }
}
