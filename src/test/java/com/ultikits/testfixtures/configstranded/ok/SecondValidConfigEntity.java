package com.ultikits.testfixtures.configstranded.ok;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * An ordinary, always-valid config class - the other half of #358 Part 1's "both classes valid"
 * fixture pair. See {@link FirstValidConfigEntity}.
 */
@ConfigEntity("config/stranded-second.yml")
public class SecondValidConfigEntity extends AbstractConfigEntity {

    @ConfigEntry(path = "value", comment = "Another value that always passes validation")
    private String value = "default";

    public SecondValidConfigEntity(String configFilePath) {
        super(configFilePath);
    }
}
