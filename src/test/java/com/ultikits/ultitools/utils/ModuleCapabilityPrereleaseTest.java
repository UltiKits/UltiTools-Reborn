package com.ultikits.ultitools.utils;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleCapabilityPrereleaseTest {
    @ParameterizedTest
    @CsvSource({
        "2.0-rc1,2.0.0",
        "v2.7-beta3,2.7.0",
        "2.0+build.9,2.0.0",
        "2.0.4-rc1,2.0.4",
        "01.02-SNAPSHOT,1.2.0",
        "2,2.0.0"
    })
    void metadataDigitsNeverFillMissingCoreVersionParts(String input, String expected) {
        assertThat(ModuleCapabilities.version(input)).isEqualTo(expected);
    }
}
