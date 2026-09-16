package com.ultikits.testfixtures.configstranded.ok;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * An ordinary, always-valid config class - one half of #358 Part 1's "both classes valid"
 * fixture pair. See {@link SecondValidConfigEntity} for the other half.
 */
@ConfigEntity("config/stranded-first.yml")
public class FirstValidConfigEntity extends AbstractConfigEntity {

    @ConfigEntry(path = "value", comment = "A value that always passes validation")
    private String value = "default";

    public FirstValidConfigEntity(String configFilePath) {
        super(configFilePath);
    }
}
