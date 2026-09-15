package com.ultikits.testfixtures.configstranded.bad;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;

/**
 * {@code count} is constrained to {@code [1, 10]}. The test that scans this package pre-seeds
 * the on-disk file with an out-of-range value so the key is already present before {@code
 * init()} runs - the missing-key defaulting branch never fires and no write happens before
 * {@code validateFields()} throws (#358 Part 1's "no configuration file is written during
 * validation" requirement).
 */
@ConfigEntity("config/stranded-bad.yml")
public class InvalidConfigEntity extends AbstractConfigEntity {

    @ConfigEntry(path = "count", comment = "A count constrained to [1, 10]")
    @Range(min = 1, max = 10)
    private int count = 5;

    public InvalidConfigEntity(String configFilePath) {
        super(configFilePath);
    }
}
